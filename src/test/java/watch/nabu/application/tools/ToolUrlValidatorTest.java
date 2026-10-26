package watch.nabu.application.tools;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolUrlValidatorTest {

    private final ToolUrlValidator validator = new ToolUrlValidator("");

    @Test
    void acceptsHttpsPublicHost() {
        ToolUrlValidator.Resolved r = validator.validate("https://example.com");
        assertThat(r.uri().getHost()).isEqualTo("example.com");
        assertThat(r.address()).isNotNull();
    }

    @Test
    void rejectsDisallowedPort() {
        assertThatThrownBy(() -> validator.validate("https://example.com:6379"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> validator.validate(""))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsLocalhostHostname() {
        assertThatThrownBy(() -> validator.validate("https://localhost"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsInternalSuffix() {
        assertThatThrownBy(() -> validator.validate("https://api.internal"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> validator.validate("file:///etc/passwd"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsUrlOver2048Chars() {
        StringBuilder sb = new StringBuilder("https://example.com/");
        for (int i = 0; i < 2050; i++) sb.append('a');
        assertThatThrownBy(() -> validator.validate(sb.toString()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
