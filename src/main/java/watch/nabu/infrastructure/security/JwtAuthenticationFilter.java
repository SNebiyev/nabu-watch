package watch.nabu.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts every request, extracts a JWT, and populates the
 * {@link SecurityContextHolder}.
 *
 * <p>Token resolution order:
 * <ol>
 *   <li>{@code Authorization: Bearer &lt;token&gt;} header — standard flow.</li>
 *   <li>{@code ?token=&lt;token&gt;} query parameter — fallback for SSE clients
 *       ({@code EventSource}) which cannot set custom request headers.</li>
 * </ol>
 *
 * <p>Requests without a valid token are not rejected here — the security filter
 * chain's {@code authorizeHttpRequests} rules handle that downstream.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX   = "Bearer ";
    private static final String TOKEN_PARAM     = "token";
    /** Path that allows the query-param fallback (SSE endpoint). */
    private static final String SSE_PATH        = "/api/v1/scans/stream";

    private final JwtService            jwtService;
    private final NabuUserDetailsService userDetailsService;

    /** Short TTL so revocations propagate within seconds; bounded by user count. */
    private static final Duration USER_CACHE_TTL = Duration.ofSeconds(30);
    private final Map<String, CachedUserDetails> userCache = new ConcurrentHashMap<>();

    private record CachedUserDetails(UserDetails userDetails, Instant expiresAt) {}

    private UserDetails loadCached(String email) {
        Instant now = Instant.now();
        CachedUserDetails hit = userCache.get(email);
        if (hit != null && hit.expiresAt.isAfter(now)) {
            return hit.userDetails;
        }
        UserDetails ud = userDetailsService.loadUserByUsername(email);
        userCache.put(email, new CachedUserDetails(ud, now.plus(USER_CACHE_TTL)));
        // Opportunistic sweep: drop expired entries to bound memory.
        if (userCache.size() > 1024) {
            userCache.entrySet().removeIf(e -> e.getValue().expiresAt.isBefore(now));
        }
        return ud;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         chain) throws ServletException, IOException {

        String token = resolveToken(request);

        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            String email = jwtService.extractSubject(token);

            // Only authenticate if no auth is already set for this request
            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = loadCached(email);

                if (jwtService.isValid(token, userDetails)) {
                    var authToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    // Propagate orgId from JWT to request scope for downstream use
                    try {
                        var orgId = jwtService.extractOrganizationId(token);
                        if (orgId != null) {
                            request.setAttribute("orgId", orgId);
                        }
                    } catch (Exception ignored) {
                        // Legacy tokens without orgId claim — not an error
                    }
                }
            }
        } catch (Exception e) {
            // Malformed or expired token — let the request proceed unauthenticated;
            // the security rules will reject it if the route requires auth.
            log.debug("JWT processing failed for request {}: {}", request.getRequestURI(), e.getMessage());
        }

        chain.doFilter(request, response);
    }

    /**
     * Resolves a raw JWT string from the incoming request.
     *
     * <p>Checks the {@code Authorization} header first.  For the SSE endpoint only,
     * falls back to the {@code ?token} query parameter so that browser
     * {@code EventSource} clients (which cannot set custom headers) can authenticate.
     */
    private String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }

        // Query-param fallback — allowed only for the SSE stream endpoint.
        if (SSE_PATH.equals(request.getServletPath())) {
            String queryToken = request.getParameter(TOKEN_PARAM);
            if (queryToken != null && !queryToken.isBlank()) {
                log.debug("Authenticating SSE connection via query-param token");
                return queryToken;
            }
        }

        return null;
    }
}
