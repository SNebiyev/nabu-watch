package watch.nabu.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shared HTTP-request helpers. Kept thin on purpose — anything richer (auth
 * resolution, MDC propagation) belongs in dedicated services.
 */
public final class HttpRequestUtils {

    private HttpRequestUtils() {
    }

    /**
     * Honours X-Forwarded-For / X-Real-IP when present — the API runs behind
     * Caddy in production which populates these headers. Falls back to the
     * direct remote address for local dev. Returns {@code null} when the
     * request itself is {@code null} (background callers).
     */
    public static String clientIp(HttpServletRequest req) {
        if (req == null) return null;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String xri = req.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri.trim();
        return req.getRemoteAddr();
    }
}
