package watch.nabu.infrastructure.web;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Utility for sanitising website URLs before they are persisted.
 *
 * <p>All methods are pure (no side effects) and null-safe.
 */
public final class UrlUtils {

    private UrlUtils() {}

    /**
     * Normalises a raw URL string supplied by the user:
     * <ol>
     *   <li>Strips surrounding whitespace.</li>
     *   <li>Removes every trailing {@code /}.</li>
     *   <li>Prepends {@code https://} when no protocol is present.</li>
     *   <li>Lowercases the host component (domains are case-insensitive per RFC 4343).</li>
     * </ol>
     *
     * @param raw the URL as submitted by the client; may be {@code null}
     * @return the normalised URL, or {@code null} if the input was {@code null}
     */
    public static String normalize(String raw) {
        if (raw == null) return null;

        String url = raw.strip();

        // Remove all trailing slashes
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        url = url.substring(0, end);

        // Ensure a protocol prefix is present
        if (!url.isEmpty()
                && !url.startsWith("http://")
                && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        // Lowercase the host and strip the "www." prefix (www.example.com == example.com)
        try {
            URI uri = new URI(url);
            if (uri.getHost() != null) {
                String host = uri.getHost().toLowerCase();
                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }
                if (!host.equals(uri.getHost())) {
                    uri = new URI(
                            uri.getScheme(),
                            uri.getUserInfo(),
                            host,
                            uri.getPort(),
                            uri.getPath(),
                            uri.getQuery(),
                            uri.getFragment()
                    );
                    url = uri.toString();
                }
            }
        } catch (URISyntaxException ignored) {
            // If URI parsing fails, return the URL as-is; validation happens at a higher layer
        }

        return url;
    }

    /**
     * Extracts the lowercased host from a URL, stripping a leading {@code www.}.
     * Returns {@code null} if the URL is null, blank, malformed, or has no host.
     * Shared by services that key rate limits, verification records, and other
     * per-domain state — keep this the single source of truth.
     */
    public static String host(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String h = new URI(url).getHost();
            if (h == null) return null;
            h = h.toLowerCase();
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (URISyntaxException e) {
            return null;
        }
    }

}
