package watch.nabu.application.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watch.nabu.application.tools.HtmlFetcher;
import watch.nabu.application.tools.ToolUrlValidator;
import watch.nabu.infrastructure.web.UrlUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Probes a verified website for commonly-exposed secret/backup paths
 * (.env, .git/config, backup archives, phpinfo, SSH keys, etc.). Runs
 * sync, in-process, same verified-only manual-scan policy as the
 * security-headers audit. Connects via the validator-resolved IP to
 * defeat DNS rebinding.
 *
 * <p>A path is flagged only when the server returns HTTP 200 AND either
 * the response carries a non-HTML content-type OR the body matches the
 * probe's fingerprint — this suppresses the common SPA / CMS
 * catch-all-HTML false positive.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExposedFilesInspector {

    private static final int PARALLELISM = 6;
    private static final int OVERALL_BUDGET_MS = 30_000;

    private final HtmlFetcher fetcher;
    private final ToolUrlValidator urlValidator;

    private record Probe(String path, String label, String fingerprint) { }

    private static final List<Probe> PROBES = List.of(
            new Probe("/.env",                  "Environment file (.env)",        null),
            new Probe("/.git/config",           ".git/config",                    "[core]"),
            new Probe("/.git/HEAD",             ".git/HEAD",                      "ref:"),
            new Probe("/.DS_Store",             ".DS_Store",                      null),
            new Probe("/wp-config.php.bak",     "wp-config.php.bak",              "DB_PASSWORD"),
            new Probe("/wp-config.php~",        "wp-config.php~",                 "DB_PASSWORD"),
            new Probe("/config.php.bak",        "config.php.bak",                 null),
            new Probe("/web.config.bak",        "web.config.bak",                 null),
            new Probe("/phpinfo.php",           "phpinfo()",                      "PHP Version"),
            new Probe("/.htaccess",             ".htaccess",                      null),
            new Probe("/.htpasswd",             ".htpasswd",                      null),
            new Probe("/backup.zip",            "backup.zip",                     null),
            new Probe("/backup.sql",            "backup.sql",                     null),
            new Probe("/backup.tar.gz",         "backup.tar.gz",                  null),
            new Probe("/dump.sql",              "dump.sql",                       null),
            new Probe("/docker-compose.yml",    "docker-compose.yml",             null),
            new Probe("/Dockerfile",            "Dockerfile",                     null),
            new Probe("/.npmrc",                ".npmrc",                         null),
            new Probe("/id_rsa",                "id_rsa (SSH private key)",       "PRIVATE KEY"),
            new Probe("/.vscode/sftp.json",     ".vscode/sftp.json",              null),
            new Probe("/server-status",         "Apache server-status",           "Apache Server Status")
    );

    public Result inspect(String websiteUrl) {
        ToolUrlValidator.Resolved resolved;
        try {
            resolved = urlValidator.validate(websiteUrl);
        } catch (RuntimeException e) {
            return Result.failed(UrlUtils.host(websiteUrl), "URL not scannable: " + e.getMessage());
        }

        URI base = resolved.uri();
        String scheme = base.getScheme();
        String host = base.getHost();
        int port = base.getPort();

        var pool = Executors.newFixedThreadPool(PARALLELISM);
        List<Finding> findings;
        try {
            List<CompletableFuture<Finding>> tasks = PROBES.stream()
                    .map(probe -> CompletableFuture.supplyAsync(
                            () -> probeOne(probe, scheme, host, port, resolved.address()), pool))
                    .toList();
            try {
                CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                        .orTimeout(OVERALL_BUDGET_MS, TimeUnit.MILLISECONDS)
                        .join();
            } catch (Exception ignored) { }
            findings = new ArrayList<>();
            for (CompletableFuture<Finding> t : tasks) {
                Finding f = t.getNow(null);
                if (f != null) findings.add(f);
            }
        } finally {
            pool.shutdownNow();
        }

        int score = findings.isEmpty() ? 100 : Math.max(0, 100 - findings.size() * 25);
        String verdict = findings.isEmpty()
                ? "No exposed paths"
                : findings.size() == 1
                    ? "1 exposed path"
                    : findings.size() + " exposed paths";
        return new Result(true, host, score, verdict, findings, null);
    }

    private Finding probeOne(Probe probe, String scheme, String host, int port, java.net.InetAddress address) {
        URI uri;
        try {
            uri = new URI(scheme, null, host, port, probe.path, null, null);
        } catch (URISyntaxException e) {
            return null;
        }
        HtmlFetcher.Result r = fetcher.fetch(uri, address);
        if (!r.success() || r.status() != 200) return null;

        String body = r.body() == null ? "" : r.body();
        String contentType = r.header("content-type");
        boolean htmlResponse = contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("text/html");

        if (probe.fingerprint != null) {
            if (!body.contains(probe.fingerprint)) return null;
        } else if (htmlResponse) {
            // No fingerprint + server returned HTML → SPA / CMS catch-all,
            // not the actual file.
            return null;
        }
        return new Finding(probe.path, probe.label);
    }

    public record Result(
            boolean success,
            String host,
            int score,
            String verdict,
            List<Finding> findings,
            String error) {

        public static Result failed(String host, String error) {
            return new Result(false, host, 0, null, List.of(), error);
        }
    }

    public record Finding(String path, String label) { }
}
