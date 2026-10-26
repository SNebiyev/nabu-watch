package watch.nabu.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(name = "uq_users_email", columnNames = "email")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @NotBlank
    @Size(max = 100)
    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @NotBlank
    @Email
    @Size(max = 255)
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /**
     * BCrypt hash of the user's password. {@code null} for OAuth-only accounts
     * (those that signed up via Google/GitHub and have never set a password).
     */
    @Column(name = "password_hash")
    private String passwordHash;

    // ── OAuth2 ────────────────────────────────────────────────────────────────

    /**
     * Lowercase provider identifier: {@code "google"}, {@code "github"}, …
     * {@code null} for email/password accounts.
     */
    @Column(name = "oauth_provider", length = 50)
    private String oauthProvider;

    /**
     * The provider's stable user identifier (OIDC {@code sub} or GitHub numeric id).
     * Combined with {@code oauthProvider} it is unique across the system.
     */
    @Column(name = "oauth_provider_id", length = 255)
    private String oauthProviderId;

    // ── Onboarding ────────────────────────────────────────────────────────────

    /**
     * {@code true} until the user successfully adds their first website.
     * Used by the frontend to drive the onboarding wizard.
     * Flipped to {@code false} in {@link watch.nabu.application.website.WebsiteService#create}.
     */
    @Column(name = "is_first_login", nullable = false)
    @Builder.Default
    private boolean firstLogin = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private AccountStatus status = AccountStatus.PENDING_VERIFICATION;

    // ── Organization context ────────────────────────────────────────────────

    /**
     * The organization the user is currently working in.
     * Used as fallback when the JWT does not carry an {@code orgId} claim
     * (e.g. tokens issued before the multi-org migration).
     */
    @Column(name = "current_organization_id")
    private UUID currentOrganizationId;

    // ── Auth ──────────────────────────────────────────────────────────────────

    /**
     * When {@code true} the user must change their password before accessing the
     * platform.  Set on initial seed accounts and can be toggled by admins.
     * Reflected as the {@code pwdChangeRequired} JWT claim so the frontend can
     * redirect immediately after login without a separate API call.
     */
    @Column(name = "require_password_change", nullable = false)
    @Builder.Default
    private boolean requirePasswordChange = false;

    // ── Notification preferences ────────────────────────────────────────────

    /**
     * When {@code true}, the user receives an immediate email alert when one of
     * their organizations' monitors regresses into HIGH/CRITICAL severity.
     */
    @Column(name = "email_alerts_enabled", nullable = false)
    @Builder.Default
    private boolean emailAlertsEnabled = true;

    /**
     * When {@code true}, the user receives a weekly digest email every Monday
     * at 08:00 UTC summarising scans and failing websites across their orgs.
     */
    @Column(name = "weekly_digest_enabled", nullable = false)
    @Builder.Default
    private boolean weeklyDigestEnabled = true;

    /**
     * Controls transactional lifecycle emails (abuse upheld / site removed /
     * verification expired / expiry warning). Separate from
     * {@link #emailAlertsEnabled} so users can silence one bucket without
     * losing the other — scan alerts and lifecycle alerts are emotionally
     * different and should opt out independently.
     */
    @Column(name = "lifecycle_emails_enabled", nullable = false)
    @Builder.Default
    private boolean lifecycleEmailsEnabled = true;

    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    @Column(name = "totp_enabled", nullable = false)
    @Builder.Default
    private boolean totpEnabled = false;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    // ── User-level verification badge (recomputed from owned websites) ────────

    /**
     * Aggregate verification badge for the user. Recomputed from the user's
     * non-deleted websites by {@code UserVerificationService.recomputeUserStatus}
     * after any DomainVerifiedEvent / WebsiteCreatedEvent / WebsiteDeletedEvent.
     * Source of truth is the underlying {@link WebsiteVerification} rows;
     * this column exists for fast public-profile + priority-queue lookups.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    @Builder.Default
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verification_revoked_at")
    private Instant verificationRevokedAt;

    /**
     * Public slug for {@code /u/{username}} trust profile. Case-insensitive
     * unique when present. {@code null} until the user opts in via settings.
     */
    @Column(name = "public_username", length = 40)
    private String publicUsername;

    @Column(name = "public_profile_enabled", nullable = false)
    @Builder.Default
    private boolean publicProfileEnabled = false;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @return {@code true} when the user's email has been verified
     * (i.e. {@code status == ACTIVE}).  Used by the security
     * layer and by {@code /api/v1/auth/me} to drive the
     * email-verification wall.
     */
    public boolean isVerified() {
        return status == AccountStatus.ACTIVE;
    }

    public enum Role {
        USER, ADMIN
    }

    public enum AccountStatus {
        PENDING_VERIFICATION, ACTIVE, SUSPENDED, DELETED
    }

    /**
     * UNVERIFIED = no verified website (or none at all);
     * VERIFIED   = at least one website AND every non-deleted website is VERIFIED;
     * VERIFIED_PENDING = was VERIFIED, now has a freshly-added site still inside
     *                    its 7-day grace period (badge preserved during grace).
     */
    public enum VerificationStatus {
        UNVERIFIED, VERIFIED, VERIFIED_PENDING
    }
}
