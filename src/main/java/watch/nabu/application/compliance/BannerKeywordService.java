package watch.nabu.application.compliance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.BannerKeyword;
import watch.nabu.domain.model.BannerKeyword.KeywordType;
import watch.nabu.infrastructure.persistence.BannerKeywordRepository;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the cookie-banner keyword lists that ride in a COOKIE_BANNER scan
 * task payload. For each keyword type the returned list is the union of the
 * website region's own rows and the global (region_id IS NULL) fallback rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BannerKeywordService {

    private final BannerKeywordRepository bannerKeywordRepo;

    /**
     * Builds the four-list banner keyword payload for a COOKIE_BANNER scan task.
     *
     * @param regionCode ISO 3166-1 alpha-2 code of the website's region;
     *                   {@code null} or blank means only global fallbacks apply
     * @return payload with all four lists populated (never {@code null});
     *         an entirely empty payload tells the Go worker to use its
     *         hardcoded defaults
     */
    @Transactional(readOnly = true)
    public BannerKeywordPayload buildPayload(String regionCode) {
        String normalized = (regionCode == null || regionCode.isBlank())
                ? "GLOBAL"
                : regionCode.toUpperCase();

        Map<KeywordType, List<String>> byType = new EnumMap<>(KeywordType.class);
        for (KeywordType type : KeywordType.values()) {
            byType.put(type, new ArrayList<>());
        }
        for (BannerKeyword bk : bannerKeywordRepo.findAllForScan(normalized)) {
            byType.get(bk.getKeywordType()).add(bk.getKeyword());
        }

        BannerKeywordPayload payload = new BannerKeywordPayload(
                byType.get(KeywordType.BANNER_PHRASE),
                byType.get(KeywordType.ACCEPT),
                byType.get(KeywordType.REJECT),
                byType.get(KeywordType.PREF)
        );

        if (payload.isEmpty()) {
            log.warn("[banner] no banner keywords found for regionCode='{}' (including global fallback) " +
                    "— COOKIE_BANNER task will use Go worker's hardcoded defaults", regionCode);
        }

        return payload;
    }
}
