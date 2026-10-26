package watch.nabu.application.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Detects whether a homepage embeds a known consent-management platform.
 *
 * <p>This is a deliberately shallow check — we only look for marker strings
 * (script srcs, container element IDs, well-known global JS variables) of
 * popular CMPs in the raw HTML. We do <strong>not</strong> render the page
 * with a headless browser and we do <strong>not</strong> use AI: those run in
 * the full scan after the website is added. Custom or in-house banners that don't ship one of the
 * fingerprints below will be reported as "not detected" — the result panel
 * makes that limitation explicit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CookieBannerInspector {

    private final HtmlFetcher fetcher;

    private record Cmp(String name, List<String> markers) { }

    private static final List<Cmp> KNOWN_CMPS = List.of(
            new Cmp("OneTrust", List.of("cdn.cookielaw.org", "onetrust-banner-sdk", "OneTrust", "optanon")),
            new Cmp("Cookiebot",  List.of("consent.cookiebot.com", "CybotCookiebotDialog")),
            new Cmp("Usercentrics", List.of("app.usercentrics.eu", "usercentrics-root", "uc-banner")),
            new Cmp("Iubenda", List.of("cdn.iubenda.com", "iubenda-cs-banner")),
            new Cmp("TrustArc", List.of("consent.trustarc.com", "truste-consent-track")),
            new Cmp("Quantcast Choice", List.of("quantcast.mgr.consensu.org", "qc-cmp2-container")),
            new Cmp("Didomi", List.of("sdk.privacy-center.org", "didomi-host")),
            new Cmp("Klaro", List.of("kiprotect.com/klaro", "klaro-cookie-notice")),
            new Cmp("CookieYes", List.of("cookie-law-info", "cookie-yes")),
            new Cmp("Termly", List.of("app.termly.io", "termly-code-snippet-support")),
            new Cmp("Osano", List.of("osano.com/uhf", "osano-cm-window")),
            new Cmp("Borlabs", List.of("borlabs-cookie", "BorlabsCookie"))
    );

    public Result inspect(URI uri, InetAddress resolvedAddress) {
        HtmlFetcher.Result fetched = fetcher.fetch(uri, resolvedAddress);
        String reason = fetched.failureReason();
        return reason != null
                ? Result.failed(uri.getHost(), reason)
                : scanBody(uri, fetched.body());
    }

    /**
     * Body-only entry: lets {@link GdprComposite} reuse a single HTML fetch
     * across multiple CMP keyword passes instead of re-fetching the homepage.
     */
    Result scanBody(URI uri, String rawBody) {
        String body = rawBody.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (Cmp cmp : KNOWN_CMPS) {
            for (String marker : cmp.markers) {
                if (body.contains(marker.toLowerCase(Locale.ROOT))) {
                    matched.add(cmp.name);
                    break;
                }
            }
        }

        String hint = matched.isEmpty()
                ? "If this is a single-page app (React/Vue/Angular), the consent platform "
                  + "may load via JavaScript and won't show up in this static check. Add "
                  + "the website to run the full headless-browser scan."
                : null;
        return new Result(true, uri.getHost(), !matched.isEmpty(), matched, hint, null);
    }

    public record Result(
            boolean success,
            String host,
            boolean detected,
            List<String> platforms,
            String hint,
            String error) {

        public static Result failed(String host, String error) {
            return new Result(false, host, false, List.of(), null, error);
        }
    }
}
