package watch.nabu.infrastructure.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Provides {@link RestTemplate} beans for outbound HTTP calls.
 *
 * <p>Kept separate from {@link watch.nabu.infrastructure.security.SecurityConfig}
 * to avoid a circular dependency:
 * {@code SecurityConfig → CustomOAuth2UserService → RestTemplate → SecurityConfig}.
 *
 * <p>The primary bean has a long read timeout because Gemini generation can take tens of seconds.
 * The GitHub bean is short (5s) because it runs inline on every GitHub login.
 */
@Configuration
public class RestTemplateConfig {

    public static final String GITHUB_REST_TEMPLATE = "githubRestTemplate";

    @Bean
    @Primary
    public RestTemplate restTemplate() {
        return build(Duration.ofSeconds(5), Duration.ofSeconds(60));
    }

    @Bean(GITHUB_REST_TEMPLATE)
    public RestTemplate githubRestTemplate() {
        return build(Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    private static RestTemplate build(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connect.toMillis());
        factory.setReadTimeout((int) read.toMillis());
        return new RestTemplate(factory);
    }
}
