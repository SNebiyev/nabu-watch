package watch.nabu.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.io.IOException;

/**
 * Invoked by Spring Security after a successful OAuth2 / OIDC login.
 *
 * <p>Extracts the authenticated email from the principal, generates a Nabu JWT
 * (same format used for email/password logins), then redirects the browser to
 * the Next.js callback page with the token as a query parameter:
 * <pre>
 *   {frontendUrl}/auth/callback?token=&lt;jwt&gt;
 * </pre>
 *
 * <p>The frontend reads the token from the URL, stores it in localStorage /
 * a cookie, then removes it from the address bar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;

    @Value("${nabu.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        String email = extractEmail(authentication);

        UserDetails userDetails = userDetailsService.loadUserByUsername(email);
        var orgId = userRepository.findByEmail(email)
                .map(u -> u.getCurrentOrganizationId())
                .orElse(null);
        String jwt = jwtService.generateToken(userDetails, orgId);

        log.info("[oauth2] Login successful — issuing JWT: email={} orgId={}", email, orgId);

        String redirectUrl = UriComponentsBuilder.fromUriString(frontendUrl)
                .path("/auth/callback")
                .queryParam("token", jwt)
                .build()
                .toUriString();

        clearAuthenticationAttributes(request);
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String extractEmail(Authentication authentication) {
        Object principal = authentication.getPrincipal();

        if (principal instanceof OidcUser oidcUser) {
            return oidcUser.getEmail();
        }
        if (principal instanceof OAuth2User oAuth2User) {
            // NabuOAuth2User always injects the canonical resolved email under "email"
            // (real address, or noreply fallback when the GitHub API also fails).
            // This attribute is never null at this point.
            Object emailAttr = oAuth2User.getAttribute("email");
            if (emailAttr != null) return emailAttr.toString().toLowerCase();
        }

        // Standard UserDetails path (should not reach here in OAuth2 flow)
        return authentication.getName();
    }
}
