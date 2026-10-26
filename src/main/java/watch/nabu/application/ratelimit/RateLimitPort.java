package watch.nabu.application.ratelimit;

import java.time.Duration;
import java.util.UUID;

/**
 * Port for rate-limiting operations.
 * Implemented by {@link watch.nabu.infrastructure.redis.RateLimitService}.
 */
public interface RateLimitPort {

    /**
     * Checks whether the domain extracted from {@code websiteUrl} is currently
     * in its post-scan cooldown window.
     *
     * @throws watch.nabu.application.exception.DomainCooldownException when cooldown is active
     */
    void checkDomainCooldown(String websiteUrl);

    /**
     * Sets the cooldown key for the domain extracted from {@code websiteUrl}
     * with the given TTL.
     *
     * @param cooldown the duration for this domain's cooldown (from {@code nabu.scan.domain-cooldown-minutes})
     */
    void setDomainCooldown(String websiteUrl, Duration cooldown);

    /**
     * Increments the per-user rescan counter and enforces the 5/min rate limit.
     *
     * @throws watch.nabu.application.exception.UserRateLimitException when the limit is exceeded
     */
    void checkUserRateLimit(UUID userId);

    /**
     * Returns the remaining domain cooldown in seconds for the given website URL.
     * Returns 0 if no cooldown is active.
     */
    long getDomainCooldownRemaining(String websiteUrl);

    /**
     * Anti-abuse safety net on authenticated API calls: caps any single
     * principal (identified by email — stable for the duration of a JWT) at
     * a high request rate that real UI usage cannot reach but a runaway
     * script or compromised credential trivially would.
     *
     * @throws watch.nabu.application.exception.UserRateLimitException when exceeded
     */
    void checkUserApiBudget(String principalEmail);
}
