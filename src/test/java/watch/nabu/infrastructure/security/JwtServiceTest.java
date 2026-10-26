package watch.nabu.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "nabu-watch-test-secret-key-must-be-at-least-32-chars!!";
    private static final long EXPIRATION_MS = 86_400_000; // 24h

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, EXPIRATION_MS);
    }

    private UserDetails buildUser(String email, String role) {
        return new User(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    // ── Token generation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("generateToken: produces a non-null JWT string")
    void generateToken_producesNonNull() {
        UserDetails user = buildUser("test@example.com", "USER");
        String token = jwtService.generateToken(user);
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("generateToken: subject matches email")
    void generateToken_subjectMatchesEmail() {
        UserDetails user = buildUser("alice@example.com", "USER");
        String token = jwtService.generateToken(user);
        assertThat(jwtService.extractSubject(token)).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("generateToken: role claim is set without ROLE_ prefix")
    void generateToken_roleWithoutPrefix() {
        UserDetails user = buildUser("admin@example.com", "ADMIN");
        String token = jwtService.generateToken(user);
        assertThat(jwtService.extractRole(token)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("generateToken: orgId claim is embedded when provided")
    void generateToken_orgIdEmbedded() {
        UserDetails user = buildUser("test@example.com", "USER");
        UUID orgId = UUID.randomUUID();
        String token = jwtService.generateToken(user, orgId);
        assertThat(jwtService.extractOrganizationId(token)).isEqualTo(orgId);
    }

    @Test
    @DisplayName("generateToken: orgId is null when not provided")
    void generateToken_orgIdNull() {
        UserDetails user = buildUser("test@example.com", "USER");
        String token = jwtService.generateToken(user);
        assertThat(jwtService.extractOrganizationId(token)).isNull();
    }

    @Test
    @DisplayName("generateToken: pwdChangeRequired claim set when flag is true")
    void generateToken_pwdChangeRequired() {
        UserDetails user = buildUser("test@example.com", "USER");
        String token = jwtService.generateToken(user, true, null);
        assertThat(jwtService.isPasswordChangeRequired(token)).isTrue();
    }

    @Test
    @DisplayName("generateToken: pwdChangeRequired absent when flag is false")
    void generateToken_pwdChangeNotRequired() {
        UserDetails user = buildUser("test@example.com", "USER");
        String token = jwtService.generateToken(user, false, null);
        assertThat(jwtService.isPasswordChangeRequired(token)).isFalse();
    }

    // ── Token validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("isValid: returns true for valid token with matching user")
    void isValid_validToken_returnsTrue() {
        UserDetails user = buildUser("test@example.com", "USER");
        String token = jwtService.generateToken(user);
        assertThat(jwtService.isValid(token, user)).isTrue();
    }

    @Test
    @DisplayName("isValid: returns false when subject doesn't match")
    void isValid_wrongSubject_returnsFalse() {
        UserDetails user = buildUser("test@example.com", "USER");
        UserDetails other = buildUser("other@example.com", "USER");
        String token = jwtService.generateToken(user);
        assertThat(jwtService.isValid(token, other)).isFalse();
    }

    @Test
    @DisplayName("isValid: returns false for expired token")
    void isValid_expiredToken_returnsFalse() {
        // Create a service with 0ms expiration
        JwtService shortLived = new JwtService(SECRET, 0);
        UserDetails user = buildUser("test@example.com", "USER");
        String token = shortLived.generateToken(user);
        // Token is already expired
        assertThat(shortLived.isValid(token, user)).isFalse();
    }

    @Test
    @DisplayName("isValid: returns false for malformed token")
    void isValid_malformedToken_returnsFalse() {
        UserDetails user = buildUser("test@example.com", "USER");
        assertThat(jwtService.isValid("not.a.valid.jwt", user)).isFalse();
    }

    @Test
    @DisplayName("isValid: returns false for token signed with different key")
    void isValid_differentKey_returnsFalse() {
        JwtService otherService = new JwtService(
                "another-secret-key-that-is-at-least-32-characters-long!!", EXPIRATION_MS);
        UserDetails user = buildUser("test@example.com", "USER");
        String token = otherService.generateToken(user);
        assertThat(jwtService.isValid(token, user)).isFalse();
    }

    // ── Expiry ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("expiryOf: returns future expiry for fresh token")
    void expiryOf_futureExpiry() {
        UserDetails user = buildUser("test@example.com", "USER");
        String token = jwtService.generateToken(user);
        Instant expiry = jwtService.expiryOf(token);
        assertThat(expiry).isAfter(Instant.now());
    }

    // ── Role extraction edge case ─────────────────────────────────────────────

    @Test
    @DisplayName("extractRole: handles USER role correctly")
    void extractRole_userRole() {
        UserDetails user = buildUser("user@example.com", "USER");
        String token = jwtService.generateToken(user);
        assertThat(jwtService.extractRole(token)).isEqualTo("USER");
    }

    // ── secret validation (fail fast, no default secret) ─────────────────────

    @Test
    void constructor_rejectsMissingOrBlankSecret() {
        assertThatThrownBy(() -> new JwtService(null, EXPIRATION_MS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtService("", EXPIRATION_MS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtService("   ", EXPIRATION_MS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_rejectsSecretShorterThan32Chars() {
        assertThatThrownBy(() -> new JwtService("x".repeat(31), EXPIRATION_MS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void constructor_acceptsSecretOfExactly32Chars() {
        assertThat(new JwtService("x".repeat(32), EXPIRATION_MS)).isNotNull();
    }
}
