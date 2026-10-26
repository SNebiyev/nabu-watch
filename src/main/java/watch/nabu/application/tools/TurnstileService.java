package watch.nabu.application.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Verifies Cloudflare Turnstile challenge tokens server-side.
 *
 * <p>When {@code nabu.tools.turnstile.enabled=false} the service short-circuits
 * to {@code true} so local development works without registering a Turnstile site.
 * Production / staging must run with the flag on and a real secret.
 */
@Slf4j
@Service
public class TurnstileService {

    private final RestTemplate restTemplate;
    private final boolean enabled;
    private final String secretKey;
    private final String verifyUrl;

    public TurnstileService(
            RestTemplate restTemplate,
            @Value("${nabu.tools.turnstile.enabled:false}") boolean enabled,
            @Value("${nabu.tools.turnstile.secret-key:}") String secretKey,
            @Value("${nabu.tools.turnstile.verify-url:https://challenges.cloudflare.com/turnstile/v0/siteverify}") String verifyUrl) {
        this.restTemplate = restTemplate;
        this.enabled = enabled;
        this.secretKey = secretKey;
        this.verifyUrl = verifyUrl;
        if (enabled && (secretKey == null || secretKey.isBlank())) {
            log.warn("[turnstile] enabled=true but secret-key is empty — verifications will fail closed");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Verifies the token with Cloudflare. Returns true on success, false on any
     * failure (network error, invalid token, missing config). Errors are logged
     * but never bubbled up — the caller should treat false as "challenge failed".
     */
    public boolean verify(String token, String remoteIp) {
        if (!enabled) {
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        if (secretKey == null || secretKey.isBlank()) {
            return false;
        }

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", secretKey);
            form.add("response", token);
            if (remoteIp != null && !remoteIp.isBlank()) {
                form.add("remoteip", remoteIp);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.postForObject(
                    verifyUrl, new HttpEntity<>(form, headers), Map.class);

            if (body == null) {
                return false;
            }
            Object success = body.get("success");
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            log.warn("[turnstile] verification call failed: {}", e.getMessage());
            return false;
        }
    }
}
