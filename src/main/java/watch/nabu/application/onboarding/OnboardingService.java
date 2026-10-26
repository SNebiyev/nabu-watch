package watch.nabu.application.onboarding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.OrganizationMember;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.OrganizationRepository;
import watch.nabu.infrastructure.persistence.UserRepository;

/**
 * Handles first-time OAuth2 login provisioning.
 *
 * <p>When an OAuth2 provider redirects back to the app after the user
 * authenticates, this service is responsible for:
 * <ol>
 *   <li>Finding the matching {@link User} by email, or creating one if the
 *       user is logging in for the first time.</li>
 *   <li>Creating a personal organization for a brand-new user.</li>
 * </ol>
 *
 * <p>Both steps run in one call so that a first login never leaves a user
 * without an organization.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository orgMemberRepository;
    private final AdminSignupNotifier adminSignupNotifier;
    private final AdminBootstrapService adminBootstrapService;

    private static boolean isNoreplyEmail(String email) {
        return email != null && email.toLowerCase().endsWith("@users.noreply.github.com");
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Finds the existing {@link User} for this OAuth2 login or creates a new
     * one if this is the first time the provider has been seen.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>By {@code (oauthProvider, oauthProviderId)} — the canonical OAuth2
     *       identity.  This is the most reliable key because emails can change.</li>
     *   <li>By email — allows linking an existing email/password account to an
     *       OAuth2 provider on the first OAuth login.</li>
     *   <li>Create a new {@link User} if neither lookup hits.</li>
     * </ol>
     *
     * @param email      the email address supplied by the provider (must not be null)
     * @param fullName   the display name supplied by the provider
     * @param provider   lowercase provider key, e.g. {@code "google"}, {@code "github"}
     * @param providerId the provider's stable user identifier
     * @return the resolved or newly created {@link User}
     */
    @Transactional
    public User processOAuthLogin(String email, String fullName, String provider, String providerId) {
        User user = resolveOAuthUser(email, fullName, provider, providerId);
        // Promotion additionally requires an ACTIVE account.
        adminBootstrapService.promoteIfEligible(user);
        return user;
    }

    private User resolveOAuthUser(String email, String fullName, String provider, String providerId) {
        // ── 1. Look up by provider identity (most reliable key) ───────────────
        return userRepository.findByOauthProviderAndOauthProviderId(provider, providerId)
                .map(existing -> {
                    log.debug("[onboarding] Known OAuth user: userId={} provider={}", existing.getId(), provider);
                    // Opportunistic email migration: if the stored email is a GitHub noreply
                    // alias (created before the /user/emails API integration) and we now have
                    // the real verified address, update the DB — but only if no other account
                    // already owns the new address (prevents accidental account merges).
                    if (isNoreplyEmail(existing.getEmail()) && !isNoreplyEmail(email)) {
                        if (userRepository.findByEmail(email).isEmpty()) {
                            existing.setEmail(email.toLowerCase());
                            userRepository.save(existing);
                            log.info("[onboarding] Migrated noreply → real email: userId={} newEmail={}",
                                    existing.getId(), email);
                        } else {
                            log.warn("[onboarding] Cannot migrate noreply email — target address already taken: userId={} email={}",
                                    existing.getId(), email);
                        }
                    }
                    return existing;
                })
                // ── 2. Look up by email — link existing account to OAuth ──────
                .or(() -> userRepository.findByEmail(email).map(existing -> {
                    if (existing.getOauthProvider() == null) {
                        existing.setOauthProvider(provider);
                        existing.setOauthProviderId(providerId);
                        userRepository.save(existing);
                        log.info("[onboarding] Linked OAuth to existing account: userId={} email={} provider={}",
                                existing.getId(), email, provider);
                    }
                    return existing;
                }))
                // ── 3. First-ever login — create user + personal org ─
                .orElseGet(() -> createOAuthUser(email, fullName, provider, providerId));
    }

    private User createOAuthUser(String email, String fullName, String provider, String providerId) {
        User user = User.builder()
                .email(email)
                .fullName(fullName != null ? fullName : email)
                // OAuth users have no password; column is nullable
                .passwordHash(null)
                .oauthProvider(provider)
                .oauthProviderId(providerId)
                // Email is already verified by the OAuth provider
                .status(User.AccountStatus.ACTIVE)
                .build();

        user = userRepository.save(user);

        log.info("[onboarding] New OAuth user created: userId={} email={} provider={}",
                user.getId(), email, provider);

        // Create a personal organization for the new user.
        Organization org = organizationRepository.save(
                Organization.builder()
                        .name(user.getFullName() + "'s Workspace")
                        .build());
        orgMemberRepository.save(
                OrganizationMember.builder()
                        .organization(org)
                        .user(user)
                        .role(OrganizationMember.Role.OWNER)
                        .build());
        user.setCurrentOrganizationId(org.getId());
        userRepository.save(user);

        log.info("[onboarding] Personal org created: userId={} orgId={}", user.getId(), org.getId());

        // Best-effort admin notification — must not fail OAuth login.
        adminSignupNotifier.notifyOfSignup(user, "oauth:" + provider);

        return user;
    }
}
