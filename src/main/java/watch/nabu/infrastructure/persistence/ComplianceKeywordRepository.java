package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.ComplianceKeyword;
import watch.nabu.domain.model.ComplianceMonitorType;
import watch.nabu.domain.model.RegionRule;

import java.util.List;
import java.util.UUID;

public interface ComplianceKeywordRepository extends JpaRepository<ComplianceKeyword, UUID> {

    /**
     * Returns all native-tier keywords (MANDATORY_NATIVE + OPTIONAL_NATIVE) for the
     * given region code and compliance document sub-type.
     *
     * <p>The {@code regionRule IS NOT NULL} predicate naturally excludes
     * {@code ENGLISH_FALLBACK} rows (which link to {@code monitorType} only).
     *
     * <p>Used by {@link watch.nabu.application.compliance.ComplianceTaskBuilderService}
     * to build the {@code requiredKeywords} and {@code optionalKeywords} tiers of a
     * scan task payload.
     *
     * @param regionCode  ISO 3166-1 alpha-2 code or {@code "GLOBAL"}
     * @param subTypeCode {@link watch.nabu.domain.model.Monitor.SubType} name
     *                    (e.g. {@code "PRIVACY_POLICY"})
     */
    @Query("""
            SELECT ck FROM ComplianceKeyword ck
            JOIN ck.regionRule rr
            JOIN rr.region r
            JOIN rr.monitorType mt
            WHERE r.code = :regionCode
              AND mt.subTypeCode = :subTypeCode
              AND rr.active = true
            """)
    List<ComplianceKeyword> findNativeByRegionAndSubType(
            @Param("regionCode") String regionCode,
            @Param("subTypeCode") String subTypeCode
    );

    /**
     * Returns the keyword strings for the {@code ENGLISH_FALLBACK} tier for the given
     * sub-type.  These are universal (not region-scoped) and are sent to the Go worker
     * for language-consistency checks on non-English-language websites.
     *
     * <p>The {@code regionRule IS NULL} predicate naturally selects only
     * {@code ENGLISH_FALLBACK} rows.
     *
     * @param subTypeCode {@link watch.nabu.domain.model.Monitor.SubType} name
     */
    @Query("""
            SELECT ck.keyword FROM ComplianceKeyword ck
            JOIN ck.monitorType mt
            WHERE mt.subTypeCode = :subTypeCode
              AND ck.regionRule IS NULL
            """)
    List<String> findFallbackKeywordsBySubTypeCode(@Param("subTypeCode") String subTypeCode);

    List<ComplianceKeyword> findByRegionRule(RegionRule regionRule);

    void deleteByRegionRule(RegionRule regionRule);

    List<ComplianceKeyword> findByMonitorTypeAndRegionRuleIsNull(ComplianceMonitorType monitorType);

    void deleteByMonitorTypeAndRegionRuleIsNull(ComplianceMonitorType monitorType);
}
