package watch.nabu.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A single keyword that the Go worker searches for when scanning a website for
 * a specific compliance document.
 *
 * <h3>Tier semantics and FK constraint</h3>
 * <ul>
 *   <li>{@link KeywordTier#MANDATORY_NATIVE} — native-language term whose <em>presence</em>
 *       marks the document as found (green).  Links to {@link RegionRule} (never to
 *       {@link ComplianceMonitorType} directly).</li>
 *   <li>{@link KeywordTier#OPTIONAL_NATIVE} — native-language bonus term; absence is silent.
 *       Also links to {@link RegionRule}.</li>
 *   <li>{@link KeywordTier#ENGLISH_FALLBACK} — English equivalent used for language-consistency
 *       checks.  Links to {@link ComplianceMonitorType} only — <em>never</em> to
 *       {@link RegionRule} — to avoid duplicating the same English word across 50 regions.</li>
 * </ul>
 *
 * <p>Exactly one of {@code regionRule} or {@code monitorType} is non-null at any time,
 * determined by the {@link KeywordTier}.
 */
@Entity
@Table(
        name = "compliance_keywords",
        indexes = {
                @Index(name = "idx_comp_keywords_region_rule_id", columnList = "region_rule_id"),
                @Index(name = "idx_comp_keywords_monitor_type_id", columnList = "monitor_type_id")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceKeyword extends BaseEntity {

    /**
     * Set for {@link KeywordTier#MANDATORY_NATIVE} and {@link KeywordTier#OPTIONAL_NATIVE}.
     * {@code null} for {@link KeywordTier#ENGLISH_FALLBACK}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_rule_id",
            foreignKey = @ForeignKey(name = "fk_comp_keywords_region_rule_id"))
    private RegionRule regionRule;

    /**
     * Set for {@link KeywordTier#ENGLISH_FALLBACK}.
     * {@code null} for {@link KeywordTier#MANDATORY_NATIVE} and {@link KeywordTier#OPTIONAL_NATIVE}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "monitor_type_id",
            foreignKey = @ForeignKey(name = "fk_comp_keywords_monitor_type_id"))
    private ComplianceMonitorType monitorType;

    /**
     * The keyword text sent to the Go worker (e.g. {@code "Datenschutzerklärung"}).
     */
    @Column(name = "keyword", nullable = false, length = 255)
    private String keyword;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 30)
    private KeywordTier tier;

    // ── Tier enum ─────────────────────────────────────────────────────────────

    public enum KeywordTier {
        /**
         * Required native-language term.  The Go worker reports GREEN when any
         * mandatory keyword for this rule is found on the target page.
         * Always linked to a {@link RegionRule}.
         */
        MANDATORY_NATIVE,

        /**
         * Optional bonus native-language term.  Boosts the confidence score when
         * present; absence causes no warning.
         * Always linked to a {@link RegionRule}.
         */
        OPTIONAL_NATIVE,

        /**
         * English-language equivalent used for language-consistency checks.
         * Finding only English keywords on a non-English-language site triggers
         * {@code language_inconsistency = true} and MEDIUM severity.
         * Always linked to a {@link ComplianceMonitorType}, never to a {@link RegionRule}.
         */
        ENGLISH_FALLBACK
    }
}
