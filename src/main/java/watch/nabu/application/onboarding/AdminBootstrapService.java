package watch.nabu.application.onboarding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.util.Locale;

/**
 * First-admin bootstrap for fresh installs.
 *
 * <p>When {@code nabu.admin.bootstrap-email} (env {@code NABU_BOOTSTRAP_ADMIN_EMAIL}) is set,
 * the account with that email (case-insensitive) is promoted to {@link User.Role#ADMIN} as
 * soon as it is {@link User.AccountStatus#ACTIVE}, but only while no ADMIN exists at all.
 * If the only admin is later demoted, the next login or restart promotes the configured
 * account again. An account only becomes ACTIVE after the
 * email address was verified (verification link, or an OAuth login whose provider email is
 * verified), so owning the mailbox is required. An unverified account is never promoted.
 *
 * <p>Promotion is attempted when an email gets verified, after every OAuth login, and once at
 * startup in case the account already exists. With the property empty (the default) the
 * feature is disabled.
 */
@Slf4j
@Service
public class AdminBootstrapService {

    private final UserRepository userRepository;
    private final String bootstrapEmail;

    public AdminBootstrapService(
            UserRepository userRepository,
            @Value("${nabu.admin.bootstrap-email:}") String bootstrapEmail) {
        this.userRepository = userRepository;
        this.bootstrapEmail = bootstrapEmail == null ? "" : bootstrapEmail.trim().toLowerCase(Locale.ROOT);
    }

    /** True when a bootstrap email is configured. */
    public boolean isEnabled() {
        return !bootstrapEmail.isEmpty();
    }

    /**
     * Promotes {@code user} to ADMIN if it matches the configured email, is ACTIVE and no ADMIN exists yet.
     * The caller is responsible for the surrounding transaction; the user is saved here.
     *
     * @return {@code true} if the user was promoted by this call
     */
    public boolean promoteIfEligible(User user) {
        if (!isEnabled() || user == null || user.getEmail() == null) {
            return false;
        }
        if (!bootstrapEmail.equalsIgnoreCase(user.getEmail().trim())) {
            return false;
        }
        if (user.getStatus() != User.AccountStatus.ACTIVE || user.getRole() == User.Role.ADMIN) {
            return false;
        }
        if (userRepository.existsByRole(User.Role.ADMIN)) {
            // Promote only while no ADMIN exists. If the sole admin is demoted, the next login
            // or restart promotes the configured account again; with any admin present it stays put.
            return false;
        }
        user.setRole(User.Role.ADMIN);
        userRepository.save(user);
        log.info("[bootstrap] Promoted verified account to ADMIN: userId={} email={}", user.getId(), user.getEmail());
        return true;
    }

    /** Startup pass: promotes the bootstrap account if it already exists and is ACTIVE. */
    @EventListener(ApplicationReadyEvent.class)
    public void promoteAtStartup() {
        if (!isEnabled()) {
            return;
        }
        userRepository.findByEmail(bootstrapEmail).ifPresent(this::promoteIfEligible);
    }
}
