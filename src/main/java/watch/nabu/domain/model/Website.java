package watch.nabu.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "websites", indexes = {
        @Index(name = "idx_websites_org_id", columnList = "organization_id"),
    @Index(name = "idx_websites_region_code", columnList = "region_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Website extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_websites_org_id"))
    private Organization organization;

    /**
     * Audit trail: the user who originally added this website.
     * Nullable for websites created before the multi-org migration.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by_user_id",
                foreignKey = @ForeignKey(name = "fk_websites_user_id"))
    private User addedBy;

    @NotBlank
    @Size(max = 2048)
    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @NotBlank
    @Size(min = 2, max = 10)
    @Column(name = "region_code", nullable = false, length = 10)
    private String regionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private WebsiteStatus status = WebsiteStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    /**
     * Timestamp of the last <em>user-initiated</em> (manual) re-scan for this website.
     *
     * <p>Set exclusively by {@code WebsiteService.rescan()} — never touched by the
     * automated scheduler.  Used together with {@code nabu.scan.manual-rescan-cooldown-minutes}
     * to enforce the cooldown window between manual rescans.
     *
     * <p>{@code null} when the website has never been manually rescanned.
     */
    @Column(name = "last_manual_scan_at")
    private Instant lastManualScanAt;

    /**
     * Controls whether English fallback keywords are treated as a passing result.
     * <ul>
     *   <li>{@code NATIVE_ONLY} — only native-language keywords count as success (default)</li>
     *   <li>{@code ENGLISH_ACCEPTED} — English keywords also count as success (native OR English = pass)</li>
     * </ul>
     * Irrelevant for English-native regions (GB, US) where fallback keywords are suppressed.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "accepted_languages", nullable = false, length = 20)
    @Builder.Default
    private AcceptedLanguages acceptedLanguages = AcceptedLanguages.NATIVE_ONLY;

    /**
     * When enabled, headless scans capture JPEG screenshots as visual evidence.
     * Effective only when file storage is configured on the server.
     * Only the organization owner can toggle this setting.
     */
    @Column(name = "screenshots_enabled", nullable = false)
    @Builder.Default
    private boolean screenshotsEnabled = false;

    /**
     * Weighted compliance grade letter (e.g. "A+", "B", "F").
     * Recalculated after every scan result. {@code null} until the first scan completes.
     */
    @Column(name = "compliance_grade", length = 3)
    private String complianceGrade;

    /**
     * Numeric compliance score (0–100) backing the letter grade.
     * {@code null} until the first scan completes.
     */
    @Column(name = "compliance_score")
    private Integer complianceScore;

    /**
     * Detected fetch mode for this website's homepage.
     * {@code null} = auto-detect (try static first, escalate to headless if needed).
     * {@code "HEADLESS"} = skip static fetch, go straight to headless Chrome.
     * Refreshed by {@code ScanResultProcessor} after every compliance scan.
     */
    @Column(name = "preferred_fetch_mode", length = 20)
    private String preferredFetchMode;

    /**
     * When {@link #preferredFetchMode} was last detected/refreshed.
     * The hint expires after 7 days — stale hints are ignored by TaskProducer,
     * forcing a fresh auto-detection cycle.
     */
    @Column(name = "fetch_mode_detected_at")
    private Instant fetchModeDetectedAt;

    // ── Domain ownership verification (DNS TXT) ──────────────────────────────

    /**
     * Mirror of {@link WebsiteVerification#getStatus()} for fast dashboard
     * filtering. {@code UNVERIFIED} only for rows created before migration
     * 082 (backfilled); new websites go straight to {@code PENDING}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    @Builder.Default
    private VerificationStatus verificationStatus = VerificationStatus.PENDING;

    /**
     * Timestamp at which DNS TXT verification succeeded.
     * Mirror of {@link WebsiteVerification#getVerifiedAt()}.
     */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    public enum VerificationStatus {
        UNVERIFIED, PENDING, VERIFIED, EXPIRED
    }

    public enum WebsiteStatus {
        ACTIVE, PAUSED
    }

    public enum AcceptedLanguages {
        /**
         * Only native-language keywords count as success (default).
         */
        NATIVE_ONLY,
        /**
         * Site is in English despite non-English region — scan uses English keywords as required.
         */
        ENGLISH_ONLY,
        /**
         * Bilingual site — native OR English keywords both count as success.
         */
        BOTH_ACCEPTED;

        /**
         * Returns true if English results should be treated as passing.
         */
        public boolean acceptsEnglish() {
            return this == ENGLISH_ONLY || this == BOTH_ACCEPTED;
        }
    }
}
