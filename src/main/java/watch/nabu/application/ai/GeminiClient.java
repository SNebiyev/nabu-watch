package watch.nabu.application.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Thin Gemini Generative Language REST client. Mirrors the pattern used by
 * the Go worker's {@code banner_ai_gemini.go} — POSTs a single prompt and
 * returns the text of the first candidate.
 *
 * <p>When {@code nabu.ai.gemini.api-key} is blank the client reports
 * unavailable and callers should short-circuit with a 503.
 */
@Slf4j
@Service
public class GeminiClient {

    private static final int MAX_OUTPUT_TOKENS = 600;
    private static final double TEMPERATURE = 0.2;
    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public GeminiClient(
            RestTemplate restTemplate,
            @Value("${nabu.ai.gemini.api-key:}") String apiKey,
            @Value("${nabu.ai.gemini.model:gemini-3.1-flash-lite-preview}") String model,
            @Value("${nabu.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta/models}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Send {@code prompt} to Gemini and return the generated text. Returns
     * {@code null} on any failure so callers can decide between surfacing an
     * error and falling back to static copy.
     */
    public String generate(String prompt) {
        if (!isAvailable()) return null;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", Map.of(
                        "maxOutputTokens", MAX_OUTPUT_TOKENS,
                        "temperature", TEMPERATURE
                )
        );

        String url = baseUrl + "/" + model + ":generateContent";
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), RESPONSE_TYPE);
            return extractText(resp.getBody());
        } catch (RestClientException e) {
            log.warn("[gemini] request failed: {}", e.getMessage());
            return null;
        }
    }

    private static String extractText(Map<String, Object> payload) {
        if (payload == null) return null;
        Object candidates = payload.get("candidates");
        if (!(candidates instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) return null;
        Object content = firstMap.get("content");
        if (!(content instanceof Map<?, ?> contentMap)) return null;
        Object parts = contentMap.get("parts");
        if (!(parts instanceof List<?> partsList) || partsList.isEmpty()) return null;
        Object partZero = partsList.get(0);
        if (!(partZero instanceof Map<?, ?> partMap)) return null;
        Object text = partMap.get("text");
        return text == null ? null : text.toString();
    }
}
