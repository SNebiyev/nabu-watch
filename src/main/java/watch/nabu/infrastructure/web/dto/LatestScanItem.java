package watch.nabu.infrastructure.web.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Returned by {@code GET /api/v1/websites/{id}/latest-scans}.
 *
 * <p>One item per monitor: the most-recent {@code ScanLog} result plus the
 * monitor's type/subType discriminators so the frontend can render the
 * appropriate detail panel without a second round-trip.
 */
public record LatestScanItem(
        UUID                scanLogId,
        UUID                monitorId,
        String              monitorType,
        String              subType,
        String              status,
        /**
         * Single source of truth for display status — includes threshold-based
         * warning detection. Possible values: {@code "PASSING"}, {@code "FAILING"},
         * {@code "WARNING"}, {@code "PENDING"}, {@code "INACTIVE"}.
         */
        String effectiveStatus,
        String              severity,
        Instant             scannedAt,
        Map<String, Object> resultJson,
        String targetUrl,
        /** How this scan was triggered: {@code "MANUAL"} or {@code "SCHEDULED"}; {@code null} for legacy rows. */
        String triggerType,
        /** Email of the user who triggered this scan; {@code null} for scheduled scans. */
        String triggeredByEmail,
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
        WebsiteHealthListItem.OverrideInfo overrideInfo,
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
        /** One-sentence AI reasoning explaining the semantic equivalence; null otherwise. */
        String aiVerificationReasoning,
        /** True when this scan was completed via fast-path verification against website_compliance_paths. */
        boolean fastPathVerified,
        /** True when the latest scan failed due to a WAF (Web Application Firewall) block. */
        boolean wafBlocked
) {}
