package watch.nabu.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import watch.nabu.application.scan.*;
import watch.nabu.infrastructure.persistence.MonitorRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Listens to the {@code nabu:scan-results} Redis Stream under consumer group
 * {@code api-result-group} and delegates each message to {@link ScanResultProcessor}.
 *
 * <p>After {@link ScanResultProcessor#process} commits its transaction, this
 * consumer publishes a {@link ScanResultEvent} so that other parts of the
 * application (e.g. Server-Sent Events) can react in real time.
 *
 * <p>ACK strategy: messages are always ACKed in a {@code finally} block so that
 * a malformed or unprocessable message never blocks the consumer group.
 *
 * <p>Race-condition retry: if the processor cannot find the referenced monitor on
 * the first attempt (e.g. the CREATE transaction has not yet committed), the
 * consumer waits {@value RACE_CONDITION_RETRY_MS} ms and tries exactly once more.
 * If the monitor is still absent, the result is discarded (the monitor was deleted
 * after the task was dispatched) and the Redis message is ACKed normally so the
 * stream is never stuck.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    static final String STREAM_KEY = "nabu:scan-results";
    static final String GROUP_NAME = "api-result-group";

    /**
     * How long to wait (in ms) before retrying after a monitor-not-found result,
     * to cover the window between task dispatch and the monitor's CREATE commit.
     */
    private static final long RACE_CONDITION_RETRY_MS = 150L;

    private final ScanResultProcessor processor;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final watch.nabu.infrastructure.web.SseEmitterRegistry sseRegistry;
    private final MonitorRepository monitorRepository;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        String msgId = record.getId().getValue();

        try {
            String json = record.getValue().get("result");

            log.info("Received message from Redis: msgId={}", msgId);

            if (json == null || json.isBlank()) {
                log.warn("Scan-result message missing 'result' field: msgId={}", msgId);
                return;
            }

            ScanResultMessage message = objectMapper.readValue(json, ScanResultMessage.class);

            log.info("Parsed scan result: msgId={} monitorId={} status={}",
                msgId, message.monitorId(), message.status());

            // Skipped results (WAF/transient/batch-abort) return Optional.empty()
            // from process(), which the retry loop below would misinterpret as
            // "monitor not found". Handle them before the retry loop.
            String status = message.status();
            if ("SKIPPED_WAF".equalsIgnoreCase(status)
                    || "SKIPPED_TRANSIENT".equalsIgnoreCase(status)
                    || "SKIPPED_BATCH_ABORT".equalsIgnoreCase(status)) {
                processor.process(message);
                // Broadcast SSE directly — @TransactionalEventListener may not fire
                // reliably for skipped results since no ScanLog is persisted.
                if ("SKIPPED_BATCH_ABORT".equalsIgnoreCase(status)) {
                    broadcastWebsiteUpdateForMonitor(message.monitorId());
                }
                log.info("Skipped result processed ({}): msgId={} monitorId={}",
                        status, msgId, message.monitorId());
                return; // ACK in finally
            }

            // process() runs inside @Transactional — returns a summary once committed,
            // or Optional.empty() if the monitor was not found in the DB.
            Optional<ScanResultSummary> result = processor.process(message);

            if (result.isEmpty()) {
                // The monitor was absent on first attempt.  Wait briefly and retry
                // once to cover a potential commit race condition (task dispatched just
                // before the monitor's INSERT committed on another thread).
                log.debug("Monitor not found on first attempt: msgId={} monitorId={} — "
                    + "waiting {}ms before retry",
                    msgId, message.monitorId(), RACE_CONDITION_RETRY_MS);

                try {
                    Thread.sleep(RACE_CONDITION_RETRY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted during race-condition retry wait: msgId={}", msgId);
                    return; // ACK in finally — do not retry on interrupt
                }

                result = processor.process(message);
            }

            if (result.isEmpty()) {
                // Monitor is physically gone (or was so before we were invoked).
                // The processor already logged a WARN; discard and ACK so the
                // stream is never blocked waiting for a deleted resource.
                log.debug("Discarding result after retry: msgId={} monitorId={}",
                    msgId, message.monitorId());
                return; // ACK in finally
            }

            ScanResultSummary summary = result.get();

            // Publish the event AFTER the transaction has committed so that
            // listeners annotated with @TransactionalEventListener(AFTER_COMMIT)
            // see a fully consistent database state.
            eventPublisher.publishEvent(new ScanResultEvent(this, summary));

            log.info("ScanResultEvent published: monitorId={} subType={} severity={}",
                summary.monitorId(), summary.subType(), summary.severity());

        } catch (Exception ex) {
            log.error("Failed to process scan result message: msgId={} error={}",
                msgId, ex.getMessage(), ex);
        } finally {
            ack(msgId);
        }
    }

    /**
     * Broadcasts a website-updated SSE event for the monitor's website.
     * Used by batch-abort skips where @TransactionalEventListener may not fire.
     */
    private void broadcastWebsiteUpdateForMonitor(String monitorId) {
        try {
            monitorRepository.findById(UUID.fromString(monitorId)).ifPresent(monitor -> {
                var website = monitor.getWebsite();
                var orgId = website.getOrganization().getId();
                sseRegistry.broadcast(orgId, SseEmitter.event()
                        .name("website-updated")
                        .data(Map.of(
                                        "websiteId", website.getId(),
                                        "websiteUrl", website.getUrl()),
                                MediaType.APPLICATION_JSON));
            });
        } catch (Exception ex) {
            log.debug("SSE broadcast failed for batch-abort skip: monitorId={} error={}",
                    monitorId, ex.getMessage());
        }
    }

    private void ack(String msgId) {
        try {
            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, msgId);
        } catch (Exception ex) {
            log.error("XACK failed: msgId={} error={}", msgId, ex.getMessage());
        }
    }
}
