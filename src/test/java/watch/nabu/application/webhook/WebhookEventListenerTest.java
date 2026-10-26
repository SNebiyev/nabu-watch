package watch.nabu.application.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watch.nabu.application.scan.ScanResultEvent;
import watch.nabu.application.scan.ScanResultSummary;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.ScanLog;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookEventListenerTest {

    @Mock
    WebhookService webhookService;

    @InjectMocks
    WebhookEventListener listener;

    private ScanResultEvent buildEvent(UUID orgId) {
        ScanResultSummary summary = new ScanResultSummary(
                UUID.randomUUID(), UUID.randomUUID(),
                Monitor.MonitorType.SSL, Monitor.SubType.SSL,
                UUID.randomUUID(), "https://example.com", orgId,
                ScanLog.Severity.INFO, Instant.now());
        return new ScanResultEvent(this, summary);
    }

    @Test
    @DisplayName("onScanResult: dispatches webhook when orgId is present")
    void onScanResult_withOrgId_dispatches() {
        UUID orgId = UUID.randomUUID();
        listener.onScanResult(buildEvent(orgId));

        verify(webhookService).dispatchScanEvent(eq(orgId), any(ScanWebhookEvent.class));
    }

    @Test
    @DisplayName("onScanResult: skips when orgId is null")
    void onScanResult_nullOrgId_skips() {
        listener.onScanResult(buildEvent(null));

        verifyNoInteractions(webhookService);
    }

    @Test
    @DisplayName("onScanResult: catches and logs exception from webhookService")
    void onScanResult_exceptionSwallowed() {
        UUID orgId = UUID.randomUUID();
        doThrow(new RuntimeException("fail")).when(webhookService)
                .dispatchScanEvent(any(), any());

        // Should not throw
        listener.onScanResult(buildEvent(orgId));

        verify(webhookService).dispatchScanEvent(eq(orgId), any());
    }
}
