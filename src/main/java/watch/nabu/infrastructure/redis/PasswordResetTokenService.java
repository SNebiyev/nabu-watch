package watch.nabu.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed token service for the password-reset flow.
 *
 * <p>Redis key space: {@code pwd-reset:{token}} — value is the userId (UUID as string);
 * TTL is {@value #TTL_HOURS} hour(s).
 *
 * <p>Tokens are single-use: {@link #validateAndConsume} atomically deletes the key on success.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetTokenService {

    private static final int TTL_HOURS = 1;
    private static final Duration TTL = Duration.ofHours(TTL_HOURS);
    private static final String PREFIX = "pwd-reset:";

    private final StringRedisTemplate redis;

    // ── key helper ────────────────────────────────────────────────────────────

    private static String key(String token) {
        return PREFIX + token;
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Generates a random UUID token, stores the userId in Redis under
     * {@code pwd-reset:{token}} with a {@value #TTL_HOURS}-hour TTL,
     * and returns the token for inclusion in the reset link.
     *
     * <p>Any previously issued token for the same user is not invalidated
     * (each call produces an independent key). The most recently issued token
     * is the one that should be e-mailed to the user.
     *
     * @param userId UUID of the user who requested the password reset
     * @return the opaque token to embed in the reset URL
     */
    public String createToken(UUID userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(key(token), userId.toString(), TTL);
        log.info("[PasswordReset] Token created: userId={} ttl={}h", userId, TTL_HOURS);
        return token;
    }

    /**
     * Validates the token and, if valid, consumes it (single-use).
     *
     * @param token the token extracted from the reset URL
     * @return the userId wrapped in an Optional, or empty if the token is
     * absent (expired or never issued) or already consumed
     */
    public Optional<UUID> validateAndConsume(String token) {
        String k = key(token);
        String stored = redis.opsForValue().get(k);

        if (stored == null) {
            log.debug("[PasswordReset] Token not found or expired: token={}", token);
            return Optional.empty();
        }

        redis.delete(k); // single-use: consume immediately after retrieval
        UUID userId = UUID.fromString(stored);
        log.info("[PasswordReset] Token validated and consumed: userId={}", userId);
        return Optional.of(userId);
    }
}
