package watch.nabu.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;


/**
 * Catalogue entry for a compliance document type (e.g. Privacy Policy, Impressum).
 *
 * <p>Each row corresponds to one value of {@link Monitor.SubType} (excluding {@code SSL}).
 * {@link ComplianceKeyword} rows with tier {@code ENGLISH_FALLBACK} link directly to this
 * entity — they are universal across all regions and are not duplicated per-country.
 *
 * <p>{@link RegionRule} rows link both to a {@link ComplianceRegion} and to this entity,
 * forming the many-to-many bridge that declares which document types are required per market.
 */
@Entity
@Table(
        name = "compliance_monitor_types",
        indexes = {
                @Index(name = "idx_compliance_monitor_types_code", columnList = "sub_type_code", unique = true)
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceMonitorType extends BaseEntity {

    /**
     * Matches the {@link Monitor.SubType} enum name exactly (e.g. {@code "PRIVACY_POLICY"}).
     * Used as the join key when resolving keywords at scan-dispatch time.
     */
    @Column(name = "sub_type_code", nullable = false, unique = true, length = 30)
    private String subTypeCode;

    /**
     * Display name shown in the UI (e.g. {@code "Privacy Policy"}).
     */
    @Column(name = "default_name", nullable = false, length = 100)
    private String defaultName;

    /**
     * Optional longer description of what this document type covers.
     */
    @Column(name = "description", length = 500)
    private String description;

}
