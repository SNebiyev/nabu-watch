package watch.nabu.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregated health snapshot for a website, derived from the latest
 * {@link ScanLog} of each associated {@link Monitor}.
 *
 * <p>Severity → colour mapping:
 * <ul>
 *   <li>ANY monitor has {@code CRITICAL}              → {@link HealthColor#RED}</li>
 *   <li>No CRITICAL, but any has {@code HIGH/MEDIUM}  → {@link HealthColor#YELLOW}</li>
 *   <li>All INFO/LOW or no scan data yet              → {@link HealthColor#GREEN}</li>
 * </ul>
 */
public record WebsiteHealth(
        UUID websiteId,
        HealthColor color,
        List<MonitorHealth> monitors,
        Instant evaluatedAt
) {

    public enum HealthColor {
        GREEN, YELLOW, RED
    }

    /**
     * Per-monitor slice of the aggregated health snapshot.
     *
     * @param monitorId      identity of the monitor
     * @param monitorType    SSL, COMPLIANCE, etc.
     * @param latestSeverity severity from the most recent scan; {@code null} if
     *                       the monitor has never run
     * @param lastScannedAt  timestamp of the most recent scan; {@code null} if
     *                       the monitor has never run
     */
    public record MonitorHealth(
            UUID monitorId,
            Monitor.MonitorType monitorType,
            ScanLog.Severity latestSeverity,   // nullable
            Instant lastScannedAt              // nullable
    ) {}
}
