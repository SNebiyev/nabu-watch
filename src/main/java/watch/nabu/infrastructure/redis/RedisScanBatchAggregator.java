package watch.nabu.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import watch.nabu.application.scan.ScanBatchAggregatorPort;
import watch.nabu.application.scan.ScanSummaryData;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.ScanStatusChange;

import java.time.Duration;
import java.util.*;

/**
 * Redis-backed implementation of {@link ScanBatchAggregatorPort}.
 *
 * <h3>Key layout (TTL: 1 hour each)</h3>
 * <pre>
 *   batch:{batchId}:meta       → hash   websiteId, websiteUrl, userId, triggerType, expected
 *   batch:{batchId}:results    → hash   {SubType} → {Severity}
 *   nabu:batch_completion:{id} → string countdown counter (starts at expectedCount, DECR to 0)
 * </pre>
 *
 * <p>Completion detection uses a single atomic {@code DECR} on the dedicated
 * string counter key.  The caller whose DECR returns {@code 0} wins the
 * "batch complete" signal — no non-atomic HINCRBY+HGET pair, no race with TTL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisScanBatchAggregator implements ScanBatchAggregatorPort {

    private static final Duration BATCH_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    private static String metaKey(UUID batchId) {
        return "batch:" + batchId + ":meta";
    }

    private static String resultsKey(UUID batchId) {
        return "batch:" + batchId + ":results";
    }

    private static String completionKey(UUID batchId) {
        return "nabu:batch_completion:" + batchId;
    }

    @Override
    public void registerBatch(UUID batchId, int expectedCount, UUID websiteId,
                              String websiteUrl, UUID userId, String triggerType) {
        String metaKey = metaKey(batchId);
        Map<String, String> meta = new HashMap<>();
        meta.put("websiteId", websiteId.toString());
        meta.put("websiteUrl", websiteUrl != null ? websiteUrl : "");
        meta.put("userId", userId.toString());
        meta.put("triggerType", triggerType != null ? triggerType : "MANUAL");
        meta.put("expected", String.valueOf(expectedCount));

        redis.opsForHash().putAll(metaKey, meta);
        redis.expire(metaKey, BATCH_TTL);

        // Atomic countdown counter: starts at expectedCount, each recordResult
        // decrements by 1.  The caller whose DECR returns 0 owns the summary.
        redis.opsForValue().set(completionKey(batchId), String.valueOf(expectedCount), BATCH_TTL);

        // Pre-create the results key so EXPIRE can be set even before the first result arrives
        String resultsKey = resultsKey(batchId);
        redis.opsForHash().put(resultsKey, "__init__", "true");
        redis.expire(resultsKey, BATCH_TTL);

        log.debug("[batch] registered: batchId={} websiteId={} expected={}", batchId, websiteId, expectedCount);
    }

    @Override
    public Optional<ScanSummaryData> recordResult(UUID batchId, Monitor.SubType subType,
                                                  ScanLog.Severity severity, ScanStatusChange statusChange) {
        String metaKey = metaKey(batchId);
        String resultsKey = resultsKey(batchId);

        // Store severity|statusChange as a combined string so we avoid a second hash key.
        // Format: "SEVERITY|STATUS_CHANGE"  (e.g. "HIGH|REGRESSED_TO_FAILING")
        // A null statusChange falls back to FIRST_SCAN for backward-compatibility.
        String changeLabel = statusChange != null ? statusChange.name() : ScanStatusChange.FIRST_SCAN.name();
        String severityLabel = severity != null ? severity.name() : "SKIPPED";
        redis.opsForHash().put(resultsKey, subType.name(), severityLabel + "|" + changeLabel);

        // Single atomic DECR: the caller whose DECR returns 0 is the sole winner.
        // No HINCRBY+HGET pair — no window where TTL expiry between the two commands
        // could cause a second caller to also read completed == expected.
        Long remaining = redis.opsForValue().decrement(completionKey(batchId));

        if (remaining == null) {
            log.warn("[batch] completion counter missing for batchId={} — result recorded but batch may be stale", batchId);
            return Optional.empty();
        }

        log.debug("[batch] result recorded: batchId={} subType={} severity={} remaining={}",
                batchId, subType, severity, remaining);

        if (remaining > 0) {
            return Optional.empty();
        }
        if (remaining < 0) {
            log.warn("[batch] completion counter underflow for batchId={} remaining={} — duplicate result or stale key",
                    batchId, remaining);
            return Optional.empty();
        }

        // remaining == 0: all monitors have reported — build summary
        return buildSummary(batchId, metaKey, resultsKey);
    }

    @Override
    public Optional<BatchTriggerInfo> getBatchTriggerInfo(UUID batchId) {
        String key = metaKey(batchId);
        List<Object> values = redis.opsForHash().multiGet(key, List.of("triggerType", "userId"));
        String triggerType = (String) values.get(0);
        String userIdStr = (String) values.get(1);
        if (triggerType == null) {
            return Optional.empty();
        }
        UUID userId = null;
        if (userIdStr != null) {
            try {
                userId = UUID.fromString(userIdStr);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Optional.of(new BatchTriggerInfo(triggerType, userId));
    }

    private Optional<ScanSummaryData> buildSummary(UUID batchId, String metaKey, String resultsKey) {
        try {
            Map<Object, Object> meta = redis.opsForHash().entries(metaKey);
            Map<Object, Object> rawResults = redis.opsForHash().entries(resultsKey);

            String websiteIdStr = (String) meta.get("websiteId");
            String websiteUrl = (String) meta.get("websiteUrl");
            String userIdStr = (String) meta.get("userId");
            String triggerType = (String) meta.get("triggerType");

            if (websiteIdStr == null || userIdStr == null) {
                log.warn("[batch] incomplete meta for batchId={} — summary skipped", batchId);
                return Optional.empty();
            }

            Map<Monitor.SubType, ScanLog.Severity> results = new EnumMap<>(Monitor.SubType.class);
            Map<Monitor.SubType, ScanStatusChange> statusChanges = new EnumMap<>(Monitor.SubType.class);
            for (Map.Entry<Object, Object> entry : rawResults.entrySet()) {
                String subTypeName = (String) entry.getKey();
                String rawValue = (String) entry.getValue();
                if ("__init__".equals(subTypeName)) continue;
                try {
                    Monitor.SubType subType = Monitor.SubType.valueOf(subTypeName);
                    // Support both legacy "SEVERITY" format and new "SEVERITY|STATUS_CHANGE" format
                    String[] parts = rawValue.split("\\|", 2);
                    results.put(subType, ScanLog.Severity.valueOf(parts[0]));
                    if (parts.length == 2) {
                        try {
                            statusChanges.put(subType, ScanStatusChange.valueOf(parts[1]));
                        } catch (IllegalArgumentException ignored) {
                            // Unknown statusChange value — omit from map; frontend treats absence as UNCHANGED
                        }
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("[batch] unknown subType/severity in results: {}={}", subTypeName, rawValue);
                }
            }

            boolean aborted = "true".equals(meta.get("aborted"));
            ScanSummaryData summary = new ScanSummaryData(
                    batchId,
                    UUID.fromString(websiteIdStr),
                    websiteUrl,
                    UUID.fromString(userIdStr),
                    triggerType,
                    results,
                    statusChanges,
                    aborted
            );

            log.info("[batch] complete: batchId={} websiteId={} results={}", batchId, websiteIdStr, results.size());
            return Optional.of(summary);

        } catch (Exception e) {
            log.error("[batch] error building summary for batchId={}: {}", batchId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public void markAborted(UUID batchId) {
        redis.opsForHash().put(metaKey(batchId), "aborted", "true");
    }

    @Override
    public boolean isAborted(UUID batchId) {
        Object val = redis.opsForHash().get(metaKey(batchId), "aborted");
        return "true".equals(val);
    }
}
