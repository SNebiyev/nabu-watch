package watch.nabu.infrastructure.web.dto;

import watch.nabu.domain.model.Monitor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for monitor-level endpoints (e.g. {@code PATCH /api/v1/monitors/{id}}).
 *
 * @param monitorId       identity of the monitor
 * @param websiteId       the website this monitor belongs to
 * @param monitorType     SSL, COMPLIANCE, etc.
 * @param effectiveUrl    the URL the worker will scan (targetUrl if set, else website URL)
 * @param targetUrlSource {@code "MANUAL"} when an explicit target URL is configured;
 *                        {@code "AUTO"} when falling back to the website's primary URL
 * @param intervalMinutes how often this monitor runs
 * @param lastRunAt       timestamp of the most recent scan; {@code null} if never run
 * @param createdAt       when this monitor was created
 * @param createdByEmail  email of the user who created this monitor; {@code null} for legacy monitors
 */
public record MonitorResponse(
    UUID    monitorId,
    UUID    websiteId,
    String  monitorType,
    String  effectiveUrl,
    String  targetUrlSource,
    int     intervalMinutes,
    Instant lastRunAt,
    Instant createdAt,
    String createdByEmail
) {
    public static MonitorResponse from(Monitor monitor) {
        boolean manual = monitor.getTargetUrl() != null
                      && !monitor.getTargetUrl().isBlank();
        return new MonitorResponse(
            monitor.getId(),
            monitor.getWebsite().getId(),
            monitor.getType().name(),
            manual ? monitor.getTargetUrl() : monitor.getWebsite().getUrl(),
            manual ? "MANUAL" : "AUTO",
            monitor.getIntervalMinutes(),
                monitor.getLastRunAt(),
                monitor.getCreatedAt(),
                monitor.getCreatedBy() != null ? monitor.getCreatedBy().getEmail() : null
        );
    }
}
