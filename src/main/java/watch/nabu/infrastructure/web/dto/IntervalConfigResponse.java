package watch.nabu.infrastructure.web.dto;

/**
 * API projection of a single {@link watch.nabu.domain.model.MonitorTypeIntervalConfig} row.
 */
public record IntervalConfigResponse(
        String subTypeCode,
        int autoScanIntervalMinutes
) {
}
