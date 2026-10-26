package watch.nabu.application.scan;

import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.ScanLog;

import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot of a completed scan extracted while the JPA transaction is still
 * open.  Carries only simple value types — no entity references — so it is
 * safe to pass across transaction boundaries without risk of
 * {@code LazyInitializationException}.
 */
public record ScanResultSummary(
    UUID                  scanLogId,
    UUID                  monitorId,
    Monitor.MonitorType   monitorType,
    Monitor.SubType       subType,
    UUID                  websiteId,
    String                websiteUrl,
    UUID organizationId,
    ScanLog.Severity      severity,
    Instant               scannedAt
) {}
