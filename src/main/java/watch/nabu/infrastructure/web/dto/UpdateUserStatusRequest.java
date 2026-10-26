package watch.nabu.infrastructure.web.dto;

/**
 * Request body for {@code PATCH /api/v1/admin/users/{id}/status}.
 * {@code status} must be a valid {@link watch.nabu.domain.model.User.AccountStatus}
 * name: {@code ACTIVE} or {@code SUSPENDED}.
 */
public record UpdateUserStatusRequest(String status) {
}
