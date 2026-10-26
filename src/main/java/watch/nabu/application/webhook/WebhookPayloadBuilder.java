package watch.nabu.application.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.WebhookConfig.WebhookType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the JSON payload POSTed to webhook endpoints.
 *
 * <ul>
 *   <li>{@link WebhookType#GENERIC} — structured JSON understood by custom integrations.</li>
 *   <li>{@link WebhookType#SLACK}   — Slack incoming-webhook format with {@code text} + {@code attachments}.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WebhookPayloadBuilder {

    private final ObjectMapper objectMapper;

    private static String toMonitorStatus(ScanLog.Severity severity) {
        return switch (severity) {
            case CRITICAL, HIGH -> "FAILING";
            default -> "PASSING";
        };
    }

    public Map<String, Object> build(WebhookType type, ScanWebhookEvent event) {
        return switch (type) {
            case SLACK -> buildSlack(event);
            case GENERIC -> buildGeneric(event);
        };
    }

    public Map<String, Object> buildTest(WebhookType type) {
        return switch (type) {
            case SLACK -> buildSlackTest();
            case GENERIC -> buildGenericTest();
        };
    }

    // ── Generic ───────────────────────────────────────────────────────────────

    public String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize webhook payload", e);
        }
    }

    private Map<String, Object> buildGeneric(ScanWebhookEvent e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "scan.result");
        payload.put("scanLogId", e.scanLogId());
        payload.put("monitorId", e.monitorId());
        payload.put("monitorType", e.monitorType().name());
        payload.put("subType", e.subType().name());
        payload.put("websiteId", e.websiteId());
        payload.put("websiteUrl", e.websiteUrl());
        payload.put("organizationId", e.organizationId());
        payload.put("severity", e.severity().name());
        payload.put("status", toMonitorStatus(e.severity()));
        payload.put("scannedAt", e.scannedAt().toString());
        return payload;
    }

    // ── Slack ─────────────────────────────────────────────────────────────────

    private Map<String, Object> buildGenericTest() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "test");
        payload.put("message", "nabu-watch webhook test – if you see this, delivery is working.");
        payload.put("sentAt", Instant.now().toString());
        return payload;
    }

    private Map<String, Object> buildSlack(ScanWebhookEvent e) {
        String color = switch (e.severity()) {
            case CRITICAL -> "#E53E3E";
            case HIGH -> "#DD6B20";
            case MEDIUM -> "#D69E2E";
            default -> "#38A169";
        };
        String statusLabel = toMonitorStatus(e.severity());

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("color", color);
        attachment.put("title", "Scan result: " + e.websiteUrl());
        attachment.put("title_link", e.websiteUrl());
        attachment.put("text", String.format(
                "*Check:* %s | *Status:* %s | *Severity:* %s",
                e.subType().name(), statusLabel, e.severity().name()));
        attachment.put("footer", "nabu-watch");
        attachment.put("ts", e.scannedAt().getEpochSecond());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", ":shield: nabu-watch scan completed for *" + e.websiteUrl() + "*");
        payload.put("attachments", java.util.List.of(attachment));
        return payload;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Map<String, Object> buildSlackTest() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", ":white_check_mark: nabu-watch webhook test — delivery is working!");
        return payload;
    }
}
