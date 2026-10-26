package watch.nabu.infrastructure.web.dto;

import watch.nabu.domain.model.Monitor;

import java.util.UUID;

/**
 * Lightweight monitor descriptor returned by {@code GET /api/v1/websites/{id}/monitors}.
 * Used by the Settings modal to display and edit per-monitor target URLs.
 *
 * @param monitorId the monitor's identity
 * @param type      {@code SSL} or {@code COMPLIANCE}
 * @param subType   fine-grained classifier (e.g. {@code PRIVACY_POLICY}, {@code SSL})
 * @param targetUrl the manually configured scan target, or {@code null} when in AUTO mode
 * @param active    whether this monitor is currently enabled
 */
public record MonitorConfigItem(
        UUID monitorId,
        String type,
        String subType,
        String targetUrl,
        boolean active
) {
    public static MonitorConfigItem from(Monitor monitor) {
        return new MonitorConfigItem(
                monitor.getId(),
                monitor.getType().name(),
                monitor.getSubType().name(),
                monitor.getTargetUrl(),
                monitor.isActive()
        );
    }
}
