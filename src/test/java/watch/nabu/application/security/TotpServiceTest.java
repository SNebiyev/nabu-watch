package watch.nabu.application.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TotpService — no mocks needed, tests real TOTP library behaviour.
 */
class TotpServiceTest {

    private final TotpService totpService = new TotpService();

    // ── generateSecret ────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateSecret returns non-null, non-blank Base32 string")
    void generateSecret_returnsNonBlankBase32() {
        String secret = totpService.generateSecret();

        assertThat(secret).isNotBlank();
        // Base32 alphabet: A-Z, 2-7 only
        assertThat(secret).matches("[A-Z2-7]+");
    }

    @Test
    @DisplayName("generateSecret returns different values on successive calls")
    void generateSecret_isDifferentEachTime() {
        String s1 = totpService.generateSecret();
        String s2 = totpService.generateSecret();

        assertThat(s1).isNotEqualTo(s2);
    }

    // ── getQrUri ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getQrUri builds correct otpauth:// URI containing email and secret")
    void getQrUri_containsEmailAndSecret() {
        String uri = totpService.getQrUri("user@example.com", "TESTSECRET");

        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("TESTSECRET");
        assertThat(uri).contains("user%40example.com");
        assertThat(uri).contains("issuer=Nabu+Watch");
        assertThat(uri).contains("digits=6");
        assertThat(uri).contains("period=30");
    }

    // ── generateQrPngBase64 ───────────────────────────────────────────────────

    @Test
    @DisplayName("generateQrPngBase64 returns non-null Base64-encoded PNG for a valid URI")
    void generateQrPngBase64_validUri_returnsBase64() {
        String secret = totpService.generateSecret();
        String uri = totpService.getQrUri("user@example.com", secret);

        String base64 = totpService.generateQrPngBase64(uri);

        assertThat(base64).isNotNull();
        // Valid Base64: only A-Z, a-z, 0-9, +, /, and = padding
        assertThat(base64).matches("[A-Za-z0-9+/]+=*");
    }

    // ── isCodeValid ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("isCodeValid with null code → false")
    void isCodeValid_nullCode_returnsFalse() {
        assertThat(totpService.isCodeValid("SECRET", null)).isFalse();
    }

    @Test
    @DisplayName("isCodeValid with code shorter than 6 digits → false")
    void isCodeValid_shortCode_returnsFalse() {
        assertThat(totpService.isCodeValid("SECRET", "12345")).isFalse();
    }

    @Test
    @DisplayName("isCodeValid with code longer than 6 digits → false")
    void isCodeValid_longCode_returnsFalse() {
        assertThat(totpService.isCodeValid("SECRET", "1234567")).isFalse();
    }

    @Test
    @DisplayName("isCodeValid with non-numeric code → false")
    void isCodeValid_nonNumericCode_returnsFalse() {
        assertThat(totpService.isCodeValid("SECRET", "abcdef")).isFalse();
    }

    @Test
    @DisplayName("isCodeValid with obviously wrong numeric code → false")
    void isCodeValid_wrongNumericCode_returnsFalse() {
        // Use a real secret but an obviously wrong code; probability of collision is 1/1000000
        String secret = totpService.generateSecret();
        assertThat(totpService.isCodeValid(secret, "000000")).isFalse();
    }
}
