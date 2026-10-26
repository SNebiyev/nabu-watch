package watch.nabu.infrastructure.web.dto;

import java.util.List;

/**
 * Paginated wrapper for the admin user directory.
 * Returned by {@code GET /api/v1/admin/users?page={p}&size={s}}.
 */
public record UserPageResponse(
        List<UserSummaryItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
