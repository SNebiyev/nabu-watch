package watch.nabu.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import watch.nabu.application.onboarding.OnboardingService;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.web.RestTemplateConfig;

import java.util.Arrays;
import java.util.List;

/**
 * OAuth2 user service for non-OIDC providers (GitHub).
 *
 * <p>Delegates the actual token exchange and user-info fetch to
 * {@link DefaultOAuth2UserService}, then finds or creates the Nabu
 * {@link User} via {@link OnboardingService}.
 *
 * <h3>GitHub private-email handling</h3>
 * <p>GitHub's {@code /user} user-info endpoint returns {@code null} or a
 * {@code noreply.github.com} address when the user has set their email to
 * private.  In that case this service calls
 * {@code GET https://api.github.com/user/emails} with the OAuth2 access token
 * and picks the entry that is both {@code primary=true} and
 * {@code verified=true}.  Only a <em>verified</em> email is accepted — using
 * an unverified address as an account identity would allow account-hijacking.
 *
 * <h3>NabuOAuth2User wrapping</h3>
 * <p>The resolved email is injected back into the principal's attribute map via
 * {@link NabuOAuth2User} so that {@link OAuth2AuthenticationSuccessHandler} can
 * read it from {@code getAttribute("email")} without any extra lookup.
 */
@Slf4j
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private static final String GITHUB_EMAILS_URL = "https://api.github.com/user/emails";
    private static final String NOREPLY_DOMAIN = "@users.noreply.github.com";

    private final OnboardingService onboardingService;
    private final RestTemplate restTemplate;

    public CustomOAuth2UserService(OnboardingService onboardingService,
                                   @Qualifier(RestTemplateConfig.GITHUB_REST_TEMPLATE) RestTemplate restTemplate) {
        this.onboardingService = onboardingService;
        this.restTemplate = restTemplate;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        // Resolve the provider's canonical user-identifier key dynamically:
        //   Google  → "sub"  (always a String)
        //   GitHub  → "id"   (returned as Integer by the GitHub API)
        String userNameAttributeName = userRequest.getClientRegistration()
                .getProviderDetails()
                .getUserInfoEndpoint()
                .getUserNameAttributeName();

        Object rawId = oAuth2User.getAttribute(userNameAttributeName);
        if (rawId == null) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("provider_id_missing"),
                    "Provider did not return a user identifier for attribute '"
                            + userNameAttributeName + "' (provider: " + registrationId + ")");
        }
        // String.valueOf handles Integer/Long/String uniformly — no ClassCastException.
        String providerId = String.valueOf(rawId);

        String resolvedEmail = resolveEmail(userRequest, oAuth2User, registrationId, providerId);
        String name = resolveName(oAuth2User);

        try {
            onboardingService.processOAuthLogin(resolvedEmail, name, registrationId, providerId);
        } catch (Exception ex) {
            log.error("[oauth2] Failed to process OAuth login: provider={} error={}",
                    registrationId, ex.getMessage(), ex);
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("user_provisioning_failed"),
                    "Failed to provision user account: " + ex.getMessage(), ex);
        }

        // Wrap the principal so every downstream component (success handler, etc.)
        // reads the canonical email from getAttribute("email") without re-deriving it.
        return new NabuOAuth2User(oAuth2User, resolvedEmail);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the canonical email address for this OAuth2 login.
     *
     * <p>For GitHub:
     * <ol>
     *   <li>Use the profile {@code email} attribute only if it is not a noreply address and
     *       {@code GET /user/emails} lists it with {@code verified=true}.</li>
     *   <li>Otherwise use the entry that is {@code primary=true} <em>and</em> {@code verified=true}.</li>
     *   <li>Last resort: synthesise the canonical noreply address so the user still
     *       gets a unique, deterministic identity in our DB (logged as a warning).</li>
     * </ol>
     * Downstream code therefore only ever sees a verified email or a noreply address.
     *
     * <p>For all other providers the {@code email} attribute is used directly; an
     * exception is thrown when it is absent.
     */
    String resolveEmail(OAuth2UserRequest userRequest,
                        OAuth2User oAuth2User,
                        String registrationId,
                        String providerId) {
        String rawEmail = oAuth2User.getAttribute("email");

        if ("github".equals(registrationId)) {
            String accessToken = userRequest.getAccessToken().getTokenValue();
            GitHubEmailApiResponse[] entries = fetchGitHubEmails(accessToken);

            // Case 1: the profile email, trusted only when /user/emails lists it as verified.
            if (rawEmail != null && !rawEmail.isBlank()
                    && !rawEmail.toLowerCase().endsWith(NOREPLY_DOMAIN)) {
                String lower = rawEmail.toLowerCase();
                boolean verified = entries != null && Arrays.stream(entries)
                        .anyMatch(e -> e.verified() && e.email() != null && e.email().equalsIgnoreCase(lower));
                if (verified) {
                    return lower;
                }
                log.warn("[oauth2] GitHub profile email is not verified in /user/emails - ignoring it");
            }

            // Case 2: use the verified primary from /user/emails
            String apiEmail = pickVerifiedPrimary(entries);
            if (apiEmail != null) {
                log.info("[oauth2] Resolved real GitHub email via API: {}", apiEmail);
                return apiEmail;
            }

            // Case 3: API failed or returned nothing - fall back to the noreply address.
            // This is deterministic and unique, so the account remains usable, but the
            // user will never see it in the UI (fullName is displayed instead).
            String login = oAuth2User.getAttribute("login");
            String synth = providerId + "+" + login + NOREPLY_DOMAIN;
            log.warn("[oauth2] GitHub email API returned no usable address - noreply fallback: {}", synth);
            return synth;
        }

        // Non-GitHub providers (e.g. fallback non-OIDC flow)
        if (rawEmail != null && !rawEmail.isBlank()) {
            return rawEmail.toLowerCase();
        }

        throw new OAuth2AuthenticationException(
                new OAuth2Error("email_not_available"),
                "No email address returned by provider '" + registrationId + "'. " +
                        "Please make your email public in your provider settings.");
    }

    /**
     * Calls {@code GET https://api.github.com/user/emails}.
     *
     * @param accessToken the OAuth2 bearer token issued for this login
     * @return the entries, or {@code null} on any error or empty response
     */
    private GitHubEmailApiResponse[] fetchGitHubEmails(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<GitHubEmailApiResponse[]> response = restTemplate.exchange(
                    GITHUB_EMAILS_URL,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    GitHubEmailApiResponse[].class);

            GitHubEmailApiResponse[] entries = response.getBody();
            if (entries == null || entries.length == 0) {
                log.warn("[oauth2] GitHub /user/emails returned an empty list");
                return null;
            }
            return entries;
        } catch (Exception ex) {
            log.warn("[oauth2] GitHub /user/emails API call failed - {}: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    /**
     * Returns the first entry that is {@code primary=true} AND {@code verified=true}.
     * Only verified emails are accepted: an unverified email could belong to another person,
     * so accepting it would allow account hijacking.
     */
    private String pickVerifiedPrimary(GitHubEmailApiResponse[] entries) {
        if (entries == null) {
            return null;
        }
        return Arrays.stream(entries)
                .filter(e -> e.primary() && e.verified() && e.email() != null)
                .map(e -> e.email().toLowerCase())
                .findFirst()
                .orElseGet(() -> {
                    log.warn("[oauth2] GitHub /user/emails had no primary+verified entry");
                    return null;
                });
    }

    private String resolveName(OAuth2User user) {
        String name = user.getAttribute("name");
        if (name != null && !name.isBlank()) return name;
        String login = user.getAttribute("login"); // GitHub username fallback
        return login != null ? login : "Unknown";
    }
}
