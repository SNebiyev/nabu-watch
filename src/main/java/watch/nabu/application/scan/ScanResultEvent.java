package watch.nabu.application.scan;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.ScanLog;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@link watch.nabu.infrastructure.redis.ResultConsumer} after a
 * scan result has been persisted and the database transaction has committed.
 *
 * <p>Consumers should annotate their listener methods with
 * {@link org.springframework.transaction.event.TransactionalEventListener} to
 * guarantee they run after the transaction is fully committed.
 *
 * <p>All fields are simple value types — no JPA entity references — so there
 * is no risk of lazy-loading after the session has closed.
 */
@Getter
public class ScanResultEvent extends ApplicationEvent {

    private final UUID                scanLogId;
    private final UUID                monitorId;
    private final Monitor.MonitorType monitorType;
    private final Monitor.SubType     subType;
    private final UUID                websiteId;
    private final String              websiteUrl;
    private final UUID organizationId;
    private final ScanLog.Severity    severity;
    private final Instant             scannedAt;

    public ScanResultEvent(Object source, ScanResultSummary summary) {
        super(source);
        this.scanLogId = summary.scanLogId();
        this.monitorId = summary.monitorId();
        this.monitorType = summary.monitorType();
        this.subType = summary.subType();
        this.websiteId = summary.websiteId();
        this.websiteUrl = summary.websiteUrl();
        this.organizationId = summary.organizationId();
        this.severity = summary.severity();
        this.scannedAt = summary.scannedAt();
    }
}
