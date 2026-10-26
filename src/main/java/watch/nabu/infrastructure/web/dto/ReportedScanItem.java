package watch.nabu.infrastructure.web.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row in the admin reports table, returned by GET /api/v1/admin/reports.
 */
public record ReportedScanItem(
        UUID scanLogId,
        String websiteUrl,
        String orgName,
        String monitorType,
        String subType,
        String severity,
        Instant scannedAt,
        /** Email of the user who submitted the report; null if the account was deleted. */
        String reporterEmail,
        String notes,
        Instant reportedAt,
        /** Full scan result at the time of the report, for admin review. */
        Map<String, Object> resultJson
) {}
