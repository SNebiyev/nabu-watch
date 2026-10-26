package watch.nabu.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A geographic/legal market that nabu-watch monitors compliance for.
 *
 * <p>Each region owns a set of {@link RegionRule} entries that declare which
 * compliance document types are required and what keywords identify them.
 *
 * <p>Exactly one row must have {@code globalDefault = true} — this is used as
 * the fallback when a website's region code is not explicitly configured.
 */
@Entity
@Table(
        name = "compliance_regions",
        indexes = {
                @Index(name = "idx_compliance_regions_code", columnList = "code", unique = true)
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceRegion extends BaseEntity {

    /**
     * ISO 3166-1 alpha-2 code (e.g. {@code "DE"}, {@code "GB"}) or {@code "GLOBAL"}.
     */
    @Column(name = "code", nullable = false, unique = true, length = 10)
    private String code;

    /**
     * Human-readable market name (e.g. {@code "Germany"}, {@code "Global (Default)"}).
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * When {@code false} the region is hidden from the UI and its monitors are
     * treated as inactive (but data is preserved for auditing).
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * Marks this region as the fallback for any website whose region code is not
     * explicitly configured.  Exactly one row should have this flag set to {@code true}.
     */
    @Column(name = "global_default", nullable = false)
    @Builder.Default
    private boolean globalDefault = false;
}
