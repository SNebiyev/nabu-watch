package watch.nabu.infrastructure.web.dto;

/**
 * Lightweight website summary shown in the admin user-details drawer.
 * Returned by {@code GET /api/v1/admin/users/{id}/websites}.
 */
public record UserWebsiteItem(
        String id,
        String url,
        String region,
        String status   // ACTIVE | PAUSED | DELETED
) {
}
