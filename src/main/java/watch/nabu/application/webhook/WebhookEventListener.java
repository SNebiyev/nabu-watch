package watch.nabu.application.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import watch.nabu.application.scan.ScanResultEvent;

/**
 * Dispatches outbound webhooks after scan results have been committed to the database.
 *
 * <p>The {@code AFTER_COMMIT} phase guarantees that any receiver who immediately
 * queries the nabu API will see the latest scan data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventListener {

    private final WebhookService webhookService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScanResult(ScanResultEvent event) {
        if (event.getOrganizationId() == null) return;
        try {
            webhookService.dispatchScanEvent(
                    event.getOrganizationId(),
                    new ScanWebhookEvent(
                            event.getScanLogId(),
                            event.getMonitorId(),
                            event.getMonitorType(),
                            event.getSubType(),
                            event.getWebsiteId(),
                            event.getWebsiteUrl(),
                            event.getOrganizationId(),
                            event.getSeverity(),
                            event.getScannedAt()
                    ));
        } catch (Exception e) {
            log.error("Webhook dispatch failed for org={}: {}", event.getOrganizationId(), e.getMessage(), e);
        }
    }
}
