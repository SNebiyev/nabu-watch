package watch.nabu.application.admin;

import watch.nabu.domain.model.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a user record enriched with their active website count.
 *
 * <p>Constructed directly by a JPQL constructor expression in
 * {@link watch.nabu.infrastructure.persistence.UserRepository#findAllWithSiteCount}
 * so all fields are value types — no entity references, no lazy-loading risk.
 */
public record UserAdminView(
        UUID id,
        String fullName,
        String email,
        User.Role role,
        User.AccountStatus status,
        Instant createdAt,
        long siteCount,
        Instant lastLoginAt
) {}
