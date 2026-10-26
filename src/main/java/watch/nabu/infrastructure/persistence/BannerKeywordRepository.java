package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.BannerKeyword;
import watch.nabu.domain.model.BannerKeyword.KeywordType;

import java.util.List;
import java.util.UUID;

public interface BannerKeywordRepository extends JpaRepository<BannerKeyword, UUID> {

    /**
     * Returns every active keyword that applies to a scan for the given region:
     * the region's own rows plus the {@code GLOBAL} fallback rows, across all
     * four keyword types, in a single DB round-trip. When the caller passes
     * {@code GLOBAL}, the {@code IN} reduces to one value so globals are not
     * returned twice.
     */
    @Query("""
            SELECT bk FROM BannerKeyword bk
            JOIN bk.region r
            WHERE bk.active = true
              AND r.code IN (:regionCode, 'GLOBAL')
            """)
    List<BannerKeyword> findAllForScan(@Param("regionCode") String regionCode);

    /**
     * Returns a single region's own keywords without merging in the GLOBAL
     * fallback. Used by the admin editor to show exactly what belongs to the
     * region being edited.
     */
    @Query("""
            SELECT bk FROM BannerKeyword bk
            WHERE bk.region.id = :regionId
            ORDER BY bk.keywordType, bk.keyword
            """)
    List<BannerKeyword> findByRegionId(@Param("regionId") UUID regionId);

    /**
     * Deletes every row matching (regionId, keywordType). Used by the admin
     * endpoint before inserting a replacement set.
     */
    @Modifying
    @Query("""
            DELETE FROM BannerKeyword bk
            WHERE bk.region.id = :regionId
              AND bk.keywordType = :keywordType
            """)
    void deleteByRegionIdAndKeywordType(
            @Param("regionId") UUID regionId,
            @Param("keywordType") KeywordType keywordType
    );
}
