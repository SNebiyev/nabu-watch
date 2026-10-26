package watch.nabu.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import watch.nabu.application.onboarding.OnboardingService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomOAuth2UserServiceTest {

    private static final String URL = "https://api.github.com/user/emails";

    @Mock private OnboardingService onboardingService;
    @Mock private RestTemplate restTemplate;
    @Mock private OAuth2UserRequest userRequest;
    @Mock private OAuth2User oAuth2User;

    private CustomOAuth2UserService service;

    @BeforeEach
    void setUp() {
        service = new CustomOAuth2UserService(onboardingService, restTemplate);
    }

    private void stubToken() {
        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "tok", java.time.Instant.now(),
                java.time.Instant.now().plusSeconds(60));
        when(userRequest.getAccessToken()).thenReturn(token);
    }

    private void stubEmails(GitHubEmailApiResponse... entries) {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(GitHubEmailApiResponse[].class))).thenReturn(ResponseEntity.ok(entries));
    }

    @Test
    @DisplayName("profile email listed as verified in /user/emails is used (lower-cased)")
    void profileEmailVerified_used() {
        stubToken();
        when(oAuth2User.getAttribute("email")).thenReturn("Dev@Example.com");
        stubEmails(new GitHubEmailApiResponse("dev@example.com", false, true));

        assertThat(service.resolveEmail(userRequest, oAuth2User, "github", "7")).isEqualTo("dev@example.com");
    }

    @Test
    @DisplayName("unverified profile email is ignored, verified primary is used")
    void profileEmailUnverified_usesVerifiedPrimary() {
        stubToken();
        when(oAuth2User.getAttribute("email")).thenReturn("victim@example.com");
        stubEmails(new GitHubEmailApiResponse("victim@example.com", false, false),
                new GitHubEmailApiResponse("me@example.com", true, true));

        assertThat(service.resolveEmail(userRequest, oAuth2User, "github", "7")).isEqualTo("me@example.com");
    }

    @Test
    @DisplayName("no verified email at all falls back to the noreply address")
    void noVerifiedEmail_noreply() {
        stubToken();
        when(oAuth2User.getAttribute("email")).thenReturn("victim@example.com");
        when(oAuth2User.getAttribute("login")).thenReturn("octo");
        stubEmails(new GitHubEmailApiResponse("victim@example.com", true, false));

        assertThat(service.resolveEmail(userRequest, oAuth2User, "github", "7"))
                .isEqualTo("7+octo@users.noreply.github.com");
    }

    @Test
    @DisplayName("/user/emails failure falls back to noreply without failing the login")
    void emailsCallFails_noreply() {
        stubToken();
        when(oAuth2User.getAttribute("email")).thenReturn("victim@example.com");
        when(oAuth2User.getAttribute("login")).thenReturn("octo");
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(GitHubEmailApiResponse[].class))).thenThrow(new ResourceAccessException("timeout"));

        assertThat(service.resolveEmail(userRequest, oAuth2User, "github", "7"))
                .isEqualTo("7+octo@users.noreply.github.com");
    }
}
