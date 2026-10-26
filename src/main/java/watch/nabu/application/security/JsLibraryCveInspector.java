package watch.nabu.application.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watch.nabu.application.tools.HtmlFetcher;
import watch.nabu.application.tools.ToolUrlValidator;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans the verified site's homepage for third-party JavaScript
 * libraries with known CVEs. Sync, in-process, no headless — we parse
 * the statically-served HTML and match {@code <script src=...>} URLs
 * against a curated in-repo signature list.
 *
 * <p>The DB is intentionally small and hand-picked: it covers the
 * libraries we actually see on customer sites (jQuery, Bootstrap,
 * AngularJS, Moment, Lodash, Handlebars). A larger Retire.js-backed DB
 * is planned post-launch; for v1 the curated set keeps false positives
 * to zero and ships without an external dependency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JsLibraryCveInspector {

    private final ToolUrlValidator urlValidator;
    private final HtmlFetcher fetcher;

    private record Signature(String library, Pattern urlPattern) { }

    private record Vulnerability(String versionPredicate, String cve, String severity, String summary) {
        boolean matches(String version) {
            return VersionRange.test(versionPredicate, version);
        }
    }

    private record LibraryEntry(Signature signature, List<Vulnerability> vulns) { }

    /**
     * Curated subset of Retire.js signatures. Each URL regex captures
     * a version via the named group {@code <v>}; the vulnerability
     * predicates use a tiny semver DSL parsed by {@link VersionRange}.
     */
    private static final List<LibraryEntry> DB = List.of(
            new LibraryEntry(
                    new Signature("jQuery",
                            Pattern.compile("(?i)(?:^|/)jquery[-.](?<v>\\d+\\.\\d+(?:\\.\\d+)?)(?:[.-]min)?\\.js")),
                    List.of(
                            new Vulnerability("<3.0.0", "CVE-2020-11023",
                                    "MEDIUM", "Untrusted HTML can execute script via jQuery.htmlPrefilter"),
                            new Vulnerability("<3.5.0", "CVE-2020-11022",
                                    "MEDIUM", "Untrusted HTML from .html()/.append() can execute script"),
                            new Vulnerability("<1.9.0", "CVE-2012-6708",
                                    "MEDIUM", "jQuery parseHTML / $() treats selector-like input as HTML"))),
            new LibraryEntry(
                    new Signature("jQuery UI",
                            Pattern.compile("(?i)(?:^|/)jquery-ui[-.](?<v>\\d+\\.\\d+(?:\\.\\d+)?)(?:[.-]min)?\\.js")),
                    List.of(
                            new Vulnerability("<1.13.0", "CVE-2021-41184",
                                    "MEDIUM", "XSS via \"of\" option of the .position() utility"))),
            new LibraryEntry(
                    new Signature("Bootstrap",
                            Pattern.compile("(?i)(?:^|/)bootstrap[-.](?<v>\\d+\\.\\d+\\.\\d+)(?:[.-]min)?\\.js")),
                    List.of(
                            new Vulnerability("<4.3.1", "CVE-2019-8331",
                                    "MEDIUM", "XSS in tooltip / popover data-template"),
                            new Vulnerability("<3.4.1", "CVE-2018-14041",
                                    "MEDIUM", "XSS in data-target / data-template attributes"))),
            new LibraryEntry(
                    new Signature("AngularJS",
                            Pattern.compile("(?i)(?:^|/)angular[-.](?<v>\\d+\\.\\d+\\.\\d+)(?:[.-]min)?\\.js")),
                    List.of(
                            new Vulnerability("<1.8.0", "CVE-2020-7676",
                                    "HIGH", "XSS via .merge() prototype pollution"),
                            new Vulnerability("<1.6.3", "CVE-2017-7521",
                                    "MEDIUM", "Sandbox bypass in expression evaluator"))),
            new LibraryEntry(
                    new Signature("Moment.js",
                            Pattern.compile("(?i)(?:^|/)moment[-.](?<v>\\d+\\.\\d+\\.\\d+)(?:[.-]min)?\\.js")),
                    List.of(
                            new Vulnerability("<2.29.4", "CVE-2022-31129",
                                    "HIGH", "Denial-of-service via maliciously crafted date strings"))),
            new LibraryEntry(
                    new Signature("Lodash",
                            Pattern.compile("(?i)(?:^|/)lodash[-.](?<v>\\d+\\.\\d+\\.\\d+)(?:[.-]min)?\\.js")),
                    List.of(
                            new Vulnerability("<4.17.21", "CVE-2021-23337",
                                    "HIGH", "Command injection via template compilation"),
                            new Vulnerability("<4.17.12", "CVE-2019-10744",
                                    "HIGH", "Prototype pollution via defaultsDeep()"))),
            new LibraryEntry(
                    new Signature("Handlebars",
                            Pattern.compile("(?i)(?:^|/)handlebars[-.](?<v>\\d+\\.\\d+\\.\\d+)(?:[.-]min)?\\.js")),
                    List.of(
                            new Vulnerability("<4.7.7", "CVE-2021-23369",
                                    "HIGH", "Arbitrary code execution via compile()")))
    );

    private static final Pattern SCRIPT_SRC =
            Pattern.compile("(?i)<script[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']");

    public Result inspect(String websiteUrl) {
        ToolUrlValidator.Resolved resolved;
        try {
            resolved = urlValidator.validate(websiteUrl);
        } catch (RuntimeException e) {
            return Result.failed(null, "URL not scannable: " + e.getMessage());
        }

        URI uri = resolved.uri();
        HtmlFetcher.Result page = fetcher.fetch(uri, resolved.address());
        String failure = page.failureReason();
        if (failure != null) {
            return Result.failed(uri.getHost(), failure);
        }

        Set<String> scripts = extractScriptUrls(page.body());
        List<Finding> findings = new ArrayList<>();
        Set<String> dedupeKey = new LinkedHashSet<>();
        for (String src : scripts) {
            for (LibraryEntry entry : DB) {
                Matcher m = entry.signature.urlPattern.matcher(src);
                if (!m.find()) continue;
                String version = m.group("v");
                for (Vulnerability v : entry.vulns) {
                    if (!v.matches(version)) continue;
                    String key = entry.signature.library + "|" + version + "|" + v.cve;
                    if (dedupeKey.add(key)) {
                        findings.add(new Finding(entry.signature.library, version,
                                v.cve, v.severity, v.summary));
                    }
                }
            }
        }

        int score = scoreFor(findings);
        String verdict = findings.isEmpty()
                ? "No vulnerable JS libraries detected"
                : findings.size() + " vulnerable librar" + (findings.size() == 1 ? "y" : "ies");
        return new Result(true, uri.getHost(), score, verdict, scripts.size(), findings, null);
    }

    static Set<String> extractScriptUrls(String html) {
        Set<String> out = new LinkedHashSet<>();
        if (html == null || html.isBlank()) return out;
        Matcher m = SCRIPT_SRC.matcher(html);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    private static int scoreFor(List<Finding> findings) {
        int score = 100;
        for (Finding f : findings) {
            score -= switch (f.severity.toUpperCase(Locale.ROOT)) {
                case "HIGH"   -> 30;
                case "MEDIUM" -> 15;
                default       -> 5;
            };
        }
        return Math.max(0, score);
    }

    public record Finding(String library, String version, String cve, String severity, String summary) { }

    public record Result(
            boolean success,
            String host,
            int score,
            String verdict,
            int scriptsScanned,
            List<Finding> findings,
            String error) {

        public static Result failed(String host, String error) {
            return new Result(false, host, 0, null, 0, List.of(), error);
        }
    }

    /**
     * Tiny predicate DSL — parses {@code "<3.5.0"}, {@code "<=4.17.21"} etc.
     * Full semver ranges (caret, tilde, OR) are unnecessary: every Retire.js
     * signature we care about is "affected below version X".
     */
    static final class VersionRange {
        static boolean test(String predicate, String version) {
            if (predicate == null || version == null) return false;
            String p = predicate.trim();
            String op;
            String target;
            if (p.startsWith("<=")) { op = "<="; target = p.substring(2).trim(); }
            else if (p.startsWith("<")) { op = "<"; target = p.substring(1).trim(); }
            else if (p.startsWith(">=")) { op = ">="; target = p.substring(2).trim(); }
            else if (p.startsWith(">")) { op = ">"; target = p.substring(1).trim(); }
            else { op = "="; target = p; }

            int cmp = compare(version, target);
            return switch (op) {
                case "<"  -> cmp < 0;
                case "<=" -> cmp <= 0;
                case ">"  -> cmp > 0;
                case ">=" -> cmp >= 0;
                default   -> cmp == 0;
            };
        }

        private static int compare(String a, String b) {
            String[] pa = a.split("\\.");
            String[] pb = b.split("\\.");
            int n = Math.max(pa.length, pb.length);
            for (int i = 0; i < n; i++) {
                int x = i < pa.length ? safe(pa[i]) : 0;
                int y = i < pb.length ? safe(pb[i]) : 0;
                if (x != y) return Integer.compare(x, y);
            }
            return 0;
        }

        private static int safe(String s) {
            try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
            catch (NumberFormatException e) { return 0; }
        }
    }
}
