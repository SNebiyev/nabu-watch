package watch.nabu.application.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregates the four other free tools (SSL, Impressum, Privacy Policy,
 * Cookie banner) into a single GDPR-readiness verdict.
 *
 * <p>Same constraints as the underlying inspectors: no AI, no proxy, no
 * headless browser. The pass/fail signal is intentionally coarse — a
 * conservative "looks OK" / "needs review" / "missing" verdict per check
 * plus a 0–100 composite score. The detailed audit lives in the signed-in
 * scanner pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GdprComposite {

    private final SslInspector ssl;
    private final LegalLinkInspector legalLink;
    private final CookieBannerInspector cookieBanner;
    private final HtmlFetcher htmlFetcher;

    public Result inspect(URI uri, InetAddress resolvedAddress) {
        Map<String, Check> checks = new LinkedHashMap<>();
        int score = 0;
        int total = 4;

        SslInspector.Result sslResult = ssl.inspect(uri, resolvedAddress);
        if (sslResult.success() && "ok".equals(sslResult.severity())) {
            checks.put("ssl", Check.pass("SSL/TLS valid (" + sslResult.daysRemaining() + " days remaining)"));
            score++;
        } else if (sslResult.success() && "warning".equals(sslResult.severity())) {
            checks.put("ssl", Check.warn("SSL expires in " + sslResult.daysRemaining() + " days"));
        } else {
            checks.put("ssl", Check.fail(sslResult.success()
                    ? "SSL critical (" + sslResult.daysRemaining() + " days)"
                    : "Could not verify SSL: " + sslResult.error()));
        }

        // Single homepage fetch shared across the three HTML-based checks
        // (impressum link / privacy-policy link / cookie-banner CMP) — avoids
        // hitting the target site three times for one composite scan.
        HtmlFetcher.Result fetched = htmlFetcher.fetch(uri, resolvedAddress);
        String fetchError = fetched.failureReason();
        String body = fetched.body();

        if (fetchError == null) {
            LegalLinkInspector.Result imp = legalLink.scanBody(uri, body, LegalLinkInspector.Persona.IMPRESSUM);
            if (imp.found()) {
                checks.put("impressum", Check.pass("Impressum link present"));
                score++;
            } else {
                checks.put("impressum", Check.fail("No Impressum / legal notice link"));
            }

            LegalLinkInspector.Result pp = legalLink.scanBody(uri, body, LegalLinkInspector.Persona.PRIVACY_POLICY);
            if (pp.found()) {
                checks.put("privacy", Check.pass("Privacy policy link present"));
                score++;
            } else {
                checks.put("privacy", Check.fail("No privacy policy link"));
            }

            CookieBannerInspector.Result cb = cookieBanner.scanBody(uri, body);
            if (cb.detected()) {
                checks.put("cookieBanner", Check.pass("Consent platform detected: " + String.join(", ", cb.platforms())));
                score++;
            } else {
                checks.put("cookieBanner", Check.warn("No known consent-management platform detected (custom banners aren't covered by this free check)"));
            }
        } else {
            checks.put("impressum", Check.fail("Could not fetch homepage: " + fetchError));
            checks.put("privacy", Check.fail("Could not fetch homepage: " + fetchError));
            checks.put("cookieBanner", Check.fail("Could not fetch homepage: " + fetchError));
        }

        int percent = Math.round(100f * score / total);
        String verdict = percent >= 75 ? "Likely compliant"
                : percent >= 50 ? "Partial — needs review"
                : "Multiple gaps";

        return new Result(true, uri.getHost(), percent, verdict, checks, null);
    }

    public record Result(
            boolean success,
            String host,
            int scorePercent,
            String verdict,
            Map<String, Check> checks,
            String error) {

        public static Result failed(String host, String error) {
            return new Result(false, host, 0, null, Map.of(), error);
        }
    }

    public record Check(String status, String message) {
        public static Check pass(String m) { return new Check("pass", m); }
        public static Check warn(String m) { return new Check("warn", m); }
        public static Check fail(String m) { return new Check("fail", m); }
    }
}
