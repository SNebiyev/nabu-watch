package watch.nabu.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Declares that a given compliance document type is required (or optional) for a
 * specific regional market.
 *
 * <p>This is the bridge entity between {@link ComplianceRegion} and
 * {@link ComplianceMonitorType}.  When nabu-watch creates monitors for a new website,
 * it queries active {@code RegionRule} rows for the website's region to determine
 * which compliance document types to watch.
 *
 * <p>{@link ComplianceKeyword} rows with tier {@code MANDATORY_NATIVE} or
 * {@code OPTIONAL_NATIVE} reference this entity — they carry the native-language
 * keywords that the Go worker uses to locate the specific legal page.
 */
@Entity
@Table(
        name = "compliance_region_rules",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_region_rule_region_type",
                        columnNames = {"region_id", "monitor_type_id"}
                )
        },
        indexes = {
                @Index(name = "idx_region_rules_region_id", columnList = "region_id"),
                @Index(name = "idx_region_rules_monitor_type_id", columnList = "monitor_type_id")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegionRule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_region_rules_region_id"))
    private ComplianceRegion region;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_type_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_region_rules_monitor_type_id"))
    private ComplianceMonitorType monitorType;

    /**
     * When {@code true}, the absence of this document triggers a compliance warning.
     * When {@code false}, it is a bonus signal (presence boosts score, absence is silent).
     */
    @Column(name = "mandatory", nullable = false)
    private boolean mandatory;

    /**
     * Inactive rules are excluded from scan-task dispatch.
     * Setting to {@code false} is preferred over deletion to preserve history.
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
