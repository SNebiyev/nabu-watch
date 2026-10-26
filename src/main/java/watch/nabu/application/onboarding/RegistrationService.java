package watch.nabu.application.onboarding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.messaging.EmailTemplateService;
import watch.nabu.application.messaging.Message;
import watch.nabu.application.messaging.MessageDispatcher;
import watch.nabu.domain.model.*;
import watch.nabu.infrastructure.persistence.*;
import watch.nabu.infrastructure.redis.EmailVerificationTokenService;
import watch.nabu.infrastructure.redis.PasswordResetTokenService;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationTokenService verificationTokenService;
    private final PasswordResetTokenService resetTokenService;
    private final EmailTemplateService templateService;
    private final MessageDispatcher messageDispatcher;
    private final AdminSignupNotifier adminSignupNotifier;
    private final AdminBootstrapService adminBootstrapService;

    @Value("${nabu.mail.base-url}")
    private String baseUrl;

    /**
     * Registers a new user with email/password.
     * Creates the user (PENDING_VERIFICATION), and a personal org.
     * Sends a verification email.
     */
    @Transactional
    public UUID register(String email, String password, String name) {
        String normalizedEmail = email.toLowerCase().trim();

        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            log.info("[register] Duplicate registration attempt for email={}", normalizedEmail);
            return null; // silently return — caller returns same success response to prevent email enumeration
        }

        String resolvedName = (name != null && !name.isBlank())
                ? name.trim()
                : normalizedEmail.split("@")[0];

        // Create user
        User user = User.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(password))
                .fullName(resolvedName)
                .status(User.AccountStatus.PENDING_VERIFICATION)
                .role(User.Role.USER)
                .build();
        user = userRepository.save(user);

        // Create personal organization
        Organization org = Organization.builder()
                .name(resolvedName + "'s Workspace")
                .enabled(true)
                .build();
        org = organizationRepository.save(org);

        // Add user as owner of the org
        OrganizationMember membership = OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(OrganizationMember.Role.OWNER)
                .build();
        memberRepository.save(membership);

        // Store current org on the user
        user.setCurrentOrganizationId(org.getId());
        userRepository.save(user);

        // Send verification email (best-effort: user stays registered if SMTP is down)
        sendVerificationEmailSafely(user);

        // Notify internal admins (best-effort — must not fail the signup)
        adminSignupNotifier.notifyOfSignup(user, "email-password");

        log.info("[register] User created: email={} userId={} orgId={}", normalizedEmail, user.getId(), org.getId());
        return user.getId();
    }

    /**
     * Verifies a user's email address via the single-use token.
     * Transitions the account from PENDING_VERIFICATION to ACTIVE and sends a welcome email.
     *
     * @return the verified user's ID (used by the controller to auto-issue a JWT)
     */
    @Transactional
    public UUID verifyEmail(String token) {
        UUID userId = verificationTokenService.validateAndConsume(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired verification token"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        user.setEmailVerifiedAt(Instant.now());
        user.setStatus(User.AccountStatus.ACTIVE);
        userRepository.save(user);
        adminBootstrapService.promoteIfEligible(user);

        // Send welcome email
        var rendered = templateService.render("welcome", "en", Map.of(
                "userName", user.getFullName(),
                "dashboardUrl", baseUrl + "/dashboard"
        ));
        try {
            messageDispatcher.dispatch(Message.email(
                    user.getEmail(), "Welcome to Nabu Watch", rendered.html(), rendered.text()));
        } catch (Exception e) {
            log.error("[mail] Welcome email could not be sent: userId={} error={}. Check MAIL_* settings.",
                    userId, e.toString());
        }

        log.info("[verify] Email verified: userId={}", userId);
        return userId;
    }

    /**
     * Resends a verification email to the given address if the account is still pending.
     * Always succeeds silently — callers never learn whether the email address is registered.
     */
    public void resendVerification(String email) {
        userRepository.findByEmail(email.toLowerCase().trim()).ifPresent(user -> {
            if (user.getStatus() == User.AccountStatus.PENDING_VERIFICATION) {
                sendVerificationEmailSafely(user);
            }
        });
    }

    /**
     * Initiates the forgot-password flow.
     * Always succeeds silently — callers never learn whether the email address is registered.
     */
    public void forgotPassword(String email) {
        userRepository.findByEmail(email.toLowerCase().trim()).ifPresent(user -> {
            if (user.getStatus() != User.AccountStatus.ACTIVE) return;

            try {
            String token = resetTokenService.createToken(user.getId());
            String resetUrl = baseUrl + "/reset-password?token=" + token;

            var rendered = templateService.render("password-reset", "en", Map.of(
                    "userName", user.getFullName(),
                    "resetUrl", resetUrl,
                    "expirationMinutes", "60"
            ));
            messageDispatcher.dispatch(Message.email(
                    user.getEmail(), "Reset your password — Nabu Watch", rendered.html(), rendered.text()));

            log.info("[password-reset] Reset email sent: userId={}", user.getId());
            } catch (Exception e) {
                // Swallow: a 500 here would reveal that the account exists.
                log.error("[mail] Password reset email could not be sent: userId={} error={}. "
                        + "Check MAIL_* settings.", user.getId(), e.toString());
            }
        });
    }

    /**
     * Resets the user's password via a single-use token issued by {@link #forgotPassword}.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        UUID userId = resetTokenService.validateAndConsume(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset token"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        log.info("[password-reset] Password reset: userId={}", userId);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** Best-effort: a mail failure must not roll back the signup or leak account existence. */
    private void sendVerificationEmailSafely(User user) {
        try {
            sendVerificationEmail(user);
        } catch (Exception e) {
            log.error("[mail] Verification email could not be sent: userId={} error={}. "
                    + "Check MAIL_* settings; the user can request a new verification email "
                    + "(POST /api/v1/auth/resend-verification).", user.getId(), e.toString());
        }
    }

    private void sendVerificationEmail(User user) {
        String token = verificationTokenService.createToken(user.getId());
        String verifyUrl = baseUrl + "/verify-email?token=" + token;

        var rendered = templateService.render("verification", "en", Map.of(
                "userName", user.getFullName(),
                "verificationUrl", verifyUrl,
                "expirationHours", "24"
        ));
        messageDispatcher.dispatch(Message.email(
                user.getEmail(), "Verify your email — Nabu Watch", rendered.html(), rendered.text()));
    }
}
