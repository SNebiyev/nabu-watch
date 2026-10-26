package watch.nabu.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Stateless JWT utility: generate, parse, and validate HS256-signed tokens.
 *
 * <p>Standard token claims:
 * <ul>
 *   <li>{@code sub}              — user email (the Spring Security username)</li>
 *   <li>{@code role}             — user role without the {@code ROLE_} prefix (e.g. {@code "ADMIN"})</li>
 *   <li>{@code orgId}            — current organization UUID</li>
 *   <li>{@code iat}              — issued-at timestamp</li>
 *   <li>{@code exp}              — expiry timestamp</li>
 * </ul>
 *
 * <p>Conditional claim (only present when {@code true}):
 * <ul>
 *   <li>{@code pwdChangeRequired} — the user must change their password before accessing
 *       any protected feature; the frontend should redirect to the change-password page.</li>
 * </ul>
 */
@Slf4j
@Service
public class JwtService {

    /** Minimum HS256 secret length in characters (256 bits). */
    static final int MIN_SECRET_LENGTH = 32;

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${nabu.jwt.secret:}") String secret,
            @Value("${nabu.jwt.expiration-ms}") long expirationMs) {
        requireValidSecret(secret);
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * Fails fast at startup when the signing secret is missing or too short. No default
     * secret ships with the code: every installation must provide its own.
     */
    static void requireValidSecret(String secret) {
        if (secret == null || secret.isBlank() || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT_SECRET is missing or shorter than " + MIN_SECRET_LENGTH + " characters. "
                            + "Set the JWT_SECRET environment variable to a random value, "
                            + "e.g. generate one with: openssl rand -base64 48");
        }
    }

    // ── token generation ─────────────────────────────────────────────────────

    /**
     * Generates a standard token without orgId.  Delegates to
     * {@link #generateToken(UserDetails, boolean, UUID)} with
     * {@code requirePasswordChange = false} and {@code orgId = null}.
     */
    public String generateToken(UserDetails userDetails) {
        return generateToken(userDetails, false, null);
    }

    /**
     * Generates a token with the given orgId.
     */
    public String generateToken(UserDetails userDetails, UUID orgId) {
        return generateToken(userDetails, false, orgId);
    }

    /**
     * Generates a token embedding the user's current organization and, optionally,
     * a {@code pwdChangeRequired} claim.
     *
     * @param userDetails           Spring Security principal
     * @param requirePasswordChange when {@code true} the frontend must redirect to change-password
     * @param orgId                 the user's active organization; embedded as {@code orgId} claim
     */
    public String generateToken(UserDetails userDetails, boolean requirePasswordChange, UUID orgId) {
        Instant now    = Instant.now();
        Instant expiry = now.plusMillis(expirationMs);

        // Extract role name (strip "ROLE_" prefix added by NabuUserDetailsService)
        String role = userDetails.getAuthorities().stream()
            .findFirst()
            .map(a -> a.getAuthority().replace("ROLE_", ""))
            .orElse("USER");

        var builder = Jwts.builder()
            .subject(userDetails.getUsername())
            .claim("role", role)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry));

        if (orgId != null) {
            builder.claim("orgId", orgId.toString());
        }

        if (requirePasswordChange) {
            builder.claim("pwdChangeRequired", true);
        }

        return builder.signWith(signingKey).compact();
    }

    public Instant expiryOf(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    /** Extracts the {@code role} claim (e.g. {@code "ADMIN"}, {@code "USER"}). */
    public String extractRole(String token) {
        return (String) parseClaims(token).get("role");
    }

    /**
     * Returns {@code true} when the token carries the {@code pwdChangeRequired}
     * claim — i.e. the user must change their password before proceeding.
     */
    public boolean isPasswordChangeRequired(String token) {
        return Boolean.TRUE.equals(parseClaims(token).get("pwdChangeRequired", Boolean.class));
    }

    /**
     * Extracts the {@code orgId} claim (the user's active organization).
     * Returns {@code null} if the claim is absent (legacy tokens).
     */
    public UUID extractOrganizationId(String token) {
        String orgId = (String) parseClaims(token).get("orgId");
        return orgId != null ? UUID.fromString(orgId) : null;
    }

    // ── token validation ─────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the token is structurally valid, the signature
     * is correct, and the subject matches the given {@link UserDetails}.
     */
    public boolean isValid(String token, UserDetails userDetails) {
        try {
            String subject = extractSubject(token);
            return subject.equals(userDetails.getUsername()) && !isExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    public String extractSubject(String token) {
        return parseClaims(token).getSubject();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private boolean isExpired(String token) {
        return parseClaims(token).getExpiration().before(new Date());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
