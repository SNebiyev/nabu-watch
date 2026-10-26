package watch.nabu.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Stores a manual severity override for a single {@link Monitor}.
 *
 * <p>When a website owner (ADMIN/OWNER role) considers a scanner-assigned severity
 * acceptable, they save an override that maps a {@code baselineSeverity} to an
 * {@code overrideSeverity}.  On subsequent scans, if the scanner produces the
 * same baseline severity the system uses the override for grade calculation and
 * status display.  If the severity differs, the system shows the scanner's
 * result and flags the mismatch.
 *
 * <p>Only one active override per monitor (enforced by a UNIQUE constraint on
 * {@code monitor_id}).
 */
@Entity
@Table(name = "monitor_status_overrides",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_monitor_status_overrides_monitor_id",
                columnNames = "monitor_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonitorStatusOverride extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_monitor_overrides_monitor_id"))
    private Monitor monitor;

    /**
     * The severity that was present when the user accepted the result.
     * Used for comparison: override applies only when the current scan
     * severity matches this value.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "baseline_severity", nullable = false, length = 20)
    private ScanLog.Severity baselineSeverity;

    /**
     * The severity the user wants the monitor to display.
     * Used for grade calculation and status derivation when the override is active.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "override_severity", nullable = false, length = 20)
    private ScanLog.Severity overrideSeverity;

    /**
     * The specific scan result the user reviewed when creating the override.
     * Retained for audit trail — shows exactly which result was accepted.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_log_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_monitor_overrides_scan_log_id"))
    private ScanLog scanLog;

    /**
     * The user who created or last updated this override.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "overridden_by", nullable = false,
            foreignKey = @ForeignKey(name = "fk_monitor_overrides_overridden_by"))
    private User overriddenBy;

    @NotNull
    @Column(name = "overridden_at", nullable = false)
    private Instant overriddenAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Returns {@code true} when the given severity matches the baseline,
     * meaning this override should be applied.
     */
    public boolean matches(ScanLog.Severity currentSeverity) {
        return baselineSeverity == currentSeverity;
    }
}
