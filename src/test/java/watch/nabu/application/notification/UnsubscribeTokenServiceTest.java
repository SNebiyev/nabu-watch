package watch.nabu.application.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UnsubscribeTokenServiceTest {

    private final UnsubscribeTokenService service =
            new UnsubscribeTokenService("test-jwt-secret-key-that-is-long-enough", "https://app.nabu.test");

    @Test
    @DisplayName("generate → validate round-trip for alerts type")
    void roundTripAlerts() {
        UUID userId = UUID.randomUUID();
        String token = service.generateToken(userId, "alerts");

        UnsubscribeTokenService.ParsedToken parsed = service.validate(token);

        assertThat(parsed).isNotNull();
        assertThat(parsed.userId()).isEqualTo(userId);
        assertThat(parsed.type()).isEqualTo("alerts");
    }

    @Test
    @DisplayName("generate → validate round-trip for digest type")
    void roundTripDigest() {
        UUID userId = UUID.randomUUID();
        String token = service.generateToken(userId, "digest");

        UnsubscribeTokenService.ParsedToken parsed = service.validate(token);

        assertThat(parsed).isNotNull();
        assertThat(parsed.userId()).isEqualTo(userId);
        assertThat(parsed.type()).isEqualTo("digest");
    }

    @Test
    @DisplayName("tampered token is rejected")
    void tamperedTokenRejected() {
        UUID userId = UUID.randomUUID();
        String token = service.generateToken(userId, "alerts");

        // Flip one character in the signature
        String tampered = token.substring(0, token.length() - 1) + "X";

        assertThat(service.validate(tampered)).isNull();
    }

    @Test
    @DisplayName("invalid format is rejected")
    void invalidFormatRejected() {
        assertThat(service.validate("")).isNull();
        assertThat(service.validate("not-a-valid-token")).isNull();
        assertThat(service.validate(null)).isNull();
    }

    @Test
    @DisplayName("unknown type is rejected")
    void unknownTypeRejected() {
        UUID userId = UUID.randomUUID();
        // Manually craft a token with an unsupported type
        String token = service.generateToken(userId, "alerts");
        // Replace "alerts" with "sms" in the token
        String modified = token.replace(":alerts:", ":sms:");

        assertThat(service.validate(modified)).isNull();
    }

    @Test
    @DisplayName("different secrets produce different tokens")
    void differentSecretsProduceDifferentTokens() {
        UnsubscribeTokenService other =
                new UnsubscribeTokenService("different-secret-key-that-is-long-enough", "https://other.test");
        UUID userId = UUID.randomUUID();

        String token1 = service.generateToken(userId, "alerts");
        String token2 = other.generateToken(userId, "alerts");

        assertThat(token1).isNotEqualTo(token2);
        // Token from one service should not validate with the other
        assertThat(other.validate(token1)).isNull();
    }

    @Test
    @DisplayName("buildUnsubscribeUrl includes base URL and token")
    void buildUrlFormat() {
        UUID userId = UUID.randomUUID();
        String url = service.buildUnsubscribeUrl(userId, "digest");

        assertThat(url).startsWith("https://app.nabu.test/unsubscribe?token=");
        // Extract token and validate
        String token = url.substring(url.indexOf("token=") + 6);
        assertThat(service.validate(token)).isNotNull();
    }
}
