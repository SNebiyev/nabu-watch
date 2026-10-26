package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.MonitorTypeIntervalConfig;

public interface MonitorTypeIntervalConfigRepository
        extends JpaRepository<MonitorTypeIntervalConfig, String> {

    /**
     * Bulk-updates {@code interval_minutes} for all active monitors of the given sub-type.
     *
     * <p>Called immediately after saving a new {@link MonitorTypeIntervalConfig} value
     * so that existing monitors pick up the change without waiting for the next
     * website creation.
     *
     * <p>SSL monitors are excluded — their interval is fixed at 1440 min.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE Monitor m
               SET m.intervalMinutes = :intervalMinutes
             WHERE m.subType          = :subType
               AND m.active           = true
               AND m.type            <> watch.nabu.domain.model.Monitor.MonitorType.SSL
            """)
    int propagateIntervalToMonitors(
            @Param("subType") watch.nabu.domain.model.Monitor.SubType subType,
            @Param("intervalMinutes") int intervalMinutes);

    /**
     * Resets {@code last_run_at} to NOW() for monitors that would otherwise
     * become immediately overdue after an interval decrease.
     *
     * <p>When an admin lowers the interval (e.g. 1440 → 20 min), every monitor
     * whose {@code last_run_at + new_interval < NOW()} would be instantly
     * overdue and dispatched in bulk on the next scheduler tick — causing an
     * unintended scan storm.  Touching {@code last_run_at = NOW()} schedules
     * the first scan in {@code new_interval} minutes instead of immediately.
     *
     * <p>Only monitors that are currently past-due under the new interval are
     * updated; recently-scanned monitors (still within the new window) are left
     * untouched so they fire on their natural schedule.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE monitors m
               SET last_run_at = NOW()
             WHERE m.sub_type          = :subTypeCode
               AND m.active            = true
               AND m.type             <> 'SSL'
               AND (
                     m.last_run_at IS NULL
                  OR m.last_run_at + (:intervalMinutes * INTERVAL '1 minute') < NOW()
                   )
            """, nativeQuery = true)
    int resetLastRunAtForOverdueMonitors(
            @Param("subTypeCode") String subTypeCode,
            @Param("intervalMinutes") int intervalMinutes);
}
