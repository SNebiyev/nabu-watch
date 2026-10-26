package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.WebsiteCompliancePath;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface WebsiteCompliancePathRepository extends JpaRepository<WebsiteCompliancePath, UUID> {

    /**
     * Lookup by website + subType — used by TaskProducer to retrieve stored path hints
     * before dispatching each scan.
     */
    Optional<WebsiteCompliancePath> findByWebsiteIdAndSubType(UUID websiteId, String subType);

    /**
     * Atomic UPSERT keyed on (website_id, sub_type) with confidence-rank gating.
     *
     * <p>A lower-confidence entry will NOT overwrite a higher-confidence one.
     * Confidence rank: {@code high=3 > medium=2 > low=1}.
     *
     * <p>On conflict, all mutable fields are refreshed and {@code fail_count} is
     * reset to 0 only when the incoming confidence rank is ≥ the stored rank.
     * If the WHERE clause does not match (incoming rank < stored rank) the
     * existing row is left unchanged — this is a PostgreSQL no-op UPDATE, not an error.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO website_compliance_paths
              (id, website_id, sub_type, discovered_url, anchor_href, anchor_text,
               dom_selector, source, confidence, discovered_at, last_verified_at, fail_count)
            VALUES
              (gen_random_uuid(), :websiteId, :subType, :discoveredUrl, :anchorHref, :anchorText,
               :domSelector, :source, :confidence, now(), now(), 0)
            ON CONFLICT (website_id, sub_type) DO UPDATE SET
              discovered_url   = EXCLUDED.discovered_url,
              anchor_href      = EXCLUDED.anchor_href,
              anchor_text      = EXCLUDED.anchor_text,
              dom_selector     = EXCLUDED.dom_selector,
              source           = EXCLUDED.source,
              confidence       = EXCLUDED.confidence,
              last_verified_at = now(),
              fail_count       = 0
            WHERE CASE EXCLUDED.confidence WHEN 'high' THEN 3 WHEN 'medium' THEN 2 ELSE 1 END
                >= CASE website_compliance_paths.confidence WHEN 'high' THEN 3 WHEN 'medium' THEN 2 ELSE 1 END
            """, nativeQuery = true)
    void upsert(
            @Param("websiteId") UUID websiteId,
            @Param("subType") String subType,
            @Param("discoveredUrl") String discoveredUrl,
            @Param("anchorHref") String anchorHref,
            @Param("anchorText") String anchorText,
            @Param("domSelector") String domSelector,
            @Param("source") String source,
            @Param("confidence") String confidence);

    /**
     * Fast-path verification ping — refreshes {@code last_verified_at} and
     * resets {@code fail_count} to 0 when the Go worker confirms the stored URL
     * is still live.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE website_compliance_paths SET last_verified_at = now(), fail_count = 0 WHERE website_id = :websiteId AND sub_type = :subType",
            nativeQuery = true)
    void recordVerification(@Param("websiteId") UUID websiteId, @Param("subType") String subType);

    /**
     * Increments {@code fail_count} and, in the same SQL statement, auto-deletes
     * the row once the counter reaches 3. A CTE runs the UPDATE first, then the
     * outer DELETE consults the post-increment value — one round-trip instead of
     * two. Returns the number of deleted rows (0 until the threshold is hit, 1
     * on the third failure).
     */
    @Modifying
    @Transactional
    @Query(value = """
            WITH updated AS (
                UPDATE website_compliance_paths
                SET fail_count = fail_count + 1
                WHERE website_id = :websiteId AND sub_type = :subType
                RETURNING fail_count
            )
            DELETE FROM website_compliance_paths
            WHERE website_id = :websiteId AND sub_type = :subType
              AND (SELECT fail_count FROM updated) >= 3
            """, nativeQuery = true)
    int incrementFailCountAndPurgeIfStale(@Param("websiteId") UUID websiteId, @Param("subType") String subType);

    @Modifying
    @Query(value = "DELETE FROM website_compliance_paths WHERE website_id IN (:websiteIds)", nativeQuery = true)
    int deleteByWebsiteIdIn(@Param("websiteIds") Collection<UUID> websiteIds);
}
