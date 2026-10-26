package watch.nabu.application.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Shared JNDI-DNS resolver for the security inspectors. Bypasses the
 * container's embedded resolver (which may serve stale records for the
 * authoritative TTL) by pointing directly at public resolvers, so a
 * recent DNS change shows up on the next scan.
 */
@Slf4j
@Service
public class DnsLookup {

    private static final String DNS_PROVIDERS =
            "dns://1.1.1.1 dns://8.8.8.8 dns://9.9.9.9";
    private static final String INITIAL_TIMEOUT_MS = "2000";
    private static final String RETRIES = "2";

    /**
     * Opens a fresh JNDI DNS context. Caller must {@link #close(DirContext)}.
     * Returns {@code null} when the JNDI provider can't be initialised.
     */
    public DirContext newContext() {
        return newContext(INITIAL_TIMEOUT_MS, RETRIES);
    }

    public DirContext newContext(String timeoutMs, String retries) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", DNS_PROVIDERS);
        env.put("com.sun.jndi.dns.timeout.initial", timeoutMs);
        env.put("com.sun.jndi.dns.timeout.retries", retries);
        try {
            return new InitialDirContext(env);
        } catch (NamingException e) {
            return null;
        }
    }

    public void close(DirContext ctx) {
        if (ctx == null) return;
        try { ctx.close(); } catch (NamingException ignored) { }
    }

    /**
     * Reads a single record-type attribute. Returns empty on missing record
     * (NXDOMAIN or no such type). Numeric types (e.g. "257" for CAA) return
     * stable byte-length placeholders since JNDI hands back raw RDATA bytes.
     */
    public List<String> lookup(DirContext ctx, String name, String type) {
        List<String> out = new ArrayList<>();
        try {
            Attributes attrs = ctx.getAttributes(name, new String[]{type});
            Attribute attr = attrs.get(type);
            if (attr == null) return out;
            NamingEnumeration<?> e = attr.getAll();
            while (e.hasMore()) {
                Object v = e.next();
                if (v == null) continue;
                if (v instanceof byte[] bytes) {
                    out.add("<" + bytes.length + " bytes>");
                } else {
                    out.add(stripQuotes(v.toString()));
                }
            }
            log.debug("[dns] {} {} => {}", name, type, out);
        } catch (NamingException ignored) {
            // Absent record → empty list.
        }
        return out;
    }

    /**
     * JNDI returns DNS strings quoted and sometimes space-concatenated.
     * Strip leading/trailing quotes so prefix-match checks see both the
     * {@code "v=spf1 …"} and {@code v=spf1 …} forms uniformly.
     */
    private static String stripQuotes(String s) {
        String t = s.replace("\" \"", "");
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
