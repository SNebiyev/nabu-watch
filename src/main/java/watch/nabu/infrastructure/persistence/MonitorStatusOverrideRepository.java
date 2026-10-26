package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.MonitorStatusOverride;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitorStatusOverrideRepository extends JpaRepository<MonitorStatusOverride, UUID> {

    Optional<MonitorStatusOverride> findByMonitorId(UUID monitorId);

    /**
     * Bulk-loads overrides for all monitors belonging to the given website IDs.
     * Used by the dashboard query to avoid N+1 when rendering override badges.
     */
    @Query("""
            SELECT o FROM MonitorStatusOverride o
            JOIN FETCH o.overriddenBy
            JOIN o.monitor m
            WHERE m.website.id IN :websiteIds
            """)
    List<MonitorStatusOverride> findAllByWebsiteIds(@Param("websiteIds") List<UUID> websiteIds);

    /**
     * Loads overrides for all monitors of a single website, eagerly fetching
     * the user who created each override.
     */
    @Query("""
            SELECT o FROM MonitorStatusOverride o
            JOIN FETCH o.overriddenBy
            JOIN o.monitor m
            WHERE m.website.id = :websiteId
            """)
    List<MonitorStatusOverride> findAllByWebsiteId(@Param("websiteId") UUID websiteId);

    void deleteByMonitorId(UUID monitorId);

    @Modifying
    @Query("DELETE FROM MonitorStatusOverride o WHERE o.monitor.id IN :monitorIds")
    int deleteByMonitorIdIn(@Param("monitorIds") Collection<UUID> monitorIds);
}
