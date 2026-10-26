package watch.nabu.infrastructure.web.dto;

import java.util.List;

/**
 * Paginated response from GET /api/v1/admin/reports.
 */
public record ReportedScanPageResponse(
        List<ReportedScanItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
