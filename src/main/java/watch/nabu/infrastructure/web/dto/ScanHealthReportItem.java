package watch.nabu.infrastructure.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A single row in the admin Scan Health Report.
 * Represents the latest scan result for one monitor on one website.
 */
public record ScanHealthReportItem(
        UUID websiteId,
        String websiteUrl,
        String region,
        UUID monitorId,
        String subType,
        String severity,
        Instant scannedAt,
        UUID scanLogId,
        String status,
        String summary
) {
}
