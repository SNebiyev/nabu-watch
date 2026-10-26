package watch.nabu.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed rate limiting for authentication endpoints.
 * Prevents brute force attacks on login, registration, and password reset.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRateLimitService {

    private static final int LOGIN_MAX_ATTEMPTS = 5;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(5);
    private static final int REGISTER_MAX_ATTEMPTS = 3;
    private static final Duration REGISTER_WINDOW = Duration.ofHours(1);
    private static final int RESET_MAX_ATTEMPTS = 3;
    private static final Duration RESET_WINDOW = Duration.ofMinutes(15);
    private static final int VERIFY_RESEND_MAX = 1;
    private static final Duration VERIFY_RESEND_WINDOW = Duration.ofMinutes(2);
    private static final int ACCOUNT_LOCK_THRESHOLD = 5;
    private static final Duration ACCOUNT_LOCK_DURATION = Duration.ofMinutes(15);
    private final StringRedisTemplate redis;

    public void checkLoginRate(String ipAddress) {
        checkRate("rl:auth:login:" + ipAddress, LOGIN_MAX_ATTEMPTS, LOGIN_WINDOW,
                "Too many login attempts. Please try again in 5 minutes.");
    }

    public void checkRegisterRate(String ipAddress) {
        checkRate("rl:auth:register:" + ipAddress, REGISTER_MAX_ATTEMPTS, REGISTER_WINDOW,
                "Too many registration attempts. Please try again later.");
    }

    public void checkResetRate(String email) {
        checkRate("rl:auth:reset:" + email.toLowerCase(), RESET_MAX_ATTEMPTS, RESET_WINDOW,
                "Too many password reset requests. Please try again in 15 minutes.");
    }

    public void checkVerifyResendRate(String email) {
        checkRate("rl:auth:verify:" + email.toLowerCase(), VERIFY_RESEND_MAX, VERIFY_RESEND_WINDOW,
                "Verification email already sent. Please wait 2 minutes before requesting again.");
    }

    public void recordFailedLogin(String email) {
        String key = "rl:auth:failed:" + email.toLowerCase();
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, ACCOUNT_LOCK_DURATION);
        }
    }

    public void checkAccountLock(String email) {
        String key = "rl:auth:failed:" + email.toLowerCase();
        String val = redis.opsForValue().get(key);
        if (val != null && Integer.parseInt(val) >= ACCOUNT_LOCK_THRESHOLD) {
            throw new AuthRateLimitException(
                    "Account temporarily locked due to too many failed login attempts. Please try again in 15 minutes.");
        }
    }

    public void clearFailedAttempts(String email) {
        redis.delete("rl:auth:failed:" + email.toLowerCase());
    }

    private void checkRate(String key, int maxAttempts, Duration window, String message) {
        // Atomic increment-then-check: avoids race condition where concurrent
        // requests all read the same count before any increment lands.
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, window);
        }
        if (count != null && count > maxAttempts) {
            throw new AuthRateLimitException(message);
        }
    }
}
