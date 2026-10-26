package watch.nabu.application.admin;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.domain.model.BannerKeyword;
import watch.nabu.domain.model.BannerKeyword.KeywordType;
import watch.nabu.domain.model.ComplianceRegion;
import watch.nabu.infrastructure.persistence.BannerKeywordRepository;
import watch.nabu.infrastructure.persistence.ComplianceRegionRepository;
import watch.nabu.infrastructure.web.dto.BannerKeywordGroupResponse;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin CRUD for the {@code banner_keywords} table, used by the Compliance
 * Matrix Builder's per-region rule editor. Keywords are grouped by (region,
 * keyword type); the editor replaces one group at a time.
 *
 * <p>The {@code GLOBAL} region is a real row in {@code compliance_regions}
 * (seeded by the Liquibase changelog), so it behaves exactly like any ISO region —
 * the editor opens it via {@code /admin/compliance/{globalRegionId}} and
 * edits it through the same endpoints.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BannerKeywordAdminService {

    /** Maximum number of keywords accepted in a single replaceGroup call. */
    static final int MAX_KEYWORDS_PER_GROUP = 1000;

    /** Maximum byte-length of a single keyword (matches the varchar(255) DB constraint). */
    static final int MAX_KEYWORD_LENGTH = 255;

    private final BannerKeywordRepository bannerKeywordRepo;
    private final ComplianceRegionRepository regionRepo;

    /**
     * Returns the four banner keyword groups for a single region — empty lists
     * for any type that has no rows. Called by the region rule editor when it
     * renders the COOKIE_BANNER card.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public List<BannerKeywordGroupResponse> listForRegion(UUID regionId) {
        ComplianceRegion region = regionRepo.findById(regionId)
                .orElseThrow(() -> new EntityNotFoundException("Region not found: " + regionId));

        Map<KeywordType, List<String>> byType = new EnumMap<>(KeywordType.class);
        for (KeywordType type : KeywordType.values()) {
            byType.put(type, new ArrayList<>());
        }
        for (BannerKeyword bk : bannerKeywordRepo.findByRegionId(regionId)) {
            byType.get(bk.getKeywordType()).add(bk.getKeyword());
        }

        List<BannerKeywordGroupResponse> groups = new ArrayList<>(KeywordType.values().length);
        for (KeywordType type : KeywordType.values()) {
            groups.add(new BannerKeywordGroupResponse(
                    region.getCode(),
                    region.getName(),
                    type.name(),
                    byType.get(type)
            ));
        }
        return groups;
    }

    /**
     * Replaces the full set of keywords for the given (region, type) combo.
     * Empty {@code keywords} clears the group.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public BannerKeywordGroupResponse replaceGroup(
            UUID regionId, KeywordType keywordType, List<String> keywords) {

        List<String> rawKeywords = keywords == null ? List.<String>of() : keywords;
        if (rawKeywords.size() > MAX_KEYWORDS_PER_GROUP) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "banner keyword group exceeds " + MAX_KEYWORDS_PER_GROUP + " entries");
        }

        ComplianceRegion region = regionRepo.findById(regionId)
                .orElseThrow(() -> new EntityNotFoundException("Region not found: " + regionId));

        bannerKeywordRepo.deleteByRegionIdAndKeywordType(regionId, keywordType);

        List<String> cleaned = rawKeywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(k -> k.length() <= MAX_KEYWORD_LENGTH)
                .distinct()
                .toList();

        if (!cleaned.isEmpty()) {
            List<BannerKeyword> rows = cleaned.stream()
                    .map(kw -> BannerKeyword.builder()
                            .region(region)
                            .keywordType(keywordType)
                            .keyword(kw)
                            .active(true)
                            .build())
                    .toList();
            bannerKeywordRepo.saveAll(rows);
        }

        log.info("[admin] replaced banner keywords region={} type={} count={}",
                region.getCode(), keywordType, cleaned.size());
        return new BannerKeywordGroupResponse(
                region.getCode(), region.getName(), keywordType.name(), cleaned);
    }
}
