package watch.nabu.application.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Caches successful anonymous-tool scan results so identical URLs do not
 * re-trigger expensive scans. Keyed per-tool so different tools (e.g. SSL vs
 * cookie-banner) do not collide.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolScanCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${nabu.tools.cache.ttl-seconds:900}")
    private long ttlSeconds;

    public <T> Optional<T> get(String tool, String url, Class<T> type) {
        String key = key(tool, url);
        String json = redis.opsForValue().get(key);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            log.warn("[tools-cache] could not deserialize cached result key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String tool, String url, Object result) {
        try {
            redis.opsForValue().set(key(tool, url),
                    objectMapper.writeValueAsString(result),
                    Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            log.warn("[tools-cache] could not serialize result for url={}: {}", url, e.getMessage());
        }
    }

    private static String key(String tool, String url) {
        return "tool:cache:" + tool + ":" + url.toLowerCase();
    }
}
