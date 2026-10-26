package watch.nabu.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * DNS TXT-based domain ownership verification state for a {@link Website}.
 *
 * <p>One row exists per website for the lifetime of the website.  Row is
 * provisioned at website creation time ({@link WebsiteService}) with a fresh
 * random token and a 7-day expiry window.  Status transitions:
 *
 * <pre>
 *   PENDING ── verify ────────────► VERIFIED
 *       │
 *       └── 7d elapse with no hit ─► EXPIRED
 * </pre>
 *
 * Once {@code VERIFIED} the row is not re-checked — the user may remove the
 * DNS record afterwards and the website stays verified (DNS record is proof
 * of ownership at a point in time, not ongoing control).
 */
@Entity
@Table(name = "website_verifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebsiteVerification extends BaseEntity {

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "website_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_website_verifications_website_id"))
    private Website website;

    /**
     * The value the user must place inside a {@code _nabu-challenge.<domain>}
     * TXT record, e.g. {@code nabu-verify=AB12CD34…}.  Generated once at row
     * creation via crypto-random; unique across the table.
     */
    @Column(name = "token", nullable = false, length = 80, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "check_count", nullable = false)
    @Builder.Default
    private int checkCount = 0;

    /**
     * Last failure message from DNS lookup (e.g. {@code "NXDOMAIN"}, {@code
     * "no matching TXT value"}, {@code "lookup timeout"}).  Surfaced in the UI
     * so the user sees why a manual check failed.
     */
    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    /**
     * Set when the 24h-before-expiry warning email has been dispatched, so
     * the scheduler never re-sends it within a single 7-day challenge window.
     */
    @Column(name = "expiry_warning_sent_at")
    private Instant expiryWarningSentAt;

    public enum Status {
        PENDING, VERIFIED, EXPIRED
    }
}
