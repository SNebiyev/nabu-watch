package watch.nabu.application.website;

import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.WebsiteHealth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Application-layer read model returned by {@link WebsiteQueryService#getHealthView}.
 *
 * <p>Unlike the domain's {@link WebsiteHealth} (which carries raw scan data),
 * this view already contains pre-formatted, human-readable summaries so the
 * HTTP layer can serialize it directly without any business logic.
 */
public record WebsiteHealthView(
        UUID                  websiteId,
        String                url,
        String                regionCode,
        WebsiteHealth.HealthColor color,
        List<MonitorSummary>  monitors,
        Instant evaluatedAt,
        String complianceGrade,
        Integer complianceScore
) {

    /**
     * Per-monitor slice of the health view.
     *
     * @param monitorId     identity of the monitor
     * @param monitorType   SSL, COMPLIANCE, etc.
     * @param severity      severity of the latest scan; {@code null} if never run
     * @param lastCheckedAt timestamp of the latest scan; {@code null} if never run
     * @param summary       human-readable one-liner, e.g. "SSL expires in 15 days"
     */
    public record MonitorSummary(
            UUID                  monitorId,
            Monitor.MonitorType   monitorType,
            Monitor.SubType       subType,
            ScanLog.Severity      severity,      // nullable
            Instant               lastCheckedAt, // nullable
            String                summary
    ) {}
}
