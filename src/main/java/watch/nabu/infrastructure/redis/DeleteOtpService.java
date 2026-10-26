package watch.nabu.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import watch.nabu.application.exception.OtpDeliveryException;
import watch.nabu.application.exception.OtpExpiredException;
import watch.nabu.application.messaging.Message;
import watch.nabu.application.messaging.MessageDispatcher;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed one-time password service for the secure-delete flow.
 *
 * <p>Redis key space: {@code otp:del:{websiteId}:{adminId}} — value is a
 * 6-digit numeric string; TTL is {@value #TTL_MINUTES} minutes.
 *
 * <p><b>Email delivery</b>: {@code generate} sends the code to the admin by email via
 * the configured {@link MessageDispatcher}. The code itself is never logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeleteOtpService {

    private static final int TTL_MINUTES = 10;
    private static final Duration TTL = Duration.ofMinutes(TTL_MINUTES);
    private static final SecureRandom RNG = new SecureRandom();

    private final StringRedisTemplate redis;
    private final MessageDispatcher messageDispatcher;

    // ── key helper ────────────────────────────────────────────────────────────

    private static String key(UUID websiteId, UUID adminId) {
        return "otp:del:" + websiteId + ":" + adminId;
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Generates a fresh 6-digit OTP, stores it in Redis with a
     * {@value #TTL_MINUTES}-minute TTL, and "delivers" it to the admin.
     *
     * <p>The previous code (if any) for this {@code (websiteId, adminId)} pair
     * is atomically overwritten — clicking "Resend" always yields a new code.
     *
     * @param websiteId  UUID of the website the admin intends to delete
     * @param adminId    UUID of the requesting admin
     * @param adminEmail email address to which the code should be delivered
     * @throws OtpDeliveryException if the email could not be dispatched
     */
    public void generate(UUID websiteId, UUID adminId, String adminEmail) {
        String code = String.format("%06d", RNG.nextInt(1_000_000));
        redis.opsForValue().set(key(websiteId, adminId), code, TTL);

        String subject = "Your confirmation code";
        String text = "Your website deletion confirmation code is " + code
                + ". It expires in " + TTL_MINUTES + " minutes. If you did not request it, ignore this email.";
        String html = "<p>Your website deletion confirmation code is <strong>"
                + HtmlUtils.htmlEscape(code) + "</strong>.</p><p>It expires in " + TTL_MINUTES
                + " minutes. If you did not request it, ignore this email.</p>";
        try {
            messageDispatcher.dispatch(Message.email(adminEmail, subject, html, text));
        } catch (Exception ex) {
            log.warn("[DeleteOTP] Could not send code email: websiteId={} admin={} error={}",
                    websiteId, adminEmail, ex.getMessage());
            redis.delete(key(websiteId, adminId)); // the admin never received it; do not keep a dangling code
            throw new OtpDeliveryException(ex);
        }
        log.info("[DeleteOTP] Code generated: websiteId={} admin={} ttl={}m", websiteId, adminEmail, TTL_MINUTES);
    }

    /**
     * Verifies the submitted code against the stored value.
     * On success the Redis key is immediately deleted (single-use).
     *
     * @throws OtpExpiredException if the key is absent (expired or never generated)
     *                             or the submitted code does not match
     */
    public void verify(UUID websiteId, UUID adminId, String submitted) {
        String k = key(websiteId, adminId);
        String stored = redis.opsForValue().get(k);

        if (stored == null || !stored.equals(submitted)) {
            throw new OtpExpiredException();
        }

        redis.delete(k); // single-use: consume after successful verification
        log.info("[DeleteOTP] Code verified and consumed: websiteId={} adminId={}", websiteId, adminId);
    }
}
