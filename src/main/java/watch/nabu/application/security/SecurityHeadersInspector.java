package watch.nabu.application.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watch.nabu.application.tools.HtmlFetcher;
import watch.nabu.application.tools.ToolUrlValidator;
import watch.nabu.infrastructure.web.UrlUtils;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Audits a website's response headers against the standard web-security set.
 * Sync, in-process, no AI / no proxy / no headless rendering — runs only when
 * the verified site owner triggers a manual scan.
 *
 * <p>The actual HTTP fetch is delegated to {@link HtmlFetcher}, which connects
 * by the validator-resolved IP and pins SNI to the original hostname. That
 * defeats the DNS-rebinding attack a verified-but-malicious owner could
 * otherwise mount by pointing their domain at an internal IP between the
 * verification flow and this scan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityHeadersInspector {

    private final HtmlFetcher fetcher;
    private final ToolUrlValidator urlValidator;

    private record HeaderCheck(String header, int weight, String label) { }

    private static final List<HeaderCheck> CHECKS = List.of(
            new HeaderCheck("strict-transport-security", 25, "HSTS"),
            new HeaderCheck("content-security-policy", 25, "Content-Security-Policy"),
            new HeaderCheck("x-content-type-options", 10, "X-Content-Type-Options"),
            new HeaderCheck("x-frame-options", 10, "X-Frame-Options / frame-ancestors"),
            new HeaderCheck("referrer-policy", 15, "Referrer-Policy"),
            new HeaderCheck("permissions-policy", 15, "Permissions-Policy")
    );

    public Result inspect(String websiteUrl) {
        ToolUrlValidator.Resolved resolved;
        try {
            resolved = urlValidator.validate(websiteUrl);
        } catch (RuntimeException e) {
            return Result.failed(UrlUtils.host(websiteUrl), "URL not scannable: " + e.getMessage());
        }

        URI uri = resolved.uri();
        HtmlFetcher.Result fetched = fetcher.fetch(uri, resolved.address());
        String reason = fetched.failureReason();
        if (reason != null) {
            return Result.failed(uri.getHost(), reason);
        }

        Map<String, Check> checks = new LinkedHashMap<>();
        int score = 0;
        for (HeaderCheck hc : CHECKS) {
            Check c = grade(hc, fetched.header(hc.header));
            checks.put(hc.label, c);
            score += c.earnedWeight;
        }

        String verdict = score >= 80 ? "Strong"
                : score >= 50 ? "Partial"
                : "Weak";
        return new Result(true, uri.getHost(), fetched.status(), score, verdict, checks, null);
    }

    private static Check grade(HeaderCheck hc, String value) {
        if (value == null || value.isBlank()) {
            return new Check("fail", "Missing", 0, hc.weight);
        }
        String v = value.toLowerCase(Locale.ROOT);

        switch (hc.header) {
            case "strict-transport-security" -> {
                long maxAge = parseHstsMaxAge(v);
                if (maxAge >= 15_552_000L) {
                    return new Check("pass", "max-age=" + maxAge, hc.weight, hc.weight);
                }
                if (maxAge > 0) {
                    return new Check("warn", "max-age=" + maxAge + " (recommend ≥6 months)", hc.weight / 2, hc.weight);
                }
                return new Check("fail", "Present but no usable max-age", 0, hc.weight);
            }
            case "content-security-policy" -> {
                if (v.contains("default-src") || v.contains("script-src")) {
                    return new Check("pass", "Non-trivial CSP", hc.weight, hc.weight);
                }
                return new Check("warn", "Permissive CSP", hc.weight / 2, hc.weight);
            }
            case "x-frame-options" -> {
                if (v.contains("deny") || v.contains("sameorigin")) {
                    return new Check("pass", value, hc.weight, hc.weight);
                }
                return new Check("warn", value, hc.weight / 2, hc.weight);
            }
            case "x-content-type-options" -> {
                if (v.contains("nosniff")) {
                    return new Check("pass", "nosniff", hc.weight, hc.weight);
                }
                return new Check("warn", value, hc.weight / 2, hc.weight);
            }
            default -> {
                return new Check("pass", "Present", hc.weight, hc.weight);
            }
        }
    }

    private static long parseHstsMaxAge(String headerValue) {
        for (String part : headerValue.split("[;\\s,]+")) {
            if (part.startsWith("max-age=")) {
                try {
                    return Long.parseLong(part.substring("max-age=".length()).trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    public record Result(
            boolean success,
            String host,
            int httpStatus,
            int score,
            String verdict,
            Map<String, Check> checks,
            String error) {

        public static Result failed(String host, String error) {
            return new Result(false, host, 0, 0, null, Map.of(), error);
        }
    }

    public record Check(String status, String message, int earnedWeight, int maxWeight) { }
}
