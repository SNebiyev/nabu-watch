package watch.nabu.application.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watch.nabu.application.tools.ToolUrlValidator;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Probes a short list of well-known service ports on the verified site's
 * public IP and reports any that accept TCP connections within the
 * timeout. Sync, in-process, manual-only, verified-sites-only — port scanning is
 * slow enough and sensitive enough (even against your own infra) that
 * we gate it behind domain verification and an explicit Run scan.
 *
 * <p>Only these ports are probed, all associated with services that
 * should generally not be exposed on a public web host:
 * <pre>
 *   21 FTP   22 SSH   3306 MySQL   5432 PostgreSQL
 *   6379 Redis   8080 alt HTTP    9000 PHP-FPM / Portainer
 * </pre>
 * 80 and 443 are deliberately excluded: they are expected to be open.
 *
 * <p>The probe sees whatever the hostname resolves to. Behind a CDN that is the CDN's edge,
 * not the origin server, so the origin's ports cannot be tested. Cloudflare is recognised
 * ({@link CloudflareRanges}): ports its edge serves for every proxied site (8080 here) are
 * ignored and the result carries a note. Other open ports are still reported. Other CDNs
 * are not recognised yet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenPortsInspector {

    private static final int CONNECT_TIMEOUT_MS = 1_500;
    private static final List<PortProbe> PORTS = List.of(
            new PortProbe(21,   "FTP",        "file transfer — legacy plaintext"),
            new PortProbe(22,   "SSH",        "remote shell"),
            new PortProbe(3306, "MySQL",      "database"),
            new PortProbe(5432, "PostgreSQL", "database"),
            new PortProbe(6379, "Redis",      "cache / KV store"),
            new PortProbe(8080, "HTTP-alt",   "alternate HTTP — admin consoles"),
            new PortProbe(9000, "PHP-FPM",    "FastCGI / admin UI")
    );

    static final String CDN_NOTE = "Behind Cloudflare: these probes reach Cloudflare's edge, not your server. "
            + "Check the origin firewall directly.";

    private final ToolUrlValidator urlValidator;

    public Result inspect(String websiteUrl) {
        ToolUrlValidator.Resolved resolved;
        try {
            resolved = urlValidator.validate(websiteUrl);
        } catch (RuntimeException e) {
            return Result.failed(null, "URL not scannable: " + e.getMessage());
        }
        InetAddress address = resolved.address();
        String host = resolved.uri().getHost();

        boolean cloudflare = CloudflareRanges.contains(address);
        List<Finding> findings = probeInParallel(address).stream()
                .filter(f -> !cloudflare || !CloudflareRanges.EDGE_PORTS.contains(f.port()))
                .toList();
        int score = findings.isEmpty() ? 100 : Math.max(0, 100 - findings.size() * 20);
        String verdict = findings.isEmpty()
                ? "No unexpected ports open"
                : findings.size() + " unexpected port" + (findings.size() == 1 ? "" : "s") + " open";
        if (!cloudflare) {
            return new Result(true, host, score, verdict, findings, null, null, null);
        }
        if (findings.isEmpty()) verdict = "Origin ports not tested (behind Cloudflare)";
        return new Result(true, host, score, verdict, findings, null, CloudflareRanges.NAME, CDN_NOTE);
    }

    private List<Finding> probeInParallel(InetAddress address) {
        var pool = Executors.newFixedThreadPool(PORTS.size());
        try {
            List<CompletableFuture<Finding>> tasks = PORTS.stream()
                    .map(p -> CompletableFuture.supplyAsync(
                            () -> isOpen(address, p.port)
                                    ? new Finding(p.port, p.service, p.description)
                                    : null,
                            pool))
                    .toList();
            try {
                CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                        .orTimeout(CONNECT_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS)
                        .join();
            } catch (Exception ignored) { }

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

    // Package-private so tests can replace the network probe.
    boolean isOpen(InetAddress address, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private record PortProbe(int port, String service, String description) { }

    public record Finding(int port, String service, String description) { }

    public record Result(
            boolean success,
            String host,
            int score,
            String verdict,
            List<Finding> findings,
            String error,
            String cdn,
            String note) {

        public static Result failed(String host, String error) {
            return new Result(false, host, 0, null, List.of(), error, null, null);
        }
    }
}
