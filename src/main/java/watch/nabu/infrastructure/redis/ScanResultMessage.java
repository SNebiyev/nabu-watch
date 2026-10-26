package watch.nabu.infrastructure.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * Deserialized form of a message from the {@code nabu:scan-results} Redis Stream.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScanResultMessage(

    @JsonProperty("task_id")
    String taskId,

    @JsonProperty("monitor_id")
    String monitorId,

    @JsonProperty("status")
    String status,

    @JsonProperty("payload")
    Map<String, String> payload,

    @JsonProperty("published_at")
    Instant publishedAt,

    @JsonProperty("batch_id")
    String batchId,

    @JsonProperty("raw_dom")
    String rawDom

) {
    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }
}
