package watch.nabu.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import watch.nabu.application.exception.UserRateLimitException;
import watch.nabu.application.ratelimit.RateLimitPort;

import java.io.IOException;

/**
 * Caps any single authenticated principal at 600 requests / 60 s across the
 * REST surface (see {@link RateLimitPort#checkUserApiBudget}). Sits just after
 * {@link JwtAuthenticationFilter} so the principal email is already on the
 * SecurityContext when this runs.
 *
 * <p>Skipped paths:
 * <ul>
 *   <li>Anything outside {@code /api/v1/}</li>
 *   <li>{@code OPTIONS} preflights</li>
 *   <li>Webhook/public/tool endpoints that authenticate per-request via other means</li>
 *   <li>Anonymous requests — those flow through path-level {@code permitAll} or
 *       are 401'd by the security chain</li>
 * </ul>
 *
 * <p>On exceed, throws {@link UserRateLimitException} which
 * {@code GlobalExceptionHandler} maps to HTTP 429 with a {@code Retry-After: 60}
 * header — matching the existing rate-limit response shape.
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class UserApiRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitPort rateLimitPort;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) return true;
        return path.startsWith("/api/v1/tools/")
                || path.startsWith("/api/v1/public/")
                || path.startsWith("/api/v1/auth/login")
                || path.startsWith("/api/v1/auth/register")
                || path.startsWith("/api/v1/auth/forgot-password")
                || path.startsWith("/api/v1/auth/reset-password")
                || path.startsWith("/api/v1/unsubscribe")
                || path.startsWith("/api/v1/abuse/reports");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null
                && !"anonymousUser".equals(auth.getName())) {
            try {
                rateLimitPort.checkUserApiBudget(auth.getName());
            } catch (UserRateLimitException ex) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", "60");
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"status\":429,\"error\":\"Rate Limit Exceeded\","
                                + "\"detail\":\"Too many API requests — slow down.\","
                                + "\"errorCode\":\"RATE_LIMIT_EXCEEDED\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
