package watch.nabu.infrastructure.web.dto;

import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Website;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * HTTP response body for {@code POST /api/v1/websites}.
 *
 * <p>Monitors are grouped by their current scan status so the caller can see
 * at a glance which legal pages are already passing and which are pending a
 * first scan or failing.
 *
 * <p>At creation time every monitor is in {@code "PENDING"} because no scan
 * has run yet.  Subsequent calls to {@code GET /api/v1/websites/{id}/health}
 * return live statuses.
 *
 * <p>Status values:
 * <ul>
 *   <li>{@code PENDING}  — monitor created but no scan result yet</li>
 *   <li>{@code PASSING}  — latest scan was successful / compliant</li>
 *   <li>{@code FAILING}  — latest scan reported a problem</li>
 * </ul>
 */
public record WebsiteResponse(
    UUID                           id,
    String                         url,
    String                         regionCode,
    String                         status,
    Instant                        createdAt,
    Map<String, List<MonitorInfo>> monitorsByStatus,
    /**
     * {@code true}  — an existing website was found and its monitors were synced;
     *                  the HTTP response will be {@code 200 OK}.
     * {@code false} — a new website was created;
     *                  the HTTP response will be {@code 201 Created}.
     */
    boolean                        updated
) {

    /**
     * Per-monitor summary inside the creation response.
     *
     * @param monitorId       identity of the monitor
     * @param monitorType     broad category: SSL or COMPLIANCE
     * @param subType         specific check: SSL, IMPRESSUM, PRIVACY_POLICY, …
     * @param effectiveUrl    URL the worker will actually scan
     * @param targetUrlSource {@code "MANUAL"} when caller supplied an explicit
     *                        target URL; {@code "AUTO"} when the website URL is used
     */
    public record MonitorInfo(
        UUID   monitorId,
        String monitorType,
        String subType,
        String effectiveUrl,
        String targetUrlSource
    ) {
        public static MonitorInfo from(Monitor monitor) {
            boolean manual = monitor.getTargetUrl() != null
                          && !monitor.getTargetUrl().isBlank();
            return new MonitorInfo(
                monitor.getId(),
                monitor.getType().name(),
                monitor.getSubType().name(),
                manual ? monitor.getTargetUrl() : monitor.getWebsite().getUrl(),
                manual ? "MANUAL" : "AUTO"
            );
        }
    }

    /**
     * Builds a creation response ({@code updated = false}).
     * All monitors are classified as {@code "PENDING"} because no scan has run yet.
     */
    public static WebsiteResponse from(Website website, List<Monitor> monitors) {
        return from(website, monitors, false);
    }

    /**
     * Builds a response with explicit {@code updated} flag.
     * Used by the sync path to signal 200 OK rather than 201 Created.
     */
    public static WebsiteResponse from(Website website, List<Monitor> monitors, boolean updated) {
        Map<String, List<MonitorInfo>> grouped = monitors.stream()
            .map(MonitorInfo::from)
            .collect(Collectors.groupingBy(m -> "PENDING"));

        return new WebsiteResponse(
            website.getId(),
            website.getUrl(),
            website.getRegionCode(),
            website.getStatus().name(),
            website.getCreatedAt(),
            grouped,
            updated
        );
    }
}
