package watch.nabu.application.tools;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TurnstileServiceTest {

    @Test
    void disabledServiceAlwaysReturnsTrue() {
        TurnstileService svc = new TurnstileService(
                mock(RestTemplate.class), false, "", "https://example/verify");
        assertThat(svc.verify(null, null)).isTrue();
        assertThat(svc.verify("", null)).isTrue();
        assertThat(svc.verify("any-token", "1.2.3.4")).isTrue();
    }

    @Test
    void enabledRejectsBlankToken() {
        TurnstileService svc = new TurnstileService(
                mock(RestTemplate.class), true, "secret", "https://example/verify");
        assertThat(svc.verify("", "1.2.3.4")).isFalse();
        assertThat(svc.verify(null, "1.2.3.4")).isFalse();
    }

    @Test
    void enabledRejectsWhenSecretMissing() {
        TurnstileService svc = new TurnstileService(
                mock(RestTemplate.class), true, "", "https://example/verify");
        assertThat(svc.verify("token", "1.2.3.4")).isFalse();
    }
}
