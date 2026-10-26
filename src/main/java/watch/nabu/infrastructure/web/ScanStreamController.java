package watch.nabu.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import watch.nabu.application.scan.ScanResultEvent;
import watch.nabu.application.scan.WebsiteBatchCompleteEvent;
import watch.nabu.application.scan.WebsiteStatusChangedEvent;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.infrastructure.persistence.ScanLogRepository;
import watch.nabu.infrastructure.web.dto.ScanActivityItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Streams real-time scan results to authenticated clients via Server-Sent Events.
 *
 * <p>Connect with:
 * <pre>
 *   GET /api/v1/scans/stream
 *   Authorization: Bearer &lt;token&gt;
 *   Accept: text/event-stream
 * </pre>
 *
 * <p>Each SSE event has:
 * <ul>
 *   <li>{@code event: scan-result}</li>
 *   <li>{@code data: <JSON payload>}</li>
 * </ul>
 *
 * <p>A keep-alive {@code event: ping} comment is sent on connection to flush
 * proxy buffers.  Clients should reconnect on disconnect (browsers do this
 * automatically with the native {@code EventSource} API).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/scans")
@RequiredArgsConstructor
public class ScanStreamController {

    /** Emitter timeout — 30 minutes; clients reconnect automatically. */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1_000L;

    private final SseEmitterRegistry registry;
    private final ScanLogRepository scanLogRepository;
    private final SecurityContextService securityContext;

    // ─── HTTP endpoints ───────────────────────────────────────────────────────

    /**
     * GET /api/v1/scans/recent
     *
     * <p>Returns the 10 most-recent scan results for any website belonging to
     * the authenticated user.  Intended as a polling fallback when the SSE
     * stream is unavailable (e.g. in environments that buffer chunked responses).
     *
     * <p>The response shape mirrors the SSE {@code scan-result} event payload
     * so the frontend can use identical parsing logic for both transports.
     */
    @GetMapping("/recent")
    public List<ScanActivityItem> recent(@AuthenticationPrincipal UserDetails principal) {
        UUID orgId = securityContext.resolveOrgId(principal.getUsername());
        return scanLogRepository
                .findRecentByOrganizationId(orgId, PageRequest.of(0, 10))
                .stream()
                .map(sl -> new ScanActivityItem(
                        sl.getId().toString(),
                        sl.getMonitor().getId().toString(),
                        sl.getMonitor().getWebsite().getId().toString(),
                        sl.getMonitor().getWebsite().getUrl(),
                        sl.getMonitor().getType().name(),
                        sl.getMonitor().getSubType().name(),
                        sl.getSeverity().name(),
                        sl.getCreatedAt().toString()))
                .toList();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request) {
        // orgId was set by JwtAuthenticationFilter from the JWT claims
        UUID orgId = (UUID) request.getAttribute("orgId");
        SseEmitter emitter = registry.register(EMITTER_TIMEOUT_MS, orgId);

        // Send an initial ping so the client knows the connection is live
        try {
            emitter.send(SseEmitter.event()
                .comment("connected")
                .name("ping")
                .data(""));
        } catch (Exception ex) {
            log.debug("Could not send initial ping: {}", ex.getMessage());
        }

        return emitter;
    }

    // ─── Event listener ───────────────────────────────────────────────────────

    /**
     * Maps a scan severity to a frontend-friendly monitor status string.
     * CRITICAL / HIGH mean the scan found a hard failure; anything else is PASSING.
     */
    private static String toMonitorStatus(ScanLog.Severity severity) {
        if (severity == null) return "PASSING";
        return switch (severity) {
            case CRITICAL, HIGH -> "FAILING";
            default -> "PASSING";
        };
    }

    /**
     * Triggered after the database transaction that persisted the ScanLog has
     * fully committed, guaranteeing clients see consistent data.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScanResult(ScanResultEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scanLogId",   event.getScanLogId());
        payload.put("monitorId",   event.getMonitorId());
        payload.put("websiteId",   event.getWebsiteId());
        payload.put("websiteUrl",  event.getWebsiteUrl());
        payload.put("monitorType", event.getMonitorType());
        payload.put("subType",     event.getSubType());
        payload.put("severity",    event.getSeverity());
        payload.put("status", toMonitorStatus(event.getSeverity()));
        payload.put("scannedAt",   event.getScannedAt());

        log.debug("Broadcasting scan-result SSE: orgId={} monitorId={} severity={} status={}",
                event.getOrganizationId(), event.getMonitorId(), event.getSeverity(), payload.get("status"));

        registry.broadcast(event.getOrganizationId(), SseEmitter.event()
            .name("scan-result")
            .data(payload, MediaType.APPLICATION_JSON));
    }

    /**
     * Triggered after all monitors in a manual rescan batch have reported and
     * their results have been committed.  Broadcasts a {@code website-updated}
     * event so dashboard tables can refetch the affected row without waiting for
     * a manual page refresh.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWebsiteBatchComplete(WebsiteBatchCompleteEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("websiteId", event.getWebsiteId());
        payload.put("websiteUrl", event.getWebsiteUrl());

        log.debug("Broadcasting website-updated SSE: orgId={} websiteId={}",
                event.getOrganizationId(), event.getWebsiteId());

        registry.broadcast(event.getOrganizationId(), SseEmitter.event()
                .name("website-updated")
                .data(payload, MediaType.APPLICATION_JSON));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Triggered after every individual scan result is committed — scheduled or
     * manual, batch or standalone.  Broadcasts a {@code website-updated} event
     * so Matrix/dashboard tables refetch the affected row in real time.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWebsiteStatusChanged(WebsiteStatusChangedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("websiteId", event.getWebsiteId());
        payload.put("websiteUrl", event.getWebsiteUrl());
        payload.put("complianceGrade", event.getComplianceGrade());
        payload.put("complianceScore", event.getComplianceScore());

        log.debug("Broadcasting website-updated SSE (status changed): orgId={} websiteId={}",
                event.getOrganizationId(), event.getWebsiteId());

        registry.broadcast(event.getOrganizationId(), SseEmitter.event()
                .name("website-updated")
                .data(payload, MediaType.APPLICATION_JSON));
    }
}
