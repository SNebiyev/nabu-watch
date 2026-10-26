package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.ComplianceMonitorType;
import watch.nabu.domain.model.ComplianceRegion;
import watch.nabu.domain.model.RegionRule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegionRuleRepository extends JpaRepository<RegionRule, UUID> {

    /**
     * Returns the {@link watch.nabu.domain.model.Monitor.SubType} codes that are
     * actively configured for the given region.
     *
     * <p>Used by {@link watch.nabu.application.compliance.ComplianceTaskBuilderService}
     * to determine which compliance monitors to create when a new website is registered.
     *
     * @param regionCode ISO 3166-1 alpha-2 code or {@code "GLOBAL"}
     * @return list of sub-type code strings (e.g. {@code ["PRIVACY_POLICY", "IMPRESSUM"]})
     */
    @Query("""
            SELECT mt.subTypeCode FROM RegionRule rr
            JOIN rr.region r
            JOIN rr.monitorType mt
            WHERE r.code = :regionCode
              AND rr.active = true
            """)
    List<String> findActiveSubTypeCodesByRegionCode(@Param("regionCode") String regionCode);

    /**
     * Returns all rules for the given region with their {@code monitorType} eagerly
     * loaded, avoiding N+1 when building the admin compliance matrix view.
     */
    @Query("""
            SELECT rr FROM RegionRule rr
            JOIN FETCH rr.monitorType
            WHERE rr.region.id = :regionId
            """)
    List<RegionRule> findByRegionIdWithMonitorType(@Param("regionId") UUID regionId);

    Optional<RegionRule> findByRegionAndMonitorType(ComplianceRegion region, ComplianceMonitorType monitorType);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE RegionRule rr SET rr.mandatory = :mandatory, rr.active = :active WHERE rr.id = :id")
    void updateMandatoryAndActive(@Param("id") UUID id, @Param("mandatory") boolean mandatory, @Param("active") boolean active);
}
