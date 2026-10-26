package watch.nabu.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import watch.nabu.application.exception.DomainCooldownException;
import watch.nabu.application.exception.UserRateLimitException;
import watch.nabu.application.ratelimit.RateLimitPort;

import watch.nabu.infrastructure.web.UrlUtils;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit; // used by checkDomainCooldown's getExpire call

/**
 * Redis-backed implementation of {@link RateLimitPort}.
 *
 * <p>Two Redis key spaces are used:
 * <ul>
 *   <li>{@code rl:domain:{hostname}} — TTL set by {@code nabu.scan.domain-cooldown-minutes};
 *       prevents hammering the same external domain faster than the cooldown allows.</li>
 *   <li>{@code rl:user:{userId}:rescan} — TTL 60 s; sliding counter;
 *       caps manual rescans at 5 per minute per user.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RateLimitService implements RateLimitPort {

    static final long USER_WINDOW_SECONDS = 60L;
    static final long USER_MAX_REQUESTS = 5L;

    /**
     * API budget window: a 60-second sliding cap of 600 requests per principal
     * (10/s). Sized to be invisible during normal dashboard interaction but
     * trip immediately on a runaway script or scraping abuse.
     */
    static final long API_BUDGET_WINDOW_SECONDS = 60L;
    static final long API_BUDGET_MAX_REQUESTS = 600L;

    private final StringRedisTemplate redis;

    private static String extractHost(String websiteUrl) {
        String h = UrlUtils.host(websiteUrl);
        return h != null ? h : websiteUrl;
    }

    @Override
    public void checkDomainCooldown(String websiteUrl) {
        String host = extractHost(websiteUrl);
        Long ttl = redis.getExpire("rl:domain:" + host, TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            throw new DomainCooldownException(host, ttl);
        }
    }

    @Override
    public void setDomainCooldown(String websiteUrl, Duration cooldown) {
        String host = extractHost(websiteUrl);
        redis.opsForValue().set("rl:domain:" + host, "1", cooldown);
    }

    @Override
    public void checkUserRateLimit(UUID userId) {
        String key = "rl:user:" + userId + ":rescan";
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(USER_WINDOW_SECONDS));
        }
        if (count != null && count > USER_MAX_REQUESTS) {
            throw new UserRateLimitException();
        }
    }

    @Override
    public long getDomainCooldownRemaining(String websiteUrl) {
        String host = extractHost(websiteUrl);
        Long ttl = redis.getExpire("rl:domain:" + host, TimeUnit.SECONDS);
        return (ttl != null && ttl > 0) ? ttl : 0;
    }

    @Override
    public void checkUserApiBudget(String principalEmail) {
        if (principalEmail == null || principalEmail.isBlank()) return;
        String key = "rl:api:user:" + principalEmail;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(API_BUDGET_WINDOW_SECONDS));
        }
        if (count != null && count > API_BUDGET_MAX_REQUESTS) {
            throw new UserRateLimitException();
        }
    }

}
