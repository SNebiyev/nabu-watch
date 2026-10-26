package watch.nabu.application.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic detector for legal/policy links on a homepage. Used by both
 * the Impressum checker and the Privacy-policy checker — they only differ
 * in the keyword set.
 *
 * <p>This is a deliberately shallow check: we look for a link in the homepage
 * markup whose visible text or href matches any keyword. We do <strong>not</strong>
 * follow the link or validate that the linked page is actually a real
 * Impressum / privacy policy. The point of the public tool is to give a
 * quick "missing or present" verdict; the full audit lives in the signed-in
 * scanner pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegalLinkInspector {

    private final HtmlFetcher fetcher;

    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([^<]{0,200})</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Only http(s) hrefs are returned to the caller. Anything else (javascript:,
     * data:, vbscript:, mailto:, …) is reported as "found but not renderable"
     * so the React frontend cannot be tricked into rendering it as a clickable
     * `<a href="…">` and executing attacker-supplied script.
     */
    private static final Set<String> RENDERABLE_SCHEMES = Set.of("http", "https");

    public Result inspect(URI uri, InetAddress resolvedAddress, Persona persona) {
        HtmlFetcher.Result fetched = fetcher.fetch(uri, resolvedAddress);
        String reason = fetched.failureReason();
        return reason != null
                ? Result.failed(uri.getHost(), reason)
                : scanBody(uri, fetched.body(), persona);
    }

    /**
     * Body-only entry point: lets {@link GdprComposite} re-use a single HTML
     * fetch across multiple keyword passes instead of fetching the homepage
     * once per check.
     */
    Result scanBody(URI uri, String body, Persona persona) {
        Matcher m = ANCHOR_PATTERN.matcher(body);
        while (m.find()) {
            String href = m.group(1);
            String text = m.group(2) == null ? "" : m.group(2).trim();
            String hrefLc = href.toLowerCase(Locale.ROOT);
            String textLc = text.toLowerCase(Locale.ROOT);
            for (String kw : persona.keywords) {
                String kwLc = kw.toLowerCase(Locale.ROOT);
                if (textLc.contains(kwLc) || hrefLc.contains(kwLc)) {
                    String safeHref = renderableHref(uri, href);
                    String message = safeHref != null
                            ? "Found"
                            : "Found (link uses a non-web scheme and is not shown)";
                    return new Result(true, uri.getHost(), persona.label, true,
                            message, safeHref, truncate(text, 120), null);
                }
            }
        }

        return new Result(true, uri.getHost(), persona.label, false,
                "No " + persona.label + " link found on the homepage", null, null, null);
    }

    /**
     * Resolves the href against the page base and returns it only if it uses
     * an http(s) scheme. Returns null for javascript:/data:/etc. and for any
     * value that cannot be parsed as a URI — the caller treats null as
     * "found but not renderable".
     */
    private static String renderableHref(URI base, String href) {
        if (href == null || href.isBlank()) return null;
        try {
            URI resolved = base.resolve(href);
            String scheme = resolved.getScheme();
            if (scheme == null) return null;
            if (!RENDERABLE_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) return null;
            return resolved.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public enum Persona {
        IMPRESSUM("Impressum / legal notice", List.of(
                "impressum", "legal-notice", "legal_notice", "legalnotice",
                "mentions-legales", "mentions_legales",
                "aviso-legal", "aviso_legal",
                "anbieterkennzeichnung")),
        PRIVACY_POLICY("Privacy policy", List.of(
                "privacy", "privacy-policy", "privacy_policy",
                "datenschutz", "datenschutzerklarung",
                "politique-de-confidentialite", "politica-de-privacidad",
                "privacybeleid", "polityka-prywatnosci"));

        final String label;
        final List<String> keywords;

        Persona(String label, List<String> keywords) {
            this.label = label;
            this.keywords = keywords;
        }
    }

    public record Result(
            boolean success,
            String host,
            String checkType,
            boolean found,
            String message,
            String linkUrl,
            String linkText,
            String error) {

        public static Result failed(String host, String error) {
            return new Result(false, host, null, false, null, null, null, error);
        }
    }
}
