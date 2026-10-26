package watch.nabu.application.scan;

import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.MonitorStatusOverride;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.ScanLog.Severity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calculates a weighted compliance grade for a website based on the latest
 * scan result per monitor.
 *
 * <p>Weights are defined on {@link SubType#gradeWeights()} so they live next
 * to the enum values — adding a new SubType without a weight is immediately
 * visible. Sub-types with weight 0 (e.g. TRACKER_DETECTION) are excluded.
 *
 * <p>The calculator is a stateless utility — no Spring dependencies.
 */
public final class ComplianceGradeCalculator {

    private ComplianceGradeCalculator() {
    }

    /**
     * Converts a {@link Severity} to a 0.0–1.0 multiplier.
     * <ul>
     *   <li>INFO  → 1.0  (fully passing)</li>
     *   <li>LOW   → 0.8</li>
     *   <li>MEDIUM → 0.5</li>
     *   <li>HIGH  → 0.2</li>
     *   <li>CRITICAL → 0.0</li>
     *   <li>{@code null} (pending / never scanned) → 0.0</li>
     * </ul>
     */
    static double severityMultiplier(Severity severity) {
        if (severity == null) return 0.0;
        return switch (severity) {
            case INFO -> 1.0;
            case LOW -> 0.8;
            case MEDIUM -> 0.5;
            case HIGH -> 0.2;
            case CRITICAL -> 0.0;
        };
    }

    /**
     * Maps a numeric score (0–100) to a letter grade.
     */
    static String scoreToGrade(int score) {
        if (score >= 95) return "A+";
        if (score >= 90) return "A";
        if (score >= 85) return "A-";
        if (score >= 80) return "B+";
        if (score >= 70) return "B";
        if (score >= 60) return "C";
        if (score >= 50) return "D";
        return "F";
    }

    /**
     * Calculates the compliance score and grade for a website (no overrides).
     *
     * @param monitors   all monitors belonging to the website (active and inactive)
     * @param latestLogs latest {@link ScanLog} per monitor (keyed by monitor ID)
     * @return the result containing both numeric score and letter grade
     */
    public static GradeResult calculate(List<Monitor> monitors, Map<UUID, ScanLog> latestLogs) {
        return calculate(monitors, latestLogs, Map.of());
    }

    /**
     * Calculates the compliance score and grade for a website, applying any
     * active severity overrides whose baseline matches the current scan severity.
     *
     * @param monitors   all monitors belonging to the website (active and inactive)
     * @param latestLogs latest {@link ScanLog} per monitor (keyed by monitor ID)
     * @param overrides  active overrides per monitor (keyed by monitor ID); may be empty
     * @return the result containing both numeric score and letter grade
     */
    public static GradeResult calculate(List<Monitor> monitors, Map<UUID, ScanLog> latestLogs,
                                        Map<UUID, MonitorStatusOverride> overrides) {
        // Only consider monitors that have a grade weight > 0 (excludes TRACKER_DETECTION)
        // and that are active.
        List<Monitor> graded = monitors.stream()
                .filter(Monitor::isActive)
                .filter(m -> m.getSubType().gradeWeight() > 0)
                .toList();

        if (graded.isEmpty()) {
            return new GradeResult(0, "F");
        }

        // Normalise weights so they sum to 100 even when some monitor types
        // are absent (e.g. no IMPRESSUM monitor for US-region websites).
        int totalRawWeight = graded.stream()
                .mapToInt(m -> m.getSubType().gradeWeight())
                .sum();

        double weightedScore = 0.0;
        for (Monitor m : graded) {
            ScanLog latest = latestLogs.get(m.getId());
            Severity severity = latest != null ? latest.getSeverity() : null;

            // Apply override when the baseline matches the current severity
            MonitorStatusOverride override = overrides.get(m.getId());
            if (override != null && override.matches(severity)) {
                severity = override.getOverrideSeverity();
            }

            double normalizedWeight = (double) m.getSubType().gradeWeight() / totalRawWeight * 100.0;
            weightedScore += normalizedWeight * severityMultiplier(severity);
        }

        int score = (int) Math.round(weightedScore);
        score = Math.max(0, Math.min(100, score));

        return new GradeResult(score, scoreToGrade(score));
    }

    public record GradeResult(int score, String grade) {
    }
}
