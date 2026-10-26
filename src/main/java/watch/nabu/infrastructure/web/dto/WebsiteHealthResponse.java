package watch.nabu.infrastructure.web.dto;

import watch.nabu.application.website.WebsiteHealthView;
import watch.nabu.domain.model.WebsiteHealth.HealthColor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * HTTP response body for {@code GET /api/v1/websites/{id}/health}.
 *
 * <p>Monitors are grouped by scan status ({@code PASSING} / {@code FAILING} /
 * {@code PENDING}) so the caller can see at a glance exactly which legal pages
 * are passing and which need attention.
 */
public record WebsiteHealthResponse(
        UUID                           websiteId,
        String                         url,
        String                         regionCode,
        HealthColor                    overallStatus,
        Map<String, List<MonitorSummary>> monitorsByStatus,
        Instant evaluatedAt,
        String complianceGrade,
        Integer complianceScore
) {

    /**
     * @param monitorId      identity of the monitor
     * @param monitorType    broad category (SSL, COMPLIANCE)
     * @param subType        specific check (SSL, IMPRESSUM, PRIVACY_POLICY, …)
     * @param severity       severity of the latest scan; {@code null} if never run
     * @param lastCheckedAt  timestamp of the latest scan; {@code null} if never run
     * @param summary        human-readable one-liner
     */
    public record MonitorSummary(
            UUID    monitorId,
            String  monitorType,
            String  subType,
            String  severity,
            Instant lastCheckedAt,
            String  summary
    ) {}

    public static WebsiteHealthResponse from(WebsiteHealthView view) {
        Map<String, List<MonitorSummary>> grouped = view.monitors().stream()
                .map(m -> new MonitorSummary(
                        m.monitorId(),
                        m.monitorType().name(),
                        m.subType().name(),
                        m.severity() != null ? m.severity().name() : null,
                        m.lastCheckedAt(),
                        m.summary()
                ))
                .collect(Collectors.groupingBy(ms -> scanStatus(ms.severity(), ms.lastCheckedAt())));

        return new WebsiteHealthResponse(
                view.websiteId(),
                view.url(),
                view.regionCode(),
                view.color(),
                grouped,
                view.evaluatedAt(),
                view.complianceGrade(),
                view.complianceScore()
        );
    }

    /**
     * Maps a monitor's latest scan data to one of three status buckets.
     *
     * <ul>
     *   <li>{@code PENDING}  — no scan has run yet ({@code lastCheckedAt == null})</li>
     *   <li>{@code FAILING}  — severity is CRITICAL, HIGH, or MEDIUM</li>
     *   <li>{@code PASSING}  — severity is INFO or LOW</li>
     * </ul>
     */
    private static String scanStatus(String severity, Instant lastCheckedAt) {
        if (lastCheckedAt == null) return "PENDING";
        return switch (severity) {
            case "CRITICAL", "HIGH", "MEDIUM" -> "FAILING";
            default                            -> "PASSING";
        };
    }
}
