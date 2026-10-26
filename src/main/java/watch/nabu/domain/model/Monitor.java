package watch.nabu.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "monitors", indexes = {
    @Index(name = "idx_monitors_website_id", columnList = "website_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Monitor extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "website_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_monitors_website_id"))
    private Website website;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private MonitorType type;

    /**
     * Fine-grained classification of what this monitor checks.
     *
     * <ul>
     *   <li>{@code SSL}             — TLS certificate validity (paired with {@code type=SSL})</li>
     *   <li>All other values        — specific legal-page checks (paired with {@code type=COMPLIANCE})</li>
     * </ul>
     *
     * For COMPLIANCE sub-types, {@code target_url} should be populated so the
     * worker scans the specific page rather than falling back to homepage mode.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "sub_type", nullable = false, length = 30)
    private SubType subType;

    @Min(1)
    @Column(name = "interval_minutes", nullable = false)
    @Builder.Default
    private int intervalMinutes = 60;

    /**
     * Optional URL override for this monitor.
     * When set, the worker scans this URL instead of {@code website.url}.
     * Useful for monitoring a specific subdomain (SSL) or a non-root page (COMPLIANCE).
     */
    @Size(max = 512)
    @Column(name = "target_url", length = 512)
    private String targetUrl;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /**
     * Whether this monitor is currently enabled.
     *
     * <p>Inactive monitors ({@code active = false}) are excluded from the
     * dispatch scheduler and not counted toward site health status.  Their
     * historical scan data is retained for auditing and re-activation.
     *
     * <p>The SSL monitor must always remain active; all deactivation logic
     * in service code enforces this invariant.
     */
    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    /**
     * Audit trail: the user who created this monitor (via website creation or sync).
     * Nullable for monitors created before this was recorded or by the scheduler.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id",
            foreignKey = @ForeignKey(name = "fk_monitors_created_by_user_id"))
    private User createdBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum MonitorType {
        SSL, COMPLIANCE
    }

    /** Fine-grained monitor sub-classification. */
    public enum SubType {
        // ── SSL ──────────────────────────────────────────────────────────────
        SSL("SSL Certificate", "SSL"),

        // ── Compliance legal-page checks ─────────────────────────────────────
        PRIVACY_POLICY("Privacy Policy", "PRIV"),
        TERMS_OF_SERVICE("Terms of Service", "TOS"),
        IMPRESSUM("Impressum", "IMPR"),
        COOKIE_POLICY("Cookie Policy", "CKPL"),
        GDPR_REQUEST("GDPR Request", "GDPR"),

        // ── Cookie consent mechanism check ───────────────────────────────────
        /**
         * Verifies that the site presents a GDPR/ePrivacy-compliant cookie
         * consent banner with functional accept and reject controls.
         * Handled by BannerScanner (Go worker) — no keyword payload required.
         */
        COOKIE_BANNER("Cookie Consent Banner", "CKBNR"),

        // ── Third-party tracker detection ────────────────────────────────────
        /**
         * Detects undisclosed third-party trackers (analytics, advertising,
         * social, fingerprinting, etc.) loaded by the website via headless
         * Chrome network interception.
         * Handled by TrackerScanner (Go worker) — no keyword payload required.
         */
        TRACKER_DETECTION("Tracker Detection", "TRKR");

        /**
         * Compliance grade weight per sub-type. Sub-types not in this map
         * (e.g. TRACKER_DETECTION) are excluded from the grade calculation.
         * When adding a new SubType, add its weight here if it should
         * contribute to the compliance grade.
         */
        private static final Map<SubType, Integer> GRADE_WEIGHTS = Map.of(
                SSL, 20,
                PRIVACY_POLICY, 15,
                TERMS_OF_SERVICE, 15,
                IMPRESSUM, 10,
                COOKIE_POLICY, 10,
                GDPR_REQUEST, 10,
                COOKIE_BANNER, 20
        );

        /**
         * Returns an unmodifiable view of all grade weights.
         */
        public static Map<SubType, Integer> gradeWeights() {
            return GRADE_WEIGHTS;
        }

        /**
         * Returns the compliance grade weight for this sub-type,
         * or {@code 0} if it does not contribute to the grade.
         */
        public int gradeWeight() {
            return GRADE_WEIGHTS.getOrDefault(this, 0);
        }

        private final String label;
        private final String shortLabel;

        SubType(String label, String shortLabel) {
            this.label = label;
            this.shortLabel = shortLabel;
        }

        /**
         * Human-readable display name — single source of truth for all label rendering.
         */
        public String label() {
            return label;
        }

        /**
         * Abbreviated label for compact UI contexts (notification badges, pills).
         */
        public String shortLabel() {
            return shortLabel;
        }
    }
}
