package watch.nabu.application.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watch.nabu.application.scan.ScanBatchAggregatorPort;
import watch.nabu.domain.model.Monitor;
import watch.nabu.infrastructure.persistence.MonitorRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Heartbeat of nabu-watch — periodically scans for overdue monitors and
 * dispatches them to the {@code nabu:scan-tasks} Redis Stream.
 *
 * <h3>Batch-processing strategy</h3>
 * Monitors are processed in pages of {@code nabu.scheduler.batch-size} rows
 * (default 100) to avoid loading thousands of records into the heap at once.
 * The query always fetches the <em>oldest</em> overdue monitors first, so
 * higher-priority (long-overdue) work is never starved.
 *
 * <h3>Redis-down resilience</h3>
 * If every dispatch in a batch fails (Redis unavailable), the scheduler aborts
 * the current tick immediately — avoiding a busy-loop that would hammer a
 * down Redis.  Because {@link MonitorDispatchService} only updates
 * {@code lastRunAt} after a successful enqueue, all unprocessed monitors
 * remain overdue and will be re-attempted on the next tick.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitoringScheduler {

    private final MonitorRepository monitorRepository;
    private final MonitorDispatchService dispatchService;
    private final ScanBatchAggregatorPort scanBatchAggregator;

    @Value("${nabu.scheduler.batch-size:100}")
    private int batchSize;

    /**
     * Runs on the configured cron expression (default: every 10 minutes).
     * Uses {@code fixedDelay} semantics via the cron string so concurrent
     * executions are impossible when virtual threads are enabled.
     */
    @Scheduled(cron = "${nabu.scheduler.cron:0 */10 * * * *}")
    public void dispatchOverdueMonitors() {
        Instant startedAt    = Instant.now();
        int     totalDispatched = 0;
        int     totalFailed     = 0;

        log.info("Monitoring scheduler tick started");

        while (true) {
            // ── 1. Fetch a page of overdue monitor IDs (native query) ─────────
            List<UUID> overdueIds = monitorRepository.findOverdueMonitorIds(batchSize);
            if (overdueIds.isEmpty()) {
                break; // Nothing left to do
            }

            // ── 2. Load full entities with website JOIN FETCH (avoids lazy-load) ─
            List<Monitor> batch = monitorRepository.findAllWithWebsiteByIds(overdueIds);

            // ── 3. Group by website, register a shared batch per website, dispatch ─
            // Each website's monitors share one batchId so ScanResultProcessor can
            // aggregate all results into a single SCAN_SUMMARY notification per website
            // instead of N per-monitor SCAN_COMPLETED notifications.
            int batchSuccessCount = 0;
            Map<UUID, List<Monitor>> byWebsite = batch.stream()
                    .collect(Collectors.groupingBy(m -> m.getWebsite().getId()));

            for (Map.Entry<UUID, List<Monitor>> entry : byWebsite.entrySet()) {
                List<Monitor> websiteMonitors = entry.getValue();
                Monitor first = websiteMonitors.get(0);
                UUID batchId = UUID.randomUUID();
                scanBatchAggregator.registerBatch(
                        batchId,
                        websiteMonitors.size(),
                        first.getWebsite().getId(),
                        first.getWebsite().getUrl(),
                        first.getWebsite().getOrganization().getId(),
                        "SCHEDULED");

                int[] counts = dispatchService.dispatchBatch(websiteMonitors, batchId);
                batchSuccessCount += counts[0];
                totalDispatched += counts[0];
                totalFailed += counts[1];
            }

            // ── 4. Redis-down guard ───────────────────────────────────────────
            // If zero monitors were dispatched, Redis is likely unavailable.
            // Abort this tick to prevent a tight retry loop against a down stream.
            if (batchSuccessCount == 0) {
                log.warn(
                    "Zero successful dispatches in batch — Redis may be unavailable. " +
                    "Aborting scheduler tick. pendingInBatch={}",
                    batch.size()
                );
                break;
            }

            // Partially successful batches continue (Redis recovered mid-tick,
            // or some monitors had transient errors).
        }

        long elapsed = Duration.between(startedAt, Instant.now()).toMillis();

        if (totalDispatched > 0 || totalFailed > 0) {
            log.info(
                "Monitoring scheduler tick complete: dispatched={} failed={} elapsed={}ms",
                totalDispatched, totalFailed, elapsed
            );
        } else {
            log.debug("Monitoring scheduler tick: no overdue monitors. elapsed={}ms", elapsed);
        }
    }
}
