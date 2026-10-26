package watch.nabu.infrastructure.web.dto;

import java.util.List;

/**
 * Paginated response for {@code GET /api/v1/websites}.
 *
 * <p>{@code stats} always reflects the <em>full</em> result set that matches
 * the URL search filter — it is computed before the status/monitor filter is
 * applied, so the KPI cards remain stable while the user drills down.
 */
public record WebsitePageResponse(
        List<WebsiteHealthListItem> content,
        long totalElements,
        int totalPages,
        int page,
        int size,
        Stats stats
) {

    public static WebsitePageResponse empty(int page, int size) {
        return of(List.of(), 0, page, size, new Stats(0, 0, 0, 0, 0, 0, 0, 0));
    }

    public static WebsitePageResponse of(
            List<WebsiteHealthListItem> content,
            long totalElements,
            int page,
            int size,
            Stats stats) {
        int pages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return new WebsitePageResponse(content, totalElements, pages, page, size, stats);
    }

    public record Stats(
            int totalSites,
            int failingSites,
            int passingSites,
            int pendingSites,
            int sslIssues,
            int complianceIssues,
            int recoveredCount,
            int degradedCount
    ) {
    }
}
