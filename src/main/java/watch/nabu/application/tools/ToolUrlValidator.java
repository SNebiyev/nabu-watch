package watch.nabu.application.tools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates anonymous tool URLs against SSRF and abuse vectors.
 * <ul>
 *   <li>Scheme must be http(s); port must be in {@link #ALLOWED_PORTS}.</li>
 *   <li>Host must not resolve to a loopback / link-local / private / multicast address.</li>
 *   <li>Hostname blacklist (localhost, internal, .local, .internal).</li>
 * </ul>
 *
 * <p>Returns a {@link Resolved} record carrying the parsed URI together with
 * the IP address the validator already verified — downstream connectors must
 * use this IP rather than re-resolving the hostname, otherwise a DNS-rebinding
 * attacker can swap the public-IP answer for an internal one between checks.
 */
@Component
public class ToolUrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> BLOCKED_HOSTS = Set.of("localhost");
    private static final Set<String> BLOCKED_SUFFIXES = Set.of(".local", ".internal", ".localhost");
    private static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443, 8080, 8443);

    /**
     * Hostnames that bypass the private-IP rejection. Empty by default. Set it to
     * your own public hostname if the API container must scan the instance it runs
     * behind and Docker DNS resolves that name to a private 172.x address. The bypass is purely
     * about the resolved-IP check; scheme / port / blocked-suffix rules still
     * apply. Comma-separated env var: {@code TOOLS_URL_VALIDATOR_SELF_HOSTS}.
     */
    private final Set<String> selfHosts;

    public ToolUrlValidator(
            @Value("${nabu.tools.url-validator.self-hosts:}") String selfHostsRaw) {
        this.selfHosts = Arrays.stream(selfHostsRaw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));
    }

    public Resolved validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw bad("URL is required");
        }
        if (rawUrl.length() > 2048) {
            throw bad("URL too long");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw bad("Invalid URL");
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
            throw bad("Only http and https URLs are allowed");
        }

        int port = uri.getPort();
        if (port != -1 && !ALLOWED_PORTS.contains(port)) {
            throw bad("Port not allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw bad("URL must include a hostname");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(lower)) {
            throw bad("Local hostnames are not allowed");
        }
        for (String suffix : BLOCKED_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                throw bad("Internal hostnames are not allowed");
            }
        }

        boolean isSelf = selfHosts.contains(lower);
        InetAddress publicAddress = null;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                boolean isPrivateRange = addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isMulticastAddress()
                        || addr.isAnyLocalAddress();
                if (isPrivateRange && !isSelf) {
                    throw bad("URL resolves to a private or local address");
                }
                if (publicAddress == null) {
                    publicAddress = addr;
                }
            }
        } catch (UnknownHostException e) {
            throw bad("Hostname could not be resolved");
        }
        if (publicAddress == null) {
            throw bad("Hostname could not be resolved");
        }

        return new Resolved(uri, publicAddress);
    }

    /**
     * Validation output: the parsed URI and the resolved-and-checked IP that
     * downstream connectors must use to defeat DNS rebinding.
     */
    public record Resolved(URI uri, InetAddress address) {
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
