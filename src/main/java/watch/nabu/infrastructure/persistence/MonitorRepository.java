package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.Monitor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitorRepository extends JpaRepository<Monitor, UUID> {

    /**
     * Resolves the organization ID that owns the given monitor, without triggering
     * lazy loading on the Monitor → Website → Organization chain.
     */
    @Query("SELECT w.organization.id FROM Monitor m JOIN m.website w WHERE m.id = :monitorId")
    Optional<UUID> findOrganizationIdById(@Param("monitorId") UUID monitorId);

    /**
     * Returns the IDs of monitors whose next scheduled run is overdue.
     *
     * <p>A monitor is overdue when:
     * <ul>
     *   <li>{@code last_run_at} is {@code NULL} (never run), or</li>
     *   <li>{@code last_run_at + interval_minutes} is before NOW().</li>
     * </ul>
     *
     * <p>Only monitors that are <b>active</b> and belong to {@code ACTIVE}
     * websites are considered.  Inactive monitors are explicitly excluded so
     * they never receive scan tasks regardless of their interval schedule.
     *
     * <p>Results are ordered oldest-first so the most-overdue monitors are
     * dispatched first.  The {@code LIMIT} keeps each DB round-trip bounded.
     *
     * <p>A native query is required because JPQL has no standard date-interval
     * arithmetic; PostgreSQL's {@code INTERVAL} syntax handles per-row offsets.
     */
    @Query(value = """
            SELECT m.id
              FROM monitors m
              JOIN websites w ON w.id = m.website_id
             WHERE w.status = 'ACTIVE'
               AND m.active = true
               AND (
                     m.last_run_at IS NULL
                  OR m.last_run_at + (m.interval_minutes * INTERVAL '1 minute') < NOW()
                   )
             ORDER BY m.last_run_at ASC NULLS FIRST
             LIMIT :limit
            """,
           nativeQuery = true)
    List<UUID> findOverdueMonitorIds(@Param("limit") int limit);

    /**
     * Loads a batch of monitors by ID with their {@code Website} and
     * {@code Website.organization} associations eagerly fetched via {@code JOIN FETCH}.
     * The organization join is required by the scheduler to read {@code orgId} for
     * batch registration without triggering a lazy-load outside a transaction.
     */
    @Query("SELECT m FROM Monitor m JOIN FETCH m.website w JOIN FETCH w.organization LEFT JOIN FETCH w.addedBy WHERE m.id IN :ids")
    List<Monitor> findAllWithWebsiteByIds(@Param("ids") List<UUID> ids);

    /** Returns ALL monitors for a website (active and inactive). */
    List<Monitor> findByWebsiteId(UUID websiteId);

    /**
     * Returns only the <b>active</b> monitors for a website.
     * Used by the sync and rescan paths where inactive monitors must be
     * excluded from task dispatch.
     */
    List<Monitor> findByWebsiteIdAndActiveTrue(UUID websiteId);

    @Query("SELECT m FROM Monitor m JOIN FETCH m.website LEFT JOIN FETCH m.createdBy WHERE m.website.id IN :websiteIds")
    List<Monitor> findAllByWebsiteIdIn(@Param("websiteIds") Collection<UUID> websiteIds);

    List<Monitor> findByWebsiteIdIn(Collection<UUID> websiteIds);

    @Modifying
    @Query("DELETE FROM Monitor m WHERE m.website.id IN :websiteIds")
    int deleteByWebsiteIdIn(@Param("websiteIds") Collection<UUID> websiteIds);

    @Modifying
    @Query("UPDATE Monitor m SET m.createdBy = null WHERE m.createdBy.id = :userId")
    int nullifyCreatedBy(@Param("userId") UUID userId);
}
