package watch.nabu.infrastructure.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import watch.nabu.application.onboarding.OnboardingService;
import watch.nabu.domain.model.User;

/**
 * OIDC user service for Google (and any future OpenID Connect provider).
 *
 * <p>Delegates token exchange and ID-token validation to {@link OidcUserService},
 * then finds or creates the Nabu {@link User} via {@link OnboardingService}.
 *
 * <p>Google's ID token guarantees a non-null {@code email} and {@code sub} claim,
 * so no email fallback is needed here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

    private final OnboardingService onboardingService;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);

        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        String providerId = oidcUser.getSubject();            // stable OIDC sub claim
        String email = oidcUser.getEmail();
        String fullName = oidcUser.getFullName();

        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_not_available"),
                    "No email claim in ID token from provider '" + registrationId + "'.");
        }

        if (!Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_not_verified"),
                    "Provider '" + registrationId + "' did not mark the email as verified.");
        }

        try {
            onboardingService.processOAuthLogin(email.toLowerCase(), fullName, registrationId, providerId);
        } catch (Exception ex) {
            log.error("[oidc] Failed to process OIDC login: provider={} error={}", registrationId, ex.getMessage(), ex);
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("user_provisioning_failed"),
                    "Failed to provision user account: " + ex.getMessage(), ex);
        }

        return oidcUser;
    }
}
