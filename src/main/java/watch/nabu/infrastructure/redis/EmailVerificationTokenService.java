package watch.nabu.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed token service for the email-verification flow.
 *
 * <p>Redis key space: {@code email-verify:{token}} — value is the userId (UUID as string);
 * TTL is {@value #TTL_HOURS} hours.
 *
 * <p>Tokens are single-use: {@link #validateAndConsume} atomically deletes the key on success.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationTokenService {

    private static final int TTL_HOURS = 24;
    private static final Duration TTL = Duration.ofHours(TTL_HOURS);
    private static final String PREFIX = "email-verify:";

    private final StringRedisTemplate redis;

    // ── key helper ────────────────────────────────────────────────────────────

    private static String key(String token) {
        return PREFIX + token;
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Generates a random UUID token, stores the userId in Redis under
     * {@code email-verify:{token}} with a {@value #TTL_HOURS}-hour TTL,
     * and returns the token for inclusion in the verification link.
     *
     * <p>Any previously issued token for the same user is not invalidated
     * (each call produces an independent key). The most recently issued token
     * is the one that should be e-mailed to the user.
     *
     * @param userId UUID of the user who requested verification
     * @return the opaque token to embed in the verification URL
     */
    public String createToken(UUID userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(key(token), userId.toString(), TTL);
        log.info("[EmailVerify] Token created: userId={} ttl={}h", userId, TTL_HOURS);
        return token;
    }

    /**
     * Validates the token and, if valid, consumes it (single-use).
     *
     * @param token the token extracted from the verification URL
     * @return the userId wrapped in an Optional, or empty if the token is
     * absent (expired or never issued) or already consumed
     */
    public Optional<UUID> validateAndConsume(String token) {
        String k = key(token);
        String stored = redis.opsForValue().get(k);

        if (stored == null) {
            log.debug("[EmailVerify] Token not found or expired: token={}", token);
            return Optional.empty();
        }

        redis.delete(k); // single-use: consume immediately after retrieval
        UUID userId = UUID.fromString(stored);
        log.info("[EmailVerify] Token validated and consumed: userId={}", userId);
        return Optional.of(userId);
    }
}
