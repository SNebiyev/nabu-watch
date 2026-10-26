package watch.nabu.domain.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Per-subtype automated scheduler interval, shared by every user.
 *
 * <p>When a {@link Monitor} is created for a website, its {@code intervalMinutes}
 * is resolved from this table using the monitor's sub-type. If no row exists
 * the scheduler falls back to {@code nabu.scan.default-interval-minutes}.
 *
 * <p>Admin changes are propagated immediately to all active monitors of the
 * updated sub-type.
 *
 * <p>SSL monitors are always fixed at 1440 min (24 h); their row in this table
 * is only used as a display default in the admin UI.
 */
@Entity
@Table(name = "monitor_type_interval_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonitorTypeIntervalConfig {

    /**
     * Matches {@link Monitor.SubType} enum name exactly (e.g. {@code "PRIVACY_POLICY"}).
     */
    @Id
    @Column(name = "sub_type_code", nullable = false, length = 30)
    private String subTypeCode;

    /**
     * How often (in minutes) the scheduler should trigger monitors of this sub-type.
     */
    @Column(name = "auto_scan_interval_minutes", nullable = false)
    @Builder.Default
    private int autoScanIntervalMinutes = 1440;
}
