package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.Website;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebsiteRepository extends JpaRepository<Website, UUID> {

    @Query("SELECT w FROM Website w LEFT JOIN FETCH w.addedBy WHERE w.organization.id = :orgId")
    List<Website> findAllByOrganizationId(@Param("orgId") UUID orgId);

    List<Website> findAllByOrganizationIdIn(Collection<UUID> organizationIds);

    /**
     * Case-insensitive URL partial match — used for the dashboard search filter.
     */
    @Query("SELECT w FROM Website w LEFT JOIN FETCH w.addedBy " +
            "WHERE w.organization.id = :orgId AND LOWER(w.url) LIKE LOWER(CONCAT('%', :url, '%'))")
    List<Website> findAllByOrganizationIdAndUrlContainingIgnoreCase(
            @Param("orgId") UUID orgId, @Param("url") String url);

    /**
     * Looks up an existing website for the given org+URL pair.
     * Used by {@code WebsiteService} to detect duplicate submissions before
     * hitting the {@code uq_websites_org_url} database constraint.
     */
    Optional<Website> findByOrganizationIdAndUrl(UUID organizationId, String url);

    /**
     * Counts an organization's websites.
     * Used by the admin dashboard.
     */
    long countByOrganizationId(UUID organizationId);

    /**
     * Lists every website a specific user added across all their organizations.
     * Used by {@code UserVerificationService} to recompute the user-level
     * verification badge.
     */
    List<Website> findAllByAddedById(UUID addedById);

    /**
     * Counts a user's recent unverified websites — used by the
     * pending-verification anti-abuse gate that caps how many DNS-challenge-pending sites one
     * user can queue inside a rolling window.
     */
    @Query("""
            SELECT COUNT(w) FROM Website w
            WHERE w.addedBy.id = :userId
              AND w.verificationStatus <> watch.nabu.domain.model.Website.VerificationStatus.VERIFIED
              AND w.createdAt >= :cutoff
            """)
    long countPendingVerificationSince(@Param("userId") UUID userId, @Param("cutoff") java.time.Instant cutoff);

    /**
     * Public-trust-page projection: verified sites for a user.
     * Avoids loading + filtering the full website list in-memory on a public,
     * unauthenticated endpoint.
     */
    List<Website> findAllByAddedByIdAndVerificationStatus(
            UUID addedById, Website.VerificationStatus verificationStatus);

    /**
     * Returns a sorted, paginated page of website IDs for the dashboard list.
     *
     * <p>Sort keys are mapped to SQL expressions:
     * <ul>
     *   <li>{@code lastCheckedAt} — MAX(sl.created_at) over the website's monitors</li>
     *   <li>{@code nextScanAt} — MIN(m.next_scan_at) over active monitors</li>
     *   <li>{@code region} — w.region_code</li>
     *   <li>{@code grade} — w.compliance_score</li>
     *   <li>{@code status} — failing-monitor count (most problematic first when DESC)</li>
     * </ul>
     *
     * <p>Returned {@code Object[]} layout per row:
     * <pre>
     *   [0] UUID website_id
     *   [1] Long total_matching  (same value on every row — total filtered count)
     * </pre>
     */
    @Query(value = """
            WITH base AS (
                SELECT w.id                                                   AS website_id,
                       w.url,
                       w.region_code,
                       w.compliance_score,
                       w.status                                               AS website_status,
                       MAX(sl.created_at)                                     AS last_checked_at,
                       MIN(CASE WHEN m.active AND m.last_run_at IS NOT NULL
                                THEN m.last_run_at + (m.interval_minutes || ' minutes')::interval END) AS next_scan_at,
                       COALESCE(SUM(CASE WHEN m.active AND sl.severity IN ('CRITICAL','HIGH')
                                         THEN 1 ELSE 0 END), 0)              AS failing_count
                  FROM websites w
                  LEFT JOIN monitors m ON m.website_id = w.id
                  LEFT JOIN LATERAL (
                      SELECT s.created_at, s.severity
                        FROM scan_logs s
                       WHERE s.monitor_id = m.id
                       ORDER BY s.created_at DESC
                       LIMIT 1
                  ) sl ON true
                 WHERE w.organization_id = :orgId
                   AND (:search IS NULL OR w.url ILIKE '%' || :search || '%')
                 GROUP BY w.id
            ),
            filtered AS (
                SELECT *
                  FROM base
                 WHERE (:statusFilter IS NULL
                        OR (:statusFilter = 'PAUSED'  AND website_status = 'PAUSED')
                        OR (:statusFilter = 'ISSUES'  AND failing_count > 0)
                        OR (:statusFilter = 'FAILING' AND failing_count > 0)
                        OR (:statusFilter = 'WARNING' AND failing_count > 0)
                        OR (:statusFilter = 'PASSING' AND failing_count = 0 AND last_checked_at IS NOT NULL AND website_status != 'PAUSED')
                        OR (:statusFilter = 'PENDING' AND last_checked_at IS NULL AND website_status != 'PAUSED')
                       )
            )
            SELECT website_id,
                   COUNT(*) OVER () AS total_matching
              FROM filtered
             ORDER BY
                   CASE WHEN :sortKey = 'region'       AND :direction = 'asc'  THEN region_code          END ASC  NULLS LAST,
                   CASE WHEN :sortKey = 'region'       AND :direction = 'desc' THEN region_code          END DESC NULLS LAST,
                   CASE WHEN :sortKey = 'grade'        AND :direction = 'asc'  THEN compliance_score     END ASC  NULLS LAST,
                   CASE WHEN :sortKey = 'grade'        AND :direction = 'desc' THEN compliance_score     END DESC NULLS LAST,
                   CASE WHEN :sortKey = 'nextScanAt'   AND :direction = 'asc'  THEN next_scan_at         END ASC  NULLS LAST,
                   CASE WHEN :sortKey = 'nextScanAt'   AND :direction = 'desc' THEN next_scan_at         END DESC NULLS LAST,
                   CASE WHEN :sortKey = 'status'       AND :direction = 'asc'  THEN failing_count        END ASC,
                   CASE WHEN :sortKey = 'status'       AND :direction = 'desc' THEN failing_count        END DESC,
                   CASE WHEN :sortKey NOT IN ('region','grade','nextScanAt','status') AND :direction = 'asc'
                        THEN last_checked_at END ASC  NULLS LAST,
                   CASE WHEN :sortKey NOT IN ('region','grade','nextScanAt','status') AND :direction = 'desc'
                        THEN last_checked_at END DESC NULLS LAST,
                   url ASC
             LIMIT :pageSize OFFSET :offset
            """,
            nativeQuery = true)
    List<Object[]> findDashboardPage(
            @Param("orgId") UUID orgId,
            @Param("search") String search,
            @Param("statusFilter") String statusFilter,
            @Param("sortKey") String sortKey,
            @Param("direction") String direction,
            @Param("pageSize") int pageSize,
            @Param("offset") int offset);

    /**
     * Aggregate stats for the dashboard KPI cards — counts failing/passing/pending
     * websites and SSL/compliance issues without loading any entities.
     *
     * <p>Returned single-row {@code Object[]}:
     * <pre>
     *   [0] Long total_sites
     *   [1] Long failing_sites
     *   [2] Long passing_sites
     *   [3] Long pending_sites
     *   [4] Long ssl_issues
     *   [5] Long compliance_issues
     * </pre>
     */
    @Query(value = """
            WITH latest AS (
                SELECT DISTINCT ON (m.id)
                       m.website_id,
                       m.type        AS monitor_type,
                       m.active,
                       sl.severity
                  FROM monitors m
                  LEFT JOIN scan_logs sl ON sl.monitor_id = m.id
                  JOIN websites w ON w.id = m.website_id
                 WHERE w.organization_id = :orgId
                 ORDER BY m.id, sl.created_at DESC
            ),
            site_status AS (
                SELECT website_id,
                       BOOL_OR(active AND severity IN ('CRITICAL','HIGH')) AS has_failing,
                       BOOL_OR(severity IS NOT NULL)                      AS has_scans
                  FROM latest
                 GROUP BY website_id
            )
            SELECT (SELECT COUNT(*) FROM websites w2
                     WHERE w2.organization_id = :orgId)                              AS total_sites,
                   COALESCE(SUM(CASE WHEN has_failing THEN 1 ELSE 0 END), 0)        AS failing_sites,
                   COALESCE(SUM(CASE WHEN NOT has_failing AND has_scans THEN 1 ELSE 0 END), 0) AS passing_sites,
                   COALESCE(SUM(CASE WHEN NOT has_scans THEN 1 ELSE 0 END), 0)      AS pending_sites,
                   (SELECT COUNT(*) FROM latest
                     WHERE active AND monitor_type = 'SSL' AND severity IN ('CRITICAL','HIGH'))     AS ssl_issues,
                   (SELECT COUNT(*) FROM latest
                     WHERE active AND monitor_type != 'SSL' AND severity IN ('CRITICAL','HIGH'))    AS compliance_issues
              FROM site_status
            """,
            nativeQuery = true)
    Object[] findDashboardStats(@Param("orgId") UUID orgId);

    @Modifying
    @Query("DELETE FROM Website w WHERE w.organization.id IN :orgIds")
    int deleteByOrganizationIdIn(@Param("orgIds") Collection<UUID> orgIds);

    @Modifying
    @Query("UPDATE Website w SET w.addedBy = null WHERE w.addedBy.id = :userId")
    int nullifyAddedBy(@Param("userId") UUID userId);
}
