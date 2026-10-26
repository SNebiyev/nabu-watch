package watch.nabu.infrastructure.web.dto;

/**
 * A single entry in the real-time scan activity feed.
 *
 * <p>The field names intentionally mirror the SSE {@code scan-result} event
 * payload produced by {@code ScanStreamController} so that the frontend can
 * parse both the SSE stream and the REST polling fallback
 * ({@code GET /api/v1/scans/recent}) with identical logic.
 */
public record ScanActivityItem(
        String scanLogId,
        String monitorId,
        String websiteId,
        String websiteUrl,
        String monitorType,
        String subType,
        String severity,
        String scannedAt
) {
}
