package watch.nabu.infrastructure.web.dto;

import java.util.Map;

/**
 * Full scan detail returned by {@code GET /api/v1/admin/scans/{scanLogId}}.
 * Includes the raw {@code resultJson} blob so the frontend can render
 * compliance keyword chips and SSL certificate fields inside the drawer.
 */
public record AdminScanDetailResponse(
        String scanLogId,
        String monitorId,
        String websiteId,
        String websiteUrl,
        String monitorType,
        String subType,
        String severity,
        String scannedAt,
        Map<String, Object> resultJson,
        /** How this scan was triggered: {@code "MANUAL"} or {@code "SCHEDULED"}; {@code null} for legacy rows. */
        String triggerType,
        /** Email of the user who triggered this scan; {@code null} for scheduled scans. */
        String triggeredByEmail
) {
}
