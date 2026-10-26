package watch.nabu.application.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import watch.nabu.application.exception.MaliciousPayloadException;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageSecurityValidatorTest {

    private final MessageSecurityValidator validator = new MessageSecurityValidator();

    // ── Safe content ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("validate: null and empty content passes")
    void validate_nullAndEmpty_passes(String content) {
        assertThatNoException().isThrownBy(() -> validator.validate(content));
    }

    @Test
    @DisplayName("validate: blank content passes")
    void validate_blank_passes() {
        assertThatNoException().isThrownBy(() -> validator.validate("   "));
    }

    @Test
    @DisplayName("validate: normal text passes")
    void validate_normalText_passes() {
        assertThatNoException().isThrownBy(() ->
                validator.validate("Hello, this is a normal message about privacy policy."));
    }

    // ── XSS patterns ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert('xss')</script>",
            "<SCRIPT>alert('xss')</SCRIPT>",
            "<script src='evil.js'>",
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "<img onerror=alert(1)>",
            "onclick=doSomething()",
            "<iframe src='evil'>",
            "eval(document.cookie)",
            "<object data='evil'>",
            "<embed src='evil'>",
            "data:text/html,<script>",
            "document.cookie",
            "document.write('xss')",
            "window.location='evil'"
    })
    @DisplayName("validate: XSS patterns are rejected")
    void validate_xssPatterns_rejected(String content) {
        assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOf(MaliciousPayloadException.class);
    }

    // ── SQLi patterns ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "' OR 1=1",
            "' AND x=x",
            "UNION SELECT * FROM users",
            "UNION ALL SELECT 1,2,3",
            "; DROP TABLE users ",
            "; DELETE FROM sessions ",
            "; INSERT INTO admins ",
            "; ALTER TABLE users ",
            "; TRUNCATE TABLE data ",
            "; CREATE TABLE evil ",
            "; EXEC xp_cmdshell ",
            "-- comment",
            "/* block comment",
            "0x414243",
            "CHAR(65)"
    })
    @DisplayName("validate: SQLi patterns are rejected")
    void validate_sqliPatterns_rejected(String content) {
        assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOf(MaliciousPayloadException.class);
    }

    // ── Truncation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validate: long malicious content still detected (truncation only affects logs)")
    void validate_longMaliciousContent_stillDetected() {
        String longContent = "x".repeat(200) + "<script>alert(1)</script>";
        assertThatThrownBy(() -> validator.validate(longContent))
                .isInstanceOf(MaliciousPayloadException.class);
    }
}
