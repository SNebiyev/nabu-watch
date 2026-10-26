package watch.nabu.application.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

/**
 * Generates and validates HMAC-SHA256 unsubscribe tokens.
 *
 * <p>Token format: {@code base64url(userId):type:base64url(hmac)}.
 * No database state required — the token is self-contained and
 * deterministic for a given (userId, type, secret) triple.
 *
 * <p>Supported types: {@code alerts} (per-scan severity alerts),
 * {@code digest} (weekly summary), {@code lifecycle} (transactional
 * site-state emails — abuse upheld/removed/restored, DNS verification
 * expired, expiry warning).
 */
@Service
public class UnsubscribeTokenService {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final byte[] secretBytes;
    private final String baseUrl;

    public UnsubscribeTokenService(
            @Value("${nabu.jwt.secret}") String jwtSecret,
            @Value("${nabu.mail.base-url:http://localhost:3000}") String baseUrl) {
        this.secretBytes = ("unsubscribe:" + jwtSecret).getBytes(StandardCharsets.UTF_8);
        this.baseUrl = baseUrl;
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates an unsubscribe token for the given user and notification type.
     *
     * @param userId user's UUID
     * @param type   one of {@code "alerts"} or {@code "digest"}
     * @return the token string
     */
    public String generateToken(UUID userId, String type) {
        String payload = userId.toString() + ":" + type;
        String sig = sign(payload);
        return base64Url(userId.toString()) + ":" + type + ":" + sig;
    }

    /**
     * Builds the full one-click unsubscribe URL.
     */
    public String buildUnsubscribeUrl(UUID userId, String type) {
        return baseUrl + "/unsubscribe?token=" + generateToken(userId, type);
    }

    /**
     * Validates a token and extracts the user ID and type.
     *
     * @return parsed result, or {@code null} if the token is invalid
     */
    public ParsedToken validate(String token) {
        if (token == null || token.isBlank()) return null;
        String[] parts = token.split(":", 3);
        if (parts.length != 3) return null;

        String userIdStr;
        try {
            userIdStr = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String type = parts[1];
        if (!"alerts".equals(type) && !"digest".equals(type) && !"lifecycle".equals(type)) return null;

        UUID userId;
        try {
            userId = UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String expectedSig = sign(userId.toString() + ":" + type);
        if (!expectedSig.equals(parts[2])) return null;

        return new ParsedToken(userId, type);
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGO));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    public record ParsedToken(UUID userId, String type) {
    }
}
