package watch.nabu.application.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watch.nabu.domain.model.Monitor;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.redis.TaskProducer;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Handles the dispatch of a single {@link Monitor}.
 *
 * <p><strong>Resilience contract:</strong> {@code lastRunAt} is stamped in the
 * database only <em>after</em> the Redis {@code XADD} succeeds.  If Redis is
 * unavailable the monitor remains overdue and will be retried on the next
 * scheduler tick — without any duplicate-prevention guarantee (at-least-once).
 *
 * <p>If the DB update fails after a successful enqueue, the monitor will be
 * re-dispatched on the next tick (again at-least-once).  Workers downstream
 * must be idempotent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorDispatchService {

    private final MonitorRepository monitorRepository;
    private final TaskProducer      taskProducer;

    /**
     * Dispatches a batch of monitors with scout-first ordering.
     * Returns {@code int[]{successCount, failCount}}.
     */
    public int[] dispatchBatch(List<Monitor> monitors, UUID batchId) {
        // Sort: homepage-parsing compliance first (scout), then banner/tracker, then SSL
        monitors.sort(Comparator.comparingInt((Monitor m) -> switch (m.getSubType()) {
            case SSL -> 2;
            case COOKIE_BANNER, TRACKER_DETECTION -> 1;
            default -> 0;
        }));

        int success = 0, failed = 0;
        boolean scoutAssigned = false;
        for (Monitor m : monitors) {
            boolean isScout = !scoutAssigned
                    && m.getType() == Monitor.MonitorType.COMPLIANCE
                    && m.getSubType() != Monitor.SubType.COOKIE_BANNER
                    && m.getSubType() != Monitor.SubType.TRACKER_DETECTION
                    && m.getSubType() != Monitor.SubType.SSL;
            if (isScout) scoutAssigned = true;

            if (dispatch(m, batchId, isScout)) {
                success++;
            } else {
                failed++;
            }
        }
        return new int[]{success, failed};
    }

    public boolean dispatch(Monitor monitor) {
        return dispatch(monitor, null, false);
    }

    public boolean dispatch(Monitor monitor, UUID batchId) {
        return dispatch(monitor, batchId, false);
    }

    /**
     * Enqueues a scan task for {@code monitor} with a shared {@code batchId}
     * and, on success, persists the current timestamp to {@code Monitor.lastRunAt}.
     *
     * @param batchId shared batch UUID for aggregation; {@code null} for ungrouped dispatches
     * @param isScout if {@code true}, marks this task as the scout (first homepage fetch in batch)
     * @return {@code true} if the task was successfully pushed to Redis
     */
    public boolean dispatch(Monitor monitor, UUID batchId, boolean isScout) {
        try {
            taskProducer.enqueue(monitor, batchId, isScout);
        } catch (Exception ex) {
            // Redis is down or the XADD failed — do NOT touch lastRunAt so the
            // monitor stays in the overdue set and is retried next tick.
            log.error(
                "Redis enqueue FAILED — lastRunAt NOT updated: " +
                "monitorId={} type={} url={} error={}",
                monitor.getId(), monitor.getType(),
                monitor.getWebsite().getUrl(), ex.getMessage()
            );
            return false;
        }

        // Task is safely in the stream — stamp the dispatch time.
        // If this DB write fails the task was already enqueued (at-least-once).
        try {
            monitor.setLastRunAt(Instant.now());
            monitorRepository.save(monitor);
        } catch (Exception ex) {
            log.error(
                "DB update of lastRunAt FAILED — task was already enqueued (at-least-once): " +
                "monitorId={} error={}",
                monitor.getId(), ex.getMessage()
            );
        }

        return true;
    }
}
