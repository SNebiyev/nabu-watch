package watch.nabu.domain.model;

import watch.nabu.domain.model.ScanLog.Severity;

/**
 * Describes how a monitor's newest scan compares to its immediately-preceding
 * scan. Derived from the pair (previousSeverity, currentSeverity).
 *
 * <p>Comparison uses <strong>both</strong> the raw severity ordinal
 * ({@code INFO < LOW < MEDIUM < HIGH < CRITICAL}) and the display bucket
 * ({@code FAILING} = CRITICAL/HIGH/MEDIUM, {@code PASSING} = INFO/LOW) so the
 * UI can distinguish bucket-crossing transitions (the loud signal) from
 * within-bucket movements (a softer improvement/regression).
 *
 * <p>Bucket definition matches {@code WebsiteHealthResponse.scanStatus} — the
 * canonical FAILING/PASSING mapping used across the dashboard.
 */
public enum ScanStatusChange {
    /**
     * No preceding scan existed — this is the monitor's first result.
     */
    FIRST_SCAN,
    /**
     * Severity is identical to the previous scan.
     */
    UNCHANGED,
    /**
     * Severity ordinal improved, but stayed in the same display bucket.
     */
    IMPROVED,
    /**
     * Severity ordinal regressed, but stayed in the same display bucket.
     */
    REGRESSED,
    /**
     * Moved out of the FAILING bucket into PASSING — the loud "green" signal.
     */
    RECOVERED,
    /**
     * Moved out of the PASSING bucket into FAILING — the loud "red" signal.
     */
    REGRESSED_TO_FAILING;

    /**
     * Computes the change descriptor from the two severities. A null
     * {@code previous} yields {@link #FIRST_SCAN}; a null {@code current}
     * yields {@link #UNCHANGED} (defensive — ScanLog always persists
     * a non-null severity, so this branch is unreachable in practice).
     */
    public static ScanStatusChange compute(Severity previous, Severity current) {
        if (previous == null) {
            return FIRST_SCAN;
        }
        if (current == null || previous == current) {
            return UNCHANGED;
        }
        boolean prevFailing = isFailing(previous);
        boolean currFailing = isFailing(current);
        if (prevFailing && !currFailing) return RECOVERED;
        if (!prevFailing && currFailing) return REGRESSED_TO_FAILING;
        // Same bucket — fall back to ordinal comparison.
        return current.ordinal() > previous.ordinal() ? REGRESSED : IMPROVED;
    }

    private static boolean isFailing(Severity s) {
        return s == Severity.CRITICAL || s == Severity.HIGH || s == Severity.MEDIUM;
    }
}
