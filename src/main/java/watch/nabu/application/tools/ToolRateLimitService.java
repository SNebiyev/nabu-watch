package watch.nabu.application.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.infrastructure.web.UrlUtils;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Rate-limits anonymous public-tool scans across three axes:
 * <ul>
 *   <li>per-IP per hour</li>
 *   <li>per-IP per day</li>
 *   <li>per-target-domain (cooldown to protect external sites and our worker)</li>
 * </ul>
 *
 * <p>Throws {@link ResponseStatusException} 429 with a {@code Retry-After}-style
 * detail when any limit is exceeded. The IP buckets are independent of the
 * authenticated-user limits in {@link watch.nabu.infrastructure.redis.RateLimitService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRateLimitService {

    private final StringRedisTemplate redis;

    @Value("${nabu.tools.rate-limit.per-ip-per-hour:3}")
    private long perIpPerHour;

    @Value("${nabu.tools.rate-limit.per-ip-per-day:10}")
    private long perIpPerDay;

    @Value("${nabu.tools.rate-limit.per-domain-per-10min:1}")
    private long perDomainPer10Min;

    public void checkAndIncrement(String tool, String ip, String url) {
        String safeIp = ip == null || ip.isBlank() ? "unknown" : ip;
        incrementBucket("rl:tool:ip:h:" + tool + ":" + safeIp, Duration.ofHours(1), perIpPerHour);
        incrementBucket("rl:tool:ip:d:" + tool + ":" + safeIp, Duration.ofDays(1), perIpPerDay);

        String host = UrlUtils.host(url);
        if (host != null && !host.isBlank()) {
            incrementBucket("rl:tool:dom:" + tool + ":" + host, Duration.ofMinutes(10), perDomainPer10Min);
        }
    }

    private void incrementBucket(String key, Duration window, long max) {
        Long current = redis.opsForValue().increment(key);
        if (current != null && current == 1L) {
            redis.expire(key, window.getSeconds(), TimeUnit.SECONDS);
        }
        if (current != null && current > max) {
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            long retryAfter = ttl != null && ttl > 0 ? ttl : window.getSeconds();
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded — try again in " + retryAfter + " seconds");
        }
    }
}
