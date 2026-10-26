package watch.nabu.application.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.WebhookConfig.WebhookType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebhookPayloadBuilder builder = new WebhookPayloadBuilder(objectMapper);

    private ScanWebhookEvent buildEvent(ScanLog.Severity severity) {
        return new ScanWebhookEvent(
                UUID.randomUUID(), UUID.randomUUID(),
                Monitor.MonitorType.COMPLIANCE, Monitor.SubType.PRIVACY_POLICY,
                UUID.randomUUID(), "https://example.com", UUID.randomUUID(),
                severity, Instant.parse("2026-03-30T10:00:00Z"));
    }

    // ── build GENERIC ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("build GENERIC: contains all expected fields")
    void buildGeneric_containsAllFields() {
        Map<String, Object> payload = builder.build(WebhookType.GENERIC, buildEvent(ScanLog.Severity.INFO));

        assertThat(payload).containsKey("event");
        assertThat(payload.get("event")).isEqualTo("scan.result");
        assertThat(payload).containsKeys("scanLogId", "monitorId", "monitorType",
                "subType", "websiteId", "websiteUrl", "organizationId",
                "severity", "status", "scannedAt");
    }

    @Test
    @DisplayName("build GENERIC: CRITICAL severity maps to FAILING status")
    void buildGeneric_critical_failingStatus() {
        Map<String, Object> payload = builder.build(WebhookType.GENERIC, buildEvent(ScanLog.Severity.CRITICAL));
        assertThat(payload.get("status")).isEqualTo("FAILING");
    }

    @Test
    @DisplayName("build GENERIC: HIGH severity maps to FAILING status")
    void buildGeneric_high_failingStatus() {
        Map<String, Object> payload = builder.build(WebhookType.GENERIC, buildEvent(ScanLog.Severity.HIGH));
        assertThat(payload.get("status")).isEqualTo("FAILING");
    }

    @Test
    @DisplayName("build GENERIC: MEDIUM severity maps to PASSING status")
    void buildGeneric_medium_passingStatus() {
        Map<String, Object> payload = builder.build(WebhookType.GENERIC, buildEvent(ScanLog.Severity.MEDIUM));
        assertThat(payload.get("status")).isEqualTo("PASSING");
    }

    @Test
    @DisplayName("build GENERIC: INFO severity maps to PASSING status")
    void buildGeneric_info_passingStatus() {
        Map<String, Object> payload = builder.build(WebhookType.GENERIC, buildEvent(ScanLog.Severity.INFO));
        assertThat(payload.get("status")).isEqualTo("PASSING");
    }

    // ── build SLACK ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("build SLACK: contains text and attachments")
    void buildSlack_containsTextAndAttachments() {
        Map<String, Object> payload = builder.build(WebhookType.SLACK, buildEvent(ScanLog.Severity.CRITICAL));

        assertThat(payload).containsKey("text");
        assertThat(payload).containsKey("attachments");
        assertThat(payload.get("text").toString()).contains("example.com");
    }

    @Test
    @DisplayName("build SLACK: CRITICAL has red color")
    @SuppressWarnings("unchecked")
    void buildSlack_critical_redColor() {
        Map<String, Object> payload = builder.build(WebhookType.SLACK, buildEvent(ScanLog.Severity.CRITICAL));
        var attachments = (java.util.List<Map<String, Object>>) payload.get("attachments");
        assertThat(attachments.get(0).get("color")).isEqualTo("#E53E3E");
    }

    @Test
    @DisplayName("build SLACK: INFO has green color")
    @SuppressWarnings("unchecked")
    void buildSlack_info_greenColor() {
        Map<String, Object> payload = builder.build(WebhookType.SLACK, buildEvent(ScanLog.Severity.INFO));
        var attachments = (java.util.List<Map<String, Object>>) payload.get("attachments");
        assertThat(attachments.get(0).get("color")).isEqualTo("#38A169");
    }

    // ── buildTest ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildTest GENERIC: contains test event")
    void buildTestGeneric_containsTestEvent() {
        Map<String, Object> payload = builder.buildTest(WebhookType.GENERIC);
        assertThat(payload.get("event")).isEqualTo("test");
        assertThat(payload).containsKey("sentAt");
    }

    @Test
    @DisplayName("buildTest SLACK: contains test text")
    void buildTestSlack_containsTestText() {
        Map<String, Object> payload = builder.buildTest(WebhookType.SLACK);
        assertThat(payload.get("text").toString()).contains("webhook test");
    }

    // ── toJson ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toJson serializes payload to JSON string")
    void toJson_serializesCorrectly() {
        Map<String, Object> payload = Map.of("event", "test");
        String json = builder.toJson(payload);
        assertThat(json).contains("\"event\"").contains("\"test\"");
    }
}
