package watch.nabu.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Configures the Redis Stream listener for {@code nabu:scan-results}.
 *
 * <p>Bootstrap order:
 * <ol>
 *   <li>{@link #resultStreamContainer} is instantiated by Spring.</li>
 *   <li>Inside that {@code @Bean} method, the consumer group is created (idempotent).</li>
 *   <li>The subscription is registered on the container.</li>
 *   <li>Spring calls {@code container.start()} as part of {@code SmartLifecycle} refresh.</li>
 * </ol>
 * This ensures the group always exists before the first {@code XREADGROUP} is issued.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisStreamConfig {

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate    stringRedisTemplate;

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> resultStreamContainer(
            ResultConsumer resultConsumer) {

        ensureConsumerGroup();

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
            StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofSeconds(1)) // Must be less than spring.data.redis.timeout
                .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
            StreamMessageListenerContainer.create(connectionFactory, options);

        container.receive(
            Consumer.from(ResultConsumer.GROUP_NAME, consumerName()),
            StreamOffset.create(ResultConsumer.STREAM_KEY, ReadOffset.lastConsumed()),
            resultConsumer
        );

        // StreamMessageListenerContainer.isAutoStartup() returns false — Spring will NOT
        // start the container automatically, so we must call start() explicitly here.
        container.start();

        log.info("Result stream container started: stream={} group={} consumer={}",
            ResultConsumer.STREAM_KEY, ResultConsumer.GROUP_NAME, consumerName());

        return container;
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    /**
     * Creates the consumer group using the raw {@code XGROUP CREATE ... MKSTREAM} command
     * so the stream is initialised atomically even before the Go worker has published
     * its first message.
     *
     * <p>The high-level {@code StreamOperations.createGroup()} does NOT pass MKSTREAM,
     * and its exception message is always the opaque {@code "Error in execution"} —
     * inspecting it to detect "no such key" is unreliable.  Using {@link RedisCallback}
     * with {@code mkStream=true} sidesteps both problems in one call.
     */
    private void ensureConsumerGroup() {
        try {
            RedisCallback<Object> createGroup = conn ->
                conn.streamCommands().xGroupCreate(
                    ResultConsumer.STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                    ResultConsumer.GROUP_NAME,
                    ReadOffset.from("0"),
                    true   // MKSTREAM — creates the stream if it does not yet exist
                );
            stringRedisTemplate.execute(createGroup);
            log.info("Consumer group created: stream={} group={}",
                ResultConsumer.STREAM_KEY, ResultConsumer.GROUP_NAME);
        } catch (Exception ex) {
            // Traverse the cause chain — Spring wraps the raw Redis reply
            Throwable cause = ex;
            while (cause.getCause() != null) cause = cause.getCause();
            String rootMsg = cause.getMessage() != null ? cause.getMessage() : "";

            if (rootMsg.contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists — skipping creation",
                    ResultConsumer.GROUP_NAME);
            } else {
                throw ex;   // connection refused, auth error, etc. — fail fast
            }
        }
    }

    /**
     * Unique consumer name per JVM instance: {@code nabu-api-<hostname>-<pid>}.
     * Ensures no two replicas share a consumer name in the same group.
     */
    private static String consumerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        return "nabu-api-" + host + "-" + ProcessHandle.current().pid();
    }
}
