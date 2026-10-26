package watch.nabu.application.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.WebhookConfig;
import watch.nabu.domain.model.WebhookDelivery;
import watch.nabu.domain.model.WebhookDelivery.DeliveryStatus;
import watch.nabu.infrastructure.persistence.WebhookConfigRepository;
import watch.nabu.infrastructure.persistence.WebhookDeliveryRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    WebhookConfigRepository webhookConfigRepository;
    @Mock
    WebhookDeliveryRepository webhookDeliveryRepository;
    @Mock
    WebhookPayloadBuilder payloadBuilder;

    @InjectMocks
    WebhookService webhookService;

    private UUID orgId;
    private UUID configId;
    private WebhookConfig enabledConfig;
    private WebhookConfig disabledConfig;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        configId = UUID.randomUUID();

        enabledConfig = WebhookConfig.builder()
                .organizationId(orgId)
                .name("My webhook")
                .url("https://example.com/hook")
                .type(WebhookConfig.WebhookType.GENERIC)
                .enabled(true)
                .build();
        ReflectionTestUtils.setField(enabledConfig, "id", configId);

        disabledConfig = WebhookConfig.builder()
                .organizationId(orgId)
                .name("Disabled")
                .url("https://example.com/disabled")
                .type(WebhookConfig.WebhookType.GENERIC)
                .enabled(false)
                .build();
        ReflectionTestUtils.setField(disabledConfig, "id", UUID.randomUUID());
    }

    // ── listForOrg ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listForOrg returns all configs for the org")
    void listForOrg_returnsAll() {
        when(webhookConfigRepository.findByOrganizationId(orgId))
                .thenReturn(List.of(enabledConfig, disabledConfig));

        List<WebhookConfig> result = webhookService.listForOrg(orgId);

        assertThat(result).hasSize(2);
    }

    // ── create ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create persists and returns new WebhookConfig (no plan gate)")
    void create_persistsNewConfig() {
        when(webhookConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WebhookConfig config = webhookService.create(
                orgId, "Test Hook", "https://hook.example.com",
                "s3cr3t", WebhookConfig.WebhookType.SLACK);

        assertThat(config.getName()).isEqualTo("Test Hook");
        assertThat(config.getType()).isEqualTo(WebhookConfig.WebhookType.SLACK);
        assertThat(config.isEnabled()).isTrue();
        verify(webhookConfigRepository).save(any(WebhookConfig.class));
    }

    // ── update ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("update modifies existing config fields")
    void update_modifiesFields() {
        when(webhookConfigRepository.findByIdAndOrganizationId(configId, orgId))
                .thenReturn(Optional.of(enabledConfig));

        WebhookConfig result = webhookService.update(
                configId, orgId, "Updated Name", "https://new.example.com",
                null, WebhookConfig.WebhookType.SLACK, false);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getUrl()).isEqualTo("https://new.example.com");
        assertThat(result.getType()).isEqualTo(WebhookConfig.WebhookType.SLACK);
        assertThat(result.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("update on unknown configId throws WebhookNotFoundException")
    void update_unknownId_throws() {
        when(webhookConfigRepository.findByIdAndOrganizationId(any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                webhookService.update(configId, orgId, "n", "https://x.com",
                        null, WebhookConfig.WebhookType.GENERIC, true))
                .isInstanceOf(WebhookNotFoundException.class);
    }

    // ── delete ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete removes config when it belongs to the org")
    void delete_removesConfig() {
        when(webhookConfigRepository.existsByIdAndOrganizationId(configId, orgId)).thenReturn(true);

        webhookService.delete(configId, orgId);

        verify(webhookConfigRepository).deleteById(configId);
    }

    @Test
    @DisplayName("delete throws WebhookNotFoundException when config not found for org")
    void delete_unknownId_throws() {
        when(webhookConfigRepository.existsByIdAndOrganizationId(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> webhookService.delete(configId, orgId))
                .isInstanceOf(WebhookNotFoundException.class);

        verify(webhookConfigRepository, never()).deleteById(any());
    }

    // ── dispatchScanEvent ──────────────────────────────────────────────────────

    @Test
    @DisplayName("dispatchScanEvent skips delivery when no enabled webhooks exist")
    void dispatchScanEvent_noWebhooks_doesNothing() {
        when(webhookConfigRepository.findByOrganizationIdAndEnabledTrue(orgId))
                .thenReturn(List.of());

        webhookService.dispatchScanEvent(orgId, buildScanEvent());

        verify(webhookDeliveryRepository, never()).save(any());
    }

    @Test
    @DisplayName("dispatchScanEvent creates a delivery record for each enabled webhook")
    void dispatchScanEvent_createsDeliveryPerConfig() {
        when(webhookConfigRepository.findByOrganizationIdAndEnabledTrue(orgId))
                .thenReturn(List.of(enabledConfig));
        when(payloadBuilder.build(any(), any())).thenReturn(Map.of("event", "scan.result"));
        when(payloadBuilder.toJson(any())).thenReturn("{\"event\":\"scan.result\"}");

        // stub the config lookup used by the HTTP attempt path (findById inside attempt)
        // In test, HTTP call fails → markFailed is called; delivery is saved
        WebhookDelivery savedDelivery = WebhookDelivery.builder()
                .webhookConfigId(configId)
                .eventType("SCAN_RESULT")
                .payload(Map.of("event", "scan.result"))
                .build();
        ReflectionTestUtils.setField(savedDelivery, "id", UUID.randomUUID());
        when(webhookDeliveryRepository.save(any())).thenReturn(savedDelivery);

        webhookService.dispatchScanEvent(orgId, buildScanEvent());

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(webhookDeliveryRepository, atLeastOnce()).save(captor.capture());
        List<WebhookDelivery> saved = captor.getAllValues();
        assertThat(saved).isNotEmpty();
        assertThat(saved.getFirst().getEventType()).isEqualTo("SCAN_RESULT");
    }

    // ── sendTest ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendTest throws WebhookNotFoundException when config not in org")
    void sendTest_unknownId_throws() {
        when(webhookConfigRepository.findByIdAndOrganizationId(any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> webhookService.sendTest(configId, orgId))
                .isInstanceOf(WebhookNotFoundException.class);
    }

    @Test
    @DisplayName("sendTest creates a TEST delivery record")
    void sendTest_createsTestDelivery() {
        when(webhookConfigRepository.findByIdAndOrganizationId(configId, orgId))
                .thenReturn(Optional.of(enabledConfig));
        when(payloadBuilder.buildTest(any())).thenReturn(Map.of("event", "test"));
        when(payloadBuilder.toJson(any())).thenReturn("{\"event\":\"test\"}");

        WebhookDelivery testDelivery = WebhookDelivery.builder()
                .webhookConfigId(configId)
                .eventType("TEST")
                .payload(Map.of("event", "test"))
                .build();
        ReflectionTestUtils.setField(testDelivery, "id", UUID.randomUUID());
        when(webhookDeliveryRepository.save(any())).thenReturn(testDelivery);

        webhookService.sendTest(configId, orgId);

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(webhookDeliveryRepository, atLeastOnce()).save(captor.capture());
        boolean hasTestEvent = captor.getAllValues().stream()
                .anyMatch(d -> "TEST".equals(d.getEventType()));
        assertThat(hasTestEvent).isTrue();
    }

    // ── retryFailed ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("retryFailed skips disabled webhook configs")
    void retryFailed_skipsDisabledConfig() {
        WebhookDelivery failedDelivery = WebhookDelivery.builder()
                .webhookConfigId(disabledConfig.getId())
                .eventType("SCAN_RESULT")
                .payload(Map.of("event", "scan.result"))
                .status(DeliveryStatus.FAILED)
                .attemptCount(1)
                .nextRetryAt(Instant.now().minusSeconds(60))
                .build();
        ReflectionTestUtils.setField(failedDelivery, "id", UUID.randomUUID());

        when(webhookDeliveryRepository.findPendingRetries(any(), anyInt(), any()))
                .thenReturn(List.of(failedDelivery));
        when(webhookConfigRepository.findById(disabledConfig.getId()))
                .thenReturn(Optional.of(disabledConfig));

        webhookService.retryFailed();

        // Should not attempt delivery for disabled config
        verify(payloadBuilder, never()).toJson(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ScanWebhookEvent buildScanEvent() {
        return new ScanWebhookEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Monitor.MonitorType.COMPLIANCE,
                Monitor.SubType.PRIVACY_POLICY,
                UUID.randomUUID(),
                "https://example.com",
                orgId,
                ScanLog.Severity.MEDIUM,
                Instant.now()
        );
    }
}
