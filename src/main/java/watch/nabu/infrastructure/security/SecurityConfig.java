package watch.nabu.infrastructure.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless JWT security configuration.
 *
 * <h3>Dispatcher-type handling</h3>
 * Spring Security 6 filters <em>all</em> dispatcher types by default
 * ({@code ASYNC}, {@code ERROR}, {@code FORWARD}, {@code REQUEST}, …).
 * Each type needs an explicit permit rule or it falls through to
 * {@code .anyRequest().authenticated()} and throws
 * {@code AuthorizationDeniedException}:
 * <ul>
 *   <li>{@code ASYNC}   — Spring MVC uses an ASYNC re-dispatch when a
 *       controller returns an {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}.
 *       The re-dispatch carries no {@code Authorization} header and must be
 *       allowed so SSE connections can remain open.</li>
 *   <li>{@code FORWARD} — Spring MVC internally forwards requests during view
 *       rendering and error handling; these must pass without re-authentication.</li>
 *   <li>{@code ERROR}   — Spring Boot error handling forwards to {@code /error}
 *       using an ERROR dispatch.  Blocking it produces a 403 loop instead of
 *       the real error response.</li>
 * </ul>
 *
 * <h3>Permit list</h3>
 * <ul>
 *   <li>{@code OPTIONS /**}                     — CORS pre-flight (no auth)</li>
 *   <li>{@code POST /api/v1/auth/login}          — login (no token required)</li>
 *   <li>{@code POST /api/v1/auth/change-password} — requires valid Bearer token</li>
 *   <li>{@code GET  /actuator/**}               — health / metrics probes</li>
 * </ul>
 *
 * <h3>Error responses</h3>
 * Both unauthenticated (401) and unauthorised (403) requests return a compact
 * RFC 7807–style JSON body instead of an HTML error page, so API clients can
 * always rely on JSON error payloads regardless of the {@code Accept} header.
 * <pre>
 * {"status":403,"error":"Forbidden","detail":"Access denied — admin role required"}
 * </pre>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final UserApiRateLimitFilter userApiRateLimitFilter;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final CustomOidcUserService customOidcUserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;

    @Value("${nabu.frontend-url}")
    private String frontendUrl;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontendUrl));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Delete-OTP", "X-MFA-Token", "X-Requested-With"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .contentTypeOptions(c -> {
                        })
                        .httpStrictTransportSecurity(h -> h
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .referrerPolicy(r -> r.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=()"))
                )
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth

                // ── Dispatcher-type permits (must come first) ─────────────────
                //
                // ASYNC: Spring MVC's internal re-dispatch for SseEmitter and
                //        @Async responses — no Authorization header present.
                // FORWARD: internal Spring MVC forwarding (view resolution, etc.)
                // ERROR: Spring Boot error handling forwards to /error via ERROR
                //        dispatch; blocking it causes a 403 loop instead of
                //        returning the real error payload.
                .dispatcherTypeMatchers(
                        DispatcherType.ASYNC,
                        DispatcherType.FORWARD,
                        DispatcherType.ERROR)
                    .permitAll()

                // ── CORS pre-flight ───────────────────────────────────────────
                // OPTIONS requests must not require authentication; the browser
                // sends them before the real request and cannot add a Bearer token.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // ── Public endpoints ──────────────────────────────────────────
                    .requestMatchers(HttpMethod.POST,
                            "/api/v1/auth/login",
                            "/api/v1/auth/register",
                            "/api/v1/auth/verify-email",
                            "/api/v1/auth/resend-verification",
                            "/api/v1/auth/forgot-password",
                            "/api/v1/auth/reset-password").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/actuator/**").hasRole("ADMIN")
                // /error is also reached via REQUEST dispatch from some clients
                .requestMatchers("/error").permitAll()
                    // One-click email unsubscribe — token-authenticated, no JWT needed
                    .requestMatchers("/api/v1/unsubscribe").permitAll()
                    // Public verified-user trust profile — backs the /u/{username} marketing pages
                    .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
                    // Third-party abuse intake — submitter typically has no account
                    .requestMatchers(HttpMethod.POST, "/api/v1/abuse/reports").permitAll()
                    // Public scan tools (SSL/cookie/etc.) — Turnstile + rate-limit guarded
                    .requestMatchers(HttpMethod.POST, "/api/v1/tools/**").permitAll()

                    // ── OAuth2 redirect endpoints ─────────────────────────────────
                    // /oauth2/authorization/** — browser redirect to provider consent screen
                    // /login/oauth2/code/**   — provider callback (receives auth code)
                    // Both carry no Bearer token and must not require authentication.
                    .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()

                    // ── WebSocket handshake ───────────────────────────────────────
                    // The initial HTTP upgrade and SockJS negotiation carry no Bearer
                    // token — actual auth is enforced by JwtChannelInterceptor on the
                    // STOMP CONNECT frame.
                    .requestMatchers("/ws-notifications/**").permitAll()

                    // ── Email-verification wall ──────────────────────────────────
                    // PENDING_VERIFICATION principals carry ROLE_USER but NOT the
                    // VERIFIED authority, so they may hit /api/v1/auth/** (to drive
                    // the "verify your email" screen) and are 403'd everywhere else.
                    .requestMatchers("/api/v1/auth/**").authenticated()
                    .requestMatchers("/api/v1/admin/**").access((supplier, ctx) -> {
                        var a = supplier.get().getAuthorities();
                        boolean ok = a.stream().anyMatch(ga -> "ROLE_ADMIN".equals(ga.getAuthority()))
                                && a.stream().anyMatch(ga -> NabuUserDetailsService.VERIFIED_AUTHORITY.equals(ga.getAuthority()));
                        return new org.springframework.security.authorization.AuthorizationDecision(ok);
                    })
                    .anyRequest().hasAuthority(NabuUserDetailsService.VERIFIED_AUTHORITY)
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(userApiRateLimitFilter, JwtAuthenticationFilter.class)
                // ── OAuth2 / OIDC login ───────────────────────────────────────────
                // Wires the authorization code flow for Google (OIDC) and GitHub (OAuth2).
                // On success the handler issues a Nabu JWT and redirects to the frontend.
                // The OAuth2 state parameter is stored in a short-lived HttpSession that
                // exists only during the provider round-trip; API endpoints remain stateless.
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                                .oidcUserService(customOidcUserService)
                        )
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler((req, res, ex) -> {
                            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            res.getWriter().write(
                                    "{\"status\":401,\"error\":\"OAuth2 Login Failed\",\"detail\":\"Authentication failed\"}");
                        })
                )
            .exceptionHandling(ex -> ex
                // 401 — no/invalid token (authentication layer)
                .authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.getWriter().write(
                        "{\"status\":401,\"error\":\"Unauthorized\",\"detail\":\"Authentication required\"}");
                })
                // 403 — valid token but insufficient role (authorisation layer)
                .accessDeniedHandler((req, res, e) -> {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.getWriter().write(
                        "{\"status\":403,\"error\":\"Forbidden\",\"detail\":\"Access denied — admin role required\"}");
                })
            );

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

}
