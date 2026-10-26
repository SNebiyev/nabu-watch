package watch.nabu.infrastructure.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Single item in the {@code GET /api/v1/admin/users} response list.
 *
 * <p>Password hashes and internal JPA fields are deliberately excluded.
 *
 * @param id          user UUID
 * @param fullName    display name
 * @param email       login email
 * @param role        {@code "USER"} or {@code "ADMIN"}
 * @param status      account status string (e.g. {@code "ACTIVE"}, {@code "SUSPENDED"})
 * @param createdAt   UTC timestamp of account creation
 * @param siteCount   number of non-deleted websites owned by this user
 * @param lastLoginAt UTC timestamp of most-recent login; {@code null} if never logged in
 */
public record UserSummaryItem(
        UUID    id,
        String  fullName,
        String  email,
        String  role,
        String  status,
        Instant createdAt,
        long siteCount,
        Instant lastLoginAt
) {}
