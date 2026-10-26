package watch.nabu.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "scan_logs", indexes = {
    @Index(name = "idx_scan_logs_monitor_id",  columnList = "monitor_id"),
    @Index(name = "idx_scan_logs_created_at",  columnList = "created_at"),
    @Index(name = "idx_scan_logs_severity",    columnList = "severity")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanLog extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_scan_logs_monitor_id"))
    private Monitor monitor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private Map<String, Object> resultJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    @Builder.Default
    private Severity severity = Severity.INFO;

    /**
     * Severity of the monitor's last <em>distinct</em> preceding scan — i.e.
     * the most recent severity that differs from {@link #severity}. When a
     * scan repeats the same severity as the one before it, this field is
     * carried forward from the prior row rather than overwritten, so status-
     * change badges (IMPROVED / REGRESSED / RECOVERED / REGRESSED_TO_FAILING)
     * remain visible until the monitor actually transitions again.
     *
     * <p>{@code null} when this is the monitor's first scan or for legacy
     * rows scanned before this field was tracked.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_severity", length = 20)
    private Severity previousSeverity;

    @Column(name = "batch_id")
    private UUID batchId;

    /**
     * How this scan was initiated: {@code MANUAL} (user-triggered rescan) or
     * {@code SCHEDULED} (automatic scheduler dispatch).
     * Nullable for scan logs created before this was recorded.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", length = 20)
    private TriggerType triggerType;

    /**
     * The user who triggered this scan (non-null only for MANUAL scans).
     * Nullable for scheduled scans and for logs created before this was recorded.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "triggered_by_user_id",
            foreignKey = @ForeignKey(name = "fk_scan_logs_triggered_by_user_id"))
    private User triggeredBy;

    // ── Reporting ─────────────────────────────────────────────────────────────

    @Column(name = "reported", nullable = false)
    @Builder.Default
    private boolean reported = false;

    @Column(name = "report_notes", length = 2000)
    private String reportNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_user_id",
            foreignKey = @ForeignKey(name = "fk_scan_logs_reported_by_user_id"))
    private User reportedBy;

    @Column(name = "reported_at")
    private Instant reportedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    public enum Severity {
        INFO, LOW, MEDIUM, HIGH, CRITICAL;

        /**
         * A scan is "failing" (FAILING bucket / alert-worthy) iff its severity
         * is HIGH or CRITICAL. Canonical rule used by the health view,
         * alerting, and reporting.
         */
        public boolean isFailing() {
            return this == HIGH || this == CRITICAL;
        }
    }
}
