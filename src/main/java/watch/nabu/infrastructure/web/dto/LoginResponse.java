package watch.nabu.infrastructure.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code POST /api/v1/auth/login}.
 *
 * @param token                 the signed JWT — include in subsequent requests as
 *                              {@code Authorization: Bearer <token>}
 * @param tokenType             always {@code "Bearer"}
 * @param expiresAt             absolute UTC timestamp when the token expires
 * @param userId                the authenticated user's UUID
 * @param role                  the authenticated user's role ({@code USER} or {@code ADMIN})
 * @param requirePasswordChange when {@code true} the frontend must redirect the user
 *                              to the change-password page before allowing further
 *                              navigation.  The same flag is embedded in the JWT as the
 *                              {@code pwdChangeRequired} claim for stateless checks.
 */
public record LoginResponse(
    String  token,
    String  tokenType,
    Instant expiresAt,
    UUID    userId,
    String  role,
    boolean requirePasswordChange
) {}
