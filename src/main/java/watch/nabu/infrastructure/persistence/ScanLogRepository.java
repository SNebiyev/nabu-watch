package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.ScanLog;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScanLogRepository extends JpaRepository<ScanLog, UUID> {

    /**
     * Returns the single most-recent {@link ScanLog} for each monitor
     * associated with the given website, in a single database round-trip.
     *
     * <p>Uses PostgreSQL's {@code DISTINCT ON} to pick the latest row per
     * {@code monitor_id} after ordering by {@code created_at DESC}.
     * The join to {@code monitors} narrows the result set to the target website.
     */
    @Query(value = """
            SELECT DISTINCT ON (sl.monitor_id) sl.*
              FROM scan_logs sl
              JOIN monitors m ON m.id = sl.monitor_id
             WHERE m.website_id = :websiteId
             ORDER BY sl.monitor_id, sl.created_at DESC
            """,
           nativeQuery = true)
    List<ScanLog> findLatestPerMonitorByWebsiteId(@Param("websiteId") UUID websiteId);

    Optional<ScanLog> findTopByMonitorIdOrderByCreatedAtDesc(UUID monitorId);

    /**
     * Returns the recent {@code links_on_page} values (number of navigable
     * {@code <a href>} tags the scanner saw on the website's homepage) for all
     * compliance scans of the given website within the last 30 days.
     *
     * <p>Used by {@code TaskProducer} to compute a per-website baseline median
     * which the Go worker compares against the current scan's link count — a
     * drastic drop is a strong signal that the homepage came back as a WAF
     * interstitial even though it passed other shell-detection heuristics.
     *
     * <p>Rows missing the field (SSL monitors, COOKIE_BANNER, targeted-mode
     * scans) are filtered out at query time so the returned list only contains
     * homepage-mode compliance samples.
     */
    @Query(value = """
            SELECT CAST(sl.result_json->>'links_on_page' AS integer)
              FROM scan_logs sl
              JOIN monitors m ON m.id = sl.monitor_id
             WHERE m.website_id = :websiteId
               AND sl.result_json ->> 'links_on_page' IS NOT NULL
               AND sl.created_at > NOW() - INTERVAL '30 days'
             ORDER BY sl.created_at DESC
             LIMIT :sampleLimit
            """,
           nativeQuery = true)
    List<Integer> findRecentLinksOnPageForWebsite(@Param("websiteId") UUID websiteId,
                                                  @Param("sampleLimit") int sampleLimit);

    /**
     * Batch variant of {@link #findLatestPerMonitorByWebsiteId}: returns the
     * most-recent {@link ScanLog} per monitor for <em>all</em> of the given
     * website IDs in a single round-trip.
     */
    @Query(value = """
            SELECT DISTINCT ON (sl.monitor_id) sl.*
              FROM scan_logs sl
              JOIN monitors m ON m.id = sl.monitor_id
             WHERE m.website_id IN (:websiteIds)
             ORDER BY sl.monitor_id, sl.created_at DESC
            """,
           nativeQuery = true)
    List<ScanLog> findLatestPerMonitorByWebsiteIds(@Param("websiteIds") Collection<UUID> websiteIds);

    // ── Admin analytics ───────────────────────────────────────────────────────

    /**
     * Returns the most-recent scan logs whose status changed compared to
     * the immediately-preceding scan (i.e. {@code previous_severity IS NOT NULL}
     * and {@code severity != previous_severity}).  Includes the ID of the
     * immediately-preceding scan so the frontend can fetch both for comparison.
     *
     * <p>Returned {@code Object[]} layout per row:
     * <pre>
     *   [0]  UUID      scan_log_id
     *   [1]  UUID      monitor_id
     *   [2]  UUID      website_id
     *   [3]  String    website_url
     *   [4]  String    monitor_type
     *   [5]  String    sub_type
     *   [6]  String    severity
     *   [7]  String    previous_severity
     *   [8]  Timestamp scanned_at
     *   [9]  String    org_name
     *   [10] UUID      previous_scan_log_id  (nullable)
     * </pre>
     */
    @Query(value = """
            SELECT sl.id                AS scan_log_id,
                   m.id                 AS monitor_id,
                   w.id                 AS website_id,
                   w.url                AS website_url,
                   m.type               AS monitor_type,
                   m.sub_type           AS sub_type,
                   sl.severity          AS severity,
                   sl.previous_severity AS previous_severity,
                   sl.created_at        AS scanned_at,
                   o.name               AS org_name,
                   prev.id              AS previous_scan_log_id
              FROM scan_logs sl
              JOIN monitors m ON m.id = sl.monitor_id
              JOIN websites w ON w.id = m.website_id
              JOIN organizations o ON o.id = w.organization_id
              LEFT JOIN LATERAL (
                  SELECT p.id
                    FROM scan_logs p
                   WHERE p.monitor_id = sl.monitor_id
                     AND p.created_at < sl.created_at
                   ORDER BY p.created_at DESC
                   LIMIT 1
              ) prev ON true
             WHERE sl.previous_severity IS NOT NULL
               AND sl.severity != sl.previous_severity
               AND m.active = true
               AND w.status = 'ACTIVE'
             ORDER BY sl.created_at DESC
             LIMIT :limit
            """,
           nativeQuery = true)
    List<Object[]> findRecentStatusChangesRaw(@Param("limit") int limit);

    // ── Activity feed queries ─────────────────────────────────────────────────

    /**
     * Returns a single {@link ScanLog} by its ID with monitor and website eagerly
     * fetched in one query.  Used by the admin scan-detail drawer.
     */
    @Query("""
            SELECT sl FROM ScanLog sl
            JOIN FETCH sl.monitor m
            JOIN FETCH m.website w
            LEFT JOIN FETCH sl.triggeredBy
            WHERE sl.id = :id
            """)
    Optional<ScanLog> findByIdWithDetails(@Param("id") UUID id);

    /**
     * Returns the {@code limit} most-recent scan logs belonging to any website
     * owned by the given organization.  Uses JOIN FETCH so monitor and website are
     * loaded in a single query and the caller can access their fields without
     * triggering lazy-load exceptions outside a transaction.
     *
     * <p>Intended for {@code GET /api/v1/scans/recent}.
     */
    @Query("""
            SELECT sl FROM ScanLog sl
            JOIN FETCH sl.monitor m
            JOIN FETCH m.website w
            WHERE w.organization.id = :orgId
            ORDER BY sl.createdAt DESC
            """)
    List<ScanLog> findRecentByOrganizationId(@Param("orgId") UUID orgId,
                                             org.springframework.data.domain.Pageable pageable);

    /**
     * Returns the {@code limit} most-recent scan logs across all users.
     * Admin-only; used by {@code GET /api/v1/admin/scans/global}.
     */
    @Query("""
            SELECT sl FROM ScanLog sl
            JOIN FETCH sl.monitor m
            JOIN FETCH m.website w
            ORDER BY sl.createdAt DESC
            """)
    List<ScanLog> findRecentGlobal(org.springframework.data.domain.Pageable pageable);

    /**
     * Count of scan logs created after {@code since} — used to report
     * activity in the last hour on the admin dashboard.
     */
    long countByCreatedAtAfter(Instant since);

    /**
     * Count of scan logs whose embedded {@code result_json.status} field
     * equals {@code "FAILED"} (written by {@code ScanResultProcessor}).
     */
    @Query(value = "SELECT COUNT(*) FROM scan_logs WHERE result_json->>'status' = 'FAILED'",
           nativeQuery = true)
    long countFailedScans();

    /**
     * Returns the {@code limit} most-recent scan logs for all monitors belonging
     * to the given website.  Used by {@link watch.nabu.application.website.WebsiteQueryService}
     * to build the scan-history batches.
     *
     * <p>The monitor association is LAZY — callers must resolve monitor properties
     * via a pre-loaded monitor map (calling {@code sl.getMonitor().getId()} is safe
     * without a transaction because Hibernate resolves the FK proxy ID without
     * a round-trip).
     */
    @Query(value = """
            SELECT sl.* FROM scan_logs sl
              JOIN monitors m ON m.id = sl.monitor_id
             WHERE m.website_id = :websiteId
             ORDER BY sl.created_at DESC
             LIMIT :limit
            """,
            nativeQuery = true)
    List<ScanLog> findRecentByWebsiteId(
            @Param("websiteId") UUID websiteId,
            @Param("limit") int limit);

    // ── Compliance trend aggregation ─────────────────────────────────────────

    /**
     * Aggregates scan results into time-bucketed pass/fail counts for
     * the given website.  Used by the compliance trend chart.
     *
     * <p>Returned {@code Object[]} layout per row:
     * <pre>
     *   [0] String  bucket     ("2026-03", "2026-W13", "2026-03-31")
     *   [1] Long    total      (all scans in bucket)
     *   [2] Long    passing    (severity IN INFO, LOW, MEDIUM)
     *   [3] Long    failing    (severity IN HIGH, CRITICAL)
     * </pre>
     */
    @Query(value = """
            SELECT bucket,
                   COUNT(*)                                                             AS total,
                   SUM(CASE WHEN severity IN ('INFO','LOW','MEDIUM') THEN 1 ELSE 0 END) AS passing,
                   SUM(CASE WHEN severity IN ('HIGH','CRITICAL')     THEN 1 ELSE 0 END) AS failing
              FROM (
                    SELECT CASE :period
                             WHEN 'MONTHLY' THEN to_char(sl.created_at, 'YYYY-MM')
                             WHEN 'WEEKLY'  THEN to_char(sl.created_at, 'IYYY-"W"IW')
                             ELSE                 to_char(sl.created_at, 'YYYY-MM-DD')
                           END AS bucket,
                           sl.severity
                      FROM scan_logs sl
                      JOIN monitors m ON m.id = sl.monitor_id
                     WHERE m.website_id = :websiteId
                       AND m.active = true
                       AND sl.severity IS NOT NULL
                       AND sl.created_at >= :since
                       AND (:subType IS NULL OR m.sub_type = CAST(:subType AS VARCHAR))
                   ) AS bucketed
             GROUP BY bucket
             ORDER BY bucket
            """,
            nativeQuery = true)
    List<Object[]> findComplianceTrendRaw(
            @Param("websiteId") UUID websiteId,
            @Param("period") String period,
            @Param("since") Instant since,
            @Param("subType") String subType);

    // ── Scan health report ──────────────────────────────────────────────────

    /**
     * Returns the latest scan result per active monitor for all active websites.
     * Used by the admin "Scan Health Report" to show a flat table of every
     * monitor's current state without opening individual drawers.
     *
     * <p>Returned {@code Object[]} layout per row:
     * <pre>
     *   [0] UUID      website_id
     *   [1] String    website_url
     *   [2] String    region_code
     *   [3] UUID      monitor_id
     *   [4] String    sub_type
     *   [5] String    severity
     *   [6] Timestamp scanned_at
     *   [7] UUID      scan_log_id
     *   [8] String    status        (from result_json->>'status')
     *   [9] String    summary       (key finding — first non-null of: error, scan_item_summary,
     *                                 days_remaining || ' days', tracker_count || ' trackers',
     *                                 banner_detected, or 'OK')
     * </pre>
     */
    @Query(value = """
            SELECT w.id                                                       AS website_id,
                   w.url                                                      AS website_url,
                   w.region_code                                              AS region_code,
                   m.id                                                       AS monitor_id,
                   m.sub_type                                                 AS sub_type,
                   latest.severity                                            AS severity,
                   latest.created_at                                          AS scanned_at,
                   latest.id                                                  AS scan_log_id,
                   latest.result_json->>'status'                              AS status,
                   COALESCE(
                       latest.result_json->>'error',
                       latest.result_json->>'scan_item_summary',
                       CASE WHEN latest.result_json->>'days_remaining' IS NOT NULL
                            THEN CONCAT(latest.result_json->>'days_remaining', ' days remaining')
                       END,
                       CASE WHEN latest.result_json->>'tracker_count' IS NOT NULL
                            THEN CONCAT(latest.result_json->>'tracker_count', ' trackers found')
                       END,
                       CASE WHEN latest.result_json->>'banner_detected' IS NOT NULL
                            THEN CONCAT('Banner: ', latest.result_json->>'banner_detected')
                       END,
                       'OK'
                   )                                                          AS summary
              FROM (
                       SELECT DISTINCT ON (sl.monitor_id)
                              sl.id,
                              sl.monitor_id,
                              sl.severity,
                              sl.created_at,
                              sl.result_json
                         FROM scan_logs sl
                        ORDER BY sl.monitor_id, sl.created_at DESC
                   ) AS latest
              JOIN monitors m ON m.id = latest.monitor_id
              JOIN websites w ON w.id = m.website_id
             WHERE w.status = 'ACTIVE'
               AND m.active = true
             ORDER BY
                   CASE latest.severity
                       WHEN 'CRITICAL' THEN 1
                       WHEN 'HIGH'     THEN 2
                       WHEN 'MEDIUM'   THEN 3
                       ELSE 4
                   END,
                   latest.created_at DESC
             LIMIT :limit OFFSET :offset
            """,
            nativeQuery = true)
    List<Object[]> findHealthReportRaw(@Param("limit") int limit, @Param("offset") int offset);

    @Query(value = """
            SELECT COUNT(*)
              FROM monitors m
              JOIN websites w ON w.id = m.website_id
             WHERE w.status = 'ACTIVE'
               AND m.active = true
               AND EXISTS (SELECT 1 FROM scan_logs sl WHERE sl.monitor_id = m.id)
            """,
            nativeQuery = true)
    long countHealthReportRows();

    // ── Weekly digest queries ────────────────────────────────────────────────

    /**
     * Count of scans created for the given organization since {@code since}.
     * Used by the weekly digest email to report "scans run this week".
     */
    @Query(value = """
            SELECT COUNT(*)
              FROM scan_logs sl
              JOIN monitors m ON m.id = sl.monitor_id
              JOIN websites w ON w.id = m.website_id
             WHERE w.organization_id = :orgId
               AND sl.created_at >= :since
            """,
            nativeQuery = true)
    long countByOrganizationIdSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    /**
     * Count of active monitors whose <em>latest</em> scan is HIGH or CRITICAL
     * for the given organization. Used by the weekly digest email as the
     * "currently open high/critical issues" metric.
     */
    @Query(value = """
            SELECT COUNT(*)
              FROM (
                       SELECT DISTINCT ON (sl.monitor_id) sl.severity
                         FROM scan_logs sl
                         JOIN monitors m ON m.id = sl.monitor_id
                         JOIN websites w ON w.id = m.website_id
                        WHERE w.organization_id = :orgId
                          AND m.active = true
                        ORDER BY sl.monitor_id, sl.created_at DESC
                   ) latest
             WHERE latest.severity IN ('HIGH', 'CRITICAL')
            """,
            nativeQuery = true)
    long countOpenHighCriticalByOrganizationId(@Param("orgId") UUID orgId);

    /**
     * Returns up to {@code limit} websites in the organization whose latest
     * monitor scan result is HIGH or CRITICAL — i.e. currently FAILING sites.
     *
     * <p>Returned {@code Object[]} layout per row:
     * <pre>
     *   [0] UUID   website_id
     *   [1] String url
     *   [2] Long   failing_monitor_count
     * </pre>
     */
    @Query(value = """
            SELECT w.id  AS website_id,
                   w.url AS url,
                   COUNT(*) AS failing_count
              FROM (
                       SELECT DISTINCT ON (sl.monitor_id)
                              m.website_id,
                              sl.severity
                         FROM scan_logs sl
                         JOIN monitors m ON m.id = sl.monitor_id
                        WHERE m.active = true
                        ORDER BY sl.monitor_id, sl.created_at DESC
                   ) latest
              JOIN websites w ON w.id = latest.website_id
             WHERE w.organization_id = :orgId
               AND latest.severity IN ('HIGH', 'CRITICAL')
             GROUP BY w.id, w.url
             ORDER BY failing_count DESC, w.url
             LIMIT :limit
            """,
            nativeQuery = true)
    List<Object[]> findFailingWebsitesByOrganizationId(
            @Param("orgId") UUID orgId, @Param("limit") int limit);

    /**
     * Counts monitors that RECOVERED (FAILING→PASSING) and DEGRADED (PASSING→FAILING)
     * in the last 24 hours for the given organization.
     *
     * <p>Returned {@code Object[]} single row: {@code [0] Long recovered, [1] Long degraded}.
     */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN sl.previous_severity IN ('CRITICAL','HIGH')
                                      AND sl.severity IN ('INFO','LOW','MEDIUM')
                                 THEN 1 ELSE 0 END), 0) AS recovered,
                   COALESCE(SUM(CASE WHEN sl.previous_severity IN ('INFO','LOW','MEDIUM')
                                      AND sl.severity IN ('CRITICAL','HIGH')
                                 THEN 1 ELSE 0 END), 0) AS degraded
              FROM scan_logs sl
              JOIN monitors m ON m.id = sl.monitor_id
              JOIN websites w ON w.id = m.website_id
             WHERE w.organization_id = :orgId
               AND sl.previous_severity IS NOT NULL
               AND sl.severity != sl.previous_severity
               AND sl.created_at >= :since
               AND m.active = true
               AND w.status = 'ACTIVE'
            """,
            nativeQuery = true)
    Object[] countStatusChanges(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Modifying
    @Query("DELETE FROM ScanLog sl WHERE sl.monitor.id IN :monitorIds")
    int deleteByMonitorIdIn(@Param("monitorIds") Collection<UUID> monitorIds);

    @Modifying
    @Query("UPDATE ScanLog sl SET sl.triggeredBy = null WHERE sl.triggeredBy.id = :userId")
    int nullifyTriggeredBy(@Param("userId") UUID userId);

    // ── Reporting ─────────────────────────────────────────────────────────────

    @Query("""
            SELECT sl FROM ScanLog sl
            JOIN FETCH sl.monitor m
            JOIN FETCH m.website w
            JOIN FETCH w.organization o
            LEFT JOIN FETCH sl.reportedBy rb
            WHERE sl.reported = true
            ORDER BY sl.reportedAt DESC NULLS LAST
            """)
    org.springframework.data.domain.Page<ScanLog> findAllReported(
            org.springframework.data.domain.Pageable pageable);

    long countByReportedTrue();

    @Modifying
    @Query("UPDATE ScanLog sl SET sl.reportedBy = null WHERE sl.reportedBy.id = :userId")
    int nullifyReportedBy(@Param("userId") UUID userId);
}
