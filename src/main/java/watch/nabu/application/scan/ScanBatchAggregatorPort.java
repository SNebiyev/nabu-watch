package watch.nabu.application.scan;

import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.ScanStatusChange;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for batch-scan aggregation.
 *
 * <p>When a manual rescan is triggered, all monitors for a website share a
 * {@code batchId}.  This port tracks how many results have arrived and signals
 * when the batch is complete so a single rich {@code SCAN_SUMMARY} notification
 * can be emitted instead of one notification per monitor.
 *
 * <p>Implemented by {@link watch.nabu.infrastructure.redis.RedisScanBatchAggregator}.
 */
public interface ScanBatchAggregatorPort {

    /**
     * Registers a new batch before scan tasks are dispatched.
     *
     * @param batchId       the shared batch UUID assigned to all monitors in this rescan
     * @param expectedCount the number of monitors that will report a result
     * @param websiteId     the website being scanned
     * @param websiteUrl    the website URL (used in the notification message)
     * @param userId        the owner to receive the summary notification
     * @param triggerType   {@code "MANUAL"} or {@code "SCHEDULED"}
     */
    void registerBatch(UUID batchId, int expectedCount, UUID websiteId,
                       String websiteUrl, UUID userId, String triggerType);

    /**
     * Records one monitor result for the given batch.
     *
     * @param statusChange how this scan's severity compares to the previous one
     *                     (may be null if unavailable — treated as FIRST_SCAN)
     * @return a {@link ScanSummaryData} with all results when the batch is complete;
     * {@link Optional#empty()} while results are still outstanding
     */
    Optional<ScanSummaryData> recordResult(UUID batchId, Monitor.SubType subType,
                                           ScanLog.Severity severity, ScanStatusChange statusChange);

    /**
     * Returns the trigger metadata for the given batch: trigger type and
     * the user who initiated it (for MANUAL scans).
     *
     * @return trigger info, or {@link Optional#empty()} if the batch has expired from Redis
     */
    Optional<BatchTriggerInfo> getBatchTriggerInfo(UUID batchId);

    /**
     * Batch-level trigger metadata used for audit persistence on {@code ScanLog}.
     *
     * @param triggerType {@code "MANUAL"} or {@code "SCHEDULED"}
     * @param userId      the user who triggered the scan (org owner for scheduled; caller for manual)
     */
    record BatchTriggerInfo(String triggerType, UUID userId) {
    }

    /**
     * Marks a batch as aborted (scout determined homepage is not scannable).
     * Used to include WAF/abort info in the batch summary notification.
     */
    void markAborted(UUID batchId);

    /**
     * Returns true if the batch was marked as aborted.
     */
    boolean isAborted(UUID batchId);
}
