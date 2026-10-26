package watch.nabu.application.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watch.nabu.infrastructure.web.UrlUtils;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Generates dnstwist-style permutations of the verified domain, DNS-
 * resolves each, and reports the ones that already exist. Registered
 * look-alikes are a phishing-risk signal — not proof of abuse, but
 * worth knowing.
 *
 * <p>Kept deliberately bounded: the permutation generator caps at
 * {@link #MAX_CANDIDATES} variants and we resolve them in parallel via
 * a short-lived fixed-size pool with per-lookup timeout. Whole scan
 * finishes in seconds even on a slow resolver.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TyposquatWatchInspector {

    /** Cap on total permutations resolved — keeps worst-case runtime bounded. */
    static final int MAX_CANDIDATES = 60;
    private static final int PARALLELISM = 10;
    private static final int PER_LOOKUP_TIMEOUT_MS = 1_500;
    private static final int OVERALL_BUDGET_MS = 12_000;

    private final DnsLookup dns;

    private static final List<String> TLD_SWAP = List.of(
            "com", "net", "org", "io", "co", "app", "dev", "xyz", "info");

    /** Homoglyph pairs that commonly appear in phishing domains. */
    private static final List<String[]> HOMOGLYPHS = List.of(
            new String[]{"l", "1"}, new String[]{"l", "i"}, new String[]{"i", "1"},
            new String[]{"o", "0"}, new String[]{"rn", "m"}, new String[]{"vv", "w"},
            new String[]{"cl", "d"}, new String[]{"m", "rn"}, new String[]{"w", "vv"});

    public Result inspect(String websiteUrl) {
        String lower = UrlUtils.host(websiteUrl);
        if (lower == null || lower.isBlank()) return Result.failed(null, "URL has no host");

        int lastDot = lower.lastIndexOf('.');
        if (lastDot <= 0) return Result.failed(lower, "Domain has no TLD");
        String label = lower.substring(0, lastDot);
        String tld = lower.substring(lastDot + 1);

        Set<String> candidates = generatePermutations(label, tld);
        candidates.remove(lower);
        List<String> batch = candidates.stream().limit(MAX_CANDIDATES).toList();

        DirContext ctx = dns.newContext(String.valueOf(PER_LOOKUP_TIMEOUT_MS), "1");
        if (ctx == null) {
            return Result.failed(lower, "DNS resolver unavailable");
        }
        List<Finding> findings;
        try {
            findings = resolveParallel(ctx, batch);
        } finally {
            dns.close(ctx);
        }
        int score = findings.isEmpty() ? 100 : Math.max(0, 100 - findings.size() * 15);
        String verdict = findings.isEmpty()
                ? "No registered look-alikes found"
                : findings.size() + " look-alike" + (findings.size() == 1 ? "" : "s") + " registered";
        return new Result(true, lower, batch.size(), score, verdict, findings, null);
    }

    static Set<String> generatePermutations(String label, String tld) {
        Set<String> out = new LinkedHashSet<>();
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";

        // 1. TLD swap
        for (String alt : TLD_SWAP) {
            if (!alt.equals(tld)) out.add(label + "." + alt);
        }

        // 2. Character omission
        for (int i = 0; i < label.length(); i++) {
            out.add(label.substring(0, i) + label.substring(i + 1) + "." + tld);
        }

        // 3. Character repetition
        for (int i = 0; i < label.length(); i++) {
            out.add(label.substring(0, i + 1) + label.charAt(i) + label.substring(i + 1) + "." + tld);
        }

        // 4. Adjacent swap
        for (int i = 0; i < label.length() - 1; i++) {
            char[] c = label.toCharArray();
            char t = c[i]; c[i] = c[i + 1]; c[i + 1] = t;
            out.add(new String(c) + "." + tld);
        }

        // 5. Single-character replacement (keeps total bounded by length × 36)
        for (int i = 0; i < label.length(); i++) {
            for (int a = 0; a < alphabet.length(); a++) {
                char repl = alphabet.charAt(a);
                if (label.charAt(i) == repl) continue;
                out.add(label.substring(0, i) + repl + label.substring(i + 1) + "." + tld);
            }
        }

        // 6. Homoglyphs
        for (String[] pair : HOMOGLYPHS) {
            if (label.contains(pair[0])) out.add(label.replaceFirst(pair[0], pair[1]) + "." + tld);
            if (label.contains(pair[1])) out.add(label.replaceFirst(pair[1], pair[0]) + "." + tld);
        }

        // 7. Hyphenation (cheap, often under-detected)
        for (int i = 1; i < label.length(); i++) {
            out.add(label.substring(0, i) + "-" + label.substring(i) + "." + tld);
        }

        return out;
    }

    private List<Finding> resolveParallel(DirContext ctx, List<String> candidates) {
        var pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            List<CompletableFuture<Finding>> tasks = new ArrayList<>(candidates.size());
            for (String c : candidates) {
                tasks.add(CompletableFuture.supplyAsync(() -> resolveOne(ctx, c), pool));
            }
            CompletableFuture<Void> all = CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));
            all.orTimeout(OVERALL_BUDGET_MS, TimeUnit.MILLISECONDS);
            try {
                all.join();
            } catch (Exception e) {
                log.debug("[typosquat] parallel resolve ended early: {}", e.getMessage());
            }

            List<Finding> findings = new ArrayList<>();
            for (CompletableFuture<Finding> t : tasks) {
                Finding f = t.getNow(null);
                if (f != null) findings.add(f);
            }
            return findings;
        } finally {
            pool.shutdownNow();
        }
    }

    private Finding resolveOne(DirContext ctx, String host) {
        try {
            Attributes attrs = ctx.getAttributes(host, new String[]{"A"});
            if (attrs.get("A") != null) return new Finding(host, "A");
            Attributes mx = ctx.getAttributes(host, new String[]{"MX"});
            if (mx.get("MX") != null) return new Finding(host, "MX");
        } catch (NamingException ignored) {
            return null;
        } catch (Exception e) {
            log.debug("[typosquat] resolver error for {}: {}", host, e.getMessage());
            return null;
        }
        return null;
    }

    public record Finding(String host, String recordType) { }

    public record Result(
            boolean success,
            String apex,
            int candidatesScanned,
            int score,
            String verdict,
            List<Finding> findings,
            String error) {

        public static Result failed(String apex, String error) {
            return new Result(false, apex, 0, 0, null, List.of(), error);
        }
    }
}
