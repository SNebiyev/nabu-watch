package watch.nabu.infrastructure.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight health snapshot returned by {@code GET /api/v1/websites}.
 *
 * <p>Includes every monitor's current status so the dashboard can show
 * issue counts and traffic-light indicators without a second round-trip.
 *
 * <p>Field names deliberately match the frontend {@code WebsiteHealth} type:
 * {@code region} (not {@code regionCode}), {@code lastCheckedAt}.
 */
public record WebsiteHealthListItem(
        UUID                    id,
        String                  url,
        String                  region,
        String                  status,
        /** Website entity status: {@code "ACTIVE"} or {@code "PAUSED"}. */
        String websiteStatus,
        Instant                 lastCheckedAt,
        List<MonitorHealthItem> monitors,
        /**
         * Email of the user who owns this website.
         * Exposed so the admin UI can determine ownership without a second round-trip:
         * if {@code ownerEmail} differs from the authenticated admin's email, the
         * secure-delete flow (OTP step-up) is triggered on the frontend.
         */
        String ownerEmail,
        String acceptedLanguages,
        boolean screenshotsEnabled,
        /** Weighted compliance grade letter (e.g. "A+", "B", "F"). {@code null} until first scan. */
        String complianceGrade,
        /** Numeric compliance score 0–100. {@code null} until first scan. */
        Integer complianceScore,
        /**
         * DNS TXT ownership verification status mirror: {@code UNVERIFIED},
         * {@code PENDING}, {@code VERIFIED}, or {@code EXPIRED}. Drives the
         * dashboard-row badge that points owners at the expand-panel
         * verification card.
         */
        String verificationStatus
) {

    /**
     * Per-monitor status item — matches the frontend {@code MonitorHealth} interface.
     *
     * <p>{@code active = false} means the monitor has been soft-disabled via
     * "Manage Monitors".  Its {@code status} will be {@code "INACTIVE"} and it
     * is excluded from the site-level health calculation.
     */
    public record MonitorHealthItem(
            UUID    id,
            String  type,
            String  subType,
            String  status,
            /**
             * Single source of truth for display status — includes threshold-based
             * warning detection. Possible values: {@code "PASSING"}, {@code "FAILING"},
             * {@code "WARNING"}, {@code "PENDING"}, {@code "INACTIVE"}.
             *
             * <p>The frontend should use this field for all status rendering and never
             * re-derive warning logic from individual fields.
             */
            String effectiveStatus,
            Instant lastCheckedAt,
            String  severity,
            boolean active,
            boolean languageInconsistency,
            /** Number of missing keywords in the latest scan; 0 for SSL or pending monitors. */
            int missingKeywordCount,
            /**
             * Number of keywords successfully found in the latest scan.
             * When {@code > 0} the monitor is considered compliant (green) even if
             * some keywords are missing — the threshold-based success rule applies.
             * 0 for SSL monitors or when no scan has run yet.
             */
            int foundKeywordCount,
            /**
             * True when the latest compliance scan retrieved actual keyword data
             * (found + missing + broken arrays are not all empty).
             * False for SSL monitors or when no scan has run yet.
             * When false on a PASSING COMPLIANCE monitor the scan was INCOMPLETE
             * (page was likely blocked by a WAF and no content was retrieved).
             */
            boolean hasKeywordData,
            /** When this monitor was created; {@code null} for legacy monitors. */
            Instant createdAt,
            /** Email of the user who created this monitor; {@code null} for legacy/scheduler-created monitors. */
            String createdByEmail,
            /** How the latest scan was triggered: {@code "MANUAL"} or {@code "SCHEDULED"}; {@code null} if no scan yet. */
            String lastTriggerType,
            /** Computed next automatic scan time ({@code lastRunAt + intervalMinutes}); {@code null} for new monitors. */
            Instant nextScanAt,
            /** Whether a manual severity override is active for this monitor. */
            boolean hasOverride,
            /**
             * True when an override exists but the latest scan severity no longer matches
             * the override's baseline — the scanner result changed since the override was set.
             */
            boolean overrideMismatch,
            /** Details of the active override; {@code null} when no override exists. */
            OverrideInfo overrideInfo,
            /**
             * Severity of the monitor's immediately-preceding scan; {@code null} when
             * this was the first scan or for legacy rows scanned before this field was tracked.
             */
            String previousSeverity,
            /**
             * How the latest scan compares to the previous one:
             * {@code FIRST_SCAN}, {@code UNCHANGED}, {@code IMPROVED}, {@code REGRESSED},
             * {@code RECOVERED} (FAILING→PASSING bucket), {@code REGRESSED_TO_FAILING}
             * (PASSING→FAILING bucket). {@code null} when no scan exists yet.
             */
            String statusChange,
            /** Non-standard term AI verified as equivalent to the mandatory compliance keyword; null otherwise. */
            String aiVerifiedTerm,
            /** One-sentence reasoning from AI explaining why the non-standard term was accepted; null otherwise. */
            String aiVerificationReasoning,
            /** True when the latest scan was completed via fast-path verification against website_compliance_paths. */
            boolean fastPathVerified,
            /** True when the latest scan failed due to a WAF (Web Application Firewall) block. */
            boolean wafBlocked
    ) {
    }

    /**
     * Compact summary of an active severity override, visible to all organization members.
     */
    public record OverrideInfo(
            String baselineSeverity,
            String overrideSeverity,
            String overriddenByEmail,
            Instant overriddenAt,
            String note
    ) {}
}
