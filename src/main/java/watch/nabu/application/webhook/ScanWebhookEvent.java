package watch.nabu.application.webhook;

import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.ScanLog;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a scan result used to build webhook payloads.
 * Carries only simple value types — no JPA entity references.
 */
public record ScanWebhookEvent(
        UUID scanLogId,
        UUID monitorId,
        Monitor.MonitorType monitorType,
        Monitor.SubType subType,
        UUID websiteId,
        String websiteUrl,
        UUID organizationId,
        ScanLog.Severity severity,
        Instant scannedAt
) {
}
