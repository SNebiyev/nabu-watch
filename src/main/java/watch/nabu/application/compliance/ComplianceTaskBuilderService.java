package watch.nabu.application.compliance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.ComplianceKeyword;
import watch.nabu.domain.model.ComplianceKeyword.KeywordTier;
import watch.nabu.domain.model.Monitor;
import watch.nabu.infrastructure.persistence.ComplianceKeywordRepository;
import watch.nabu.infrastructure.persistence.ComplianceRegionRepository;
import watch.nabu.infrastructure.persistence.RegionRuleRepository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Database-driven compliance rule resolver used by {@link watch.nabu.application.website.WebsiteService}
 * and {@link watch.nabu.infrastructure.redis.TaskProducer}.
 *
 * <h3>Region fallback</h3>
 * <p>When the requested region code is null, blank, or has no active rules in the DB
 * (unknown market), this service transparently falls back to the region flagged as
 * {@code globalDefault = true} (typically {@code "GLOBAL"}).  Callers never need to
 * handle the fallback themselves — they always receive a non-empty result (unless the
 * global default region itself has no rules, which would be a data misconfiguration).
 *
 * <h3>English-region heuristic</h3>
 * <p>For GB and US, the native keywords are already in English, so English-fallback
 * keywords would be redundant and trigger false {@code language_inconsistency} flags.
 * {@link #buildPayload} automatically suppresses fallback keywords for these regions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceTaskBuilderService {

    private static final Set<String> ENGLISH_REGIONS = Set.of("GB", "US");

    private final ComplianceRegionRepository regionRepo;
    private final RegionRuleRepository regionRuleRepo;
    private final ComplianceKeywordRepository keywordRepo;

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns the set of compliance sub-types that should be monitored for the given region.
     *
     * Called by {@link watch.nabu.application.website.WebsiteService} to determine
     * which {@link Monitor.SubType} values to create monitors for.
     *
     * @param regionCode ISO 3166-1 alpha-2 code (e.g. {@code "DE"}); may be {@code null}
     * @return insertion-ordered set of SubType values; empty only when the global default
     * region has no active rules (misconfiguration)
     */
    @Transactional(readOnly = true)
    public Set<Monitor.SubType> getSubTypesForRegion(String regionCode) {
        List<String> codes = findSubTypeCodes(regionCode);

        if (codes.isEmpty()) {
            log.warn("[compliance] no active rules found for regionCode='{}' " +
                    "(including global fallback) — monitor creation will be skipped", regionCode);
        }

        return codes.stream()
                .map(Monitor.SubType::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Builds the three-tier keyword payload for a single compliance scan task.
     *
     * Called by {@link watch.nabu.infrastructure.redis.TaskProducer} to populate
     * the keyword fields of each COMPLIANCE scan task message.
     *
     * @param regionCode ISO 3166-1 alpha-2 code (e.g. {@code "DE"}); may be {@code null}
     * @param subType    the compliance document type being scanned
     * @return payload with all three keyword tiers populated (lists are never {@code null})
     */
    @Transactional(readOnly = true)
    public ComplianceTaskPayload buildPayload(String regionCode, Monitor.SubType subType) {
        // COOKIE_BANNER and TRACKER_DETECTION are DOM/network-based (not keyword-based)
        // — the Go worker's BannerScanner / TrackerScanner do not use keywords.
        if (subType == Monitor.SubType.COOKIE_BANNER
                || subType == Monitor.SubType.TRACKER_DETECTION) {
            return ComplianceTaskPayload.empty();
        }

        String subTypeCode = subType.name();

        List<ComplianceKeyword> nativeKeywords = findNativeKeywords(regionCode, subTypeCode);

        if (nativeKeywords.isEmpty()) {
            log.warn("[compliance] no keywords found for regionCode='{}' subType='{}' " +
                            "(including global fallback) — task dispatched without keywords",
                    regionCode, subTypeCode);
            return ComplianceTaskPayload.empty();
        }

        List<String> required = nativeKeywords.stream()
                .filter(k -> k.getTier() == KeywordTier.MANDATORY_NATIVE)
                .map(ComplianceKeyword::getKeyword)
                .toList();

        List<String> optional = nativeKeywords.stream()
                .filter(k -> k.getTier() == KeywordTier.OPTIONAL_NATIVE)
                .map(ComplianceKeyword::getKeyword)
                .toList();

        // English-language regions already use English as their native keywords —
        // adding fallback keywords would trigger false language_inconsistency flags.
        boolean isEnglishRegion = regionCode != null
                && ENGLISH_REGIONS.contains(regionCode.toUpperCase());

        List<String> fallback = isEnglishRegion
                ? List.of()
                : keywordRepo.findFallbackKeywordsBySubTypeCode(subTypeCode);

        return new ComplianceTaskPayload(required, optional, fallback);
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    /**
     * Falls back to the global default region when the requested region has no active rules.
     */
    private List<String> findSubTypeCodes(String regionCode) {
        if (regionCode != null && !regionCode.isBlank()) {
            List<String> codes = regionRuleRepo
                    .findActiveSubTypeCodesByRegionCode(regionCode.toUpperCase());
            if (!codes.isEmpty()) return codes;
            log.debug("[compliance] no active rules for regionCode='{}' — falling back to global",
                    regionCode);
        }
        return regionRuleRepo.findActiveSubTypeCodesByRegionCode(globalDefaultCode());
    }

    /**
     * Tries the requested region first; falls back to the global default region
     * when the requested code is null, blank, or yields no native keywords.
     */
    private List<ComplianceKeyword> findNativeKeywords(String regionCode, String subTypeCode) {
        if (regionCode != null && !regionCode.isBlank()) {
            List<ComplianceKeyword> kws = keywordRepo
                    .findNativeByRegionAndSubType(regionCode.toUpperCase(), subTypeCode);
            if (!kws.isEmpty()) return kws;
            log.debug("[compliance] no native keywords for regionCode='{}' subType='{}' " +
                    "— falling back to global", regionCode, subTypeCode);
        }
        return keywordRepo.findNativeByRegionAndSubType(globalDefaultCode(), subTypeCode);
    }

    private String globalDefaultCode() {
        return regionRepo.findByGlobalDefaultTrue()
                .map(watch.nabu.domain.model.ComplianceRegion::getCode)
                .orElse("GLOBAL");
    }
}
