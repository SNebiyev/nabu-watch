package watch.nabu.domain.model;

/**
 * Indicates how a scan was initiated.
 *
 * <ul>
 *   <li>{@code MANUAL}    — triggered by a user via the "Re-scan" button</li>
 *   <li>{@code SCHEDULED} — triggered automatically by {@link watch.nabu.application.scheduler.MonitoringScheduler}</li>
 * </ul>
 */
public enum TriggerType {
    MANUAL, SCHEDULED
}
