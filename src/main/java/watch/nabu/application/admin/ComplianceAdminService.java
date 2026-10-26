package watch.nabu.application.admin;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.*;
import watch.nabu.domain.model.ComplianceKeyword.KeywordTier;
import watch.nabu.infrastructure.persistence.*;
import watch.nabu.infrastructure.web.dto.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComplianceAdminService {

    private final ComplianceRegionRepository regionRepo;
    private final ComplianceMonitorTypeRepository monitorTypeRepo;
    private final RegionRuleRepository ruleRepo;
    private final ComplianceKeywordRepository keywordRepo;

    // ── Regions ────────────────────────────────────────────────────────────────

    private static List<String> coalesce(List<String> list) {
        return list == null ? List.of() : list;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<ComplianceRegionResponse> listRegions() {
        return regionRepo.findAll().stream()
                .sorted(Comparator
                        .comparing(ComplianceRegion::isGlobalDefault).reversed()
                        .thenComparing(ComplianceRegion::getName))
                .map(this::toRegionResponse)
                .toList();
    }

    // ── Monitor types ──────────────────────────────────────────────────────────

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ComplianceRegionResponse patchRegionActive(UUID regionId, boolean active) {
        ComplianceRegion region = regionRepo.findById(regionId)
                .orElseThrow(() -> new EntityNotFoundException("Region not found: " + regionId));
        regionRepo.updateActive(regionId, active);
        // Re-fetch to return up-to-date state
        ComplianceRegion updated = regionRepo.findById(regionId).orElseThrow();
        return toRegionResponse(updated);
    }

    // ── Region rules ───────────────────────────────────────────────────────────

    @PreAuthorize("hasRole('ADMIN')")
    public List<ComplianceMonitorTypeResponse> listMonitorTypes() {
        return monitorTypeRepo.findAll().stream()
                .map(this::toMonitorTypeResponse)
                .toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<RegionRuleDetailResponse> listRegionRules(UUID regionId) {
        regionRepo.findById(regionId)
                .orElseThrow(() -> new EntityNotFoundException("Region not found: " + regionId));

        return ruleRepo.findByRegionIdWithMonitorType(regionId).stream()
                .map(rule -> {
                    List<ComplianceKeyword> keywords = keywordRepo.findByRegionRule(rule);
                    List<String> mandatory = keywords.stream()
                            .filter(k -> k.getTier() == KeywordTier.MANDATORY_NATIVE)
                            .map(ComplianceKeyword::getKeyword)
                            .toList();
                    List<String> optional = keywords.stream()
                            .filter(k -> k.getTier() == KeywordTier.OPTIONAL_NATIVE)
                            .map(ComplianceKeyword::getKeyword)
                            .toList();
                    ComplianceMonitorType mt = rule.getMonitorType();
                    return new RegionRuleDetailResponse(
                            rule.getId(),
                            mt.getId(),
                            mt.getSubTypeCode(),
                            mt.getDefaultName(),
                            mt.getDescription(),
                            rule.isActive(),
                            rule.isMandatory(),
                            mandatory,
                            optional
                    );
                })
                .toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RegionRuleDetailResponse upsertRegionRule(
            UUID regionId, UUID monitorTypeId, UpsertRegionRuleRequest req) {

        ComplianceRegion region = regionRepo.findById(regionId)
                .orElseThrow(() -> new EntityNotFoundException("Region not found: " + regionId));
        ComplianceMonitorType monitorType = monitorTypeRepo.findById(monitorTypeId)
                .orElseThrow(() -> new EntityNotFoundException("Monitor type not found: " + monitorTypeId));

        RegionRule rule = ruleRepo.findByRegionAndMonitorType(region, monitorType)
                .orElse(null);

        if (rule == null) {
            rule = ruleRepo.save(RegionRule.builder()
                    .region(region)
                    .monitorType(monitorType)
                    .mandatory(req.mandatory())
                    .active(req.active())
                    .build());
        } else {
            ruleRepo.updateMandatoryAndActive(rule.getId(), req.mandatory(), req.active());
            // Refresh after the bulk update so the returned state is current
            rule = ruleRepo.findById(rule.getId()).orElseThrow();
        }

        // Replace all keywords
        keywordRepo.deleteByRegionRule(rule);

        List<ComplianceKeyword> newKeywords = new ArrayList<>();
        for (String kw : coalesce(req.mandatoryKeywords())) {
            newKeywords.add(ComplianceKeyword.builder()
                    .regionRule(rule)
                    .keyword(kw)
                    .tier(KeywordTier.MANDATORY_NATIVE)
                    .build());
        }
        for (String kw : coalesce(req.optionalKeywords())) {
            newKeywords.add(ComplianceKeyword.builder()
                    .regionRule(rule)
                    .keyword(kw)
                    .tier(KeywordTier.OPTIONAL_NATIVE)
                    .build());
        }
        keywordRepo.saveAll(newKeywords);

        return new RegionRuleDetailResponse(
                rule.getId(),
                monitorType.getId(),
                monitorType.getSubTypeCode(),
                monitorType.getDefaultName(),
                monitorType.getDescription(),
                rule.isActive(),
                rule.isMandatory(),
                coalesce(req.mandatoryKeywords()),
                coalesce(req.optionalKeywords())
        );
    }

    // ── Global fallbacks ───────────────────────────────────────────────────────

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void deleteRegionRule(UUID regionId, UUID monitorTypeId) {
        ComplianceRegion region = regionRepo.findById(regionId).orElse(null);
        ComplianceMonitorType monitorType = monitorTypeRepo.findById(monitorTypeId).orElse(null);
        if (region == null || monitorType == null) return;

        ruleRepo.findByRegionAndMonitorType(region, monitorType).ifPresent(rule -> {
            keywordRepo.deleteByRegionRule(rule);
            ruleRepo.delete(rule);
        });
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<GlobalFallbackItemResponse> listGlobalFallbacks() {
        return monitorTypeRepo.findAll().stream()
                .map(mt -> {
                    List<String> keywords = keywordRepo
                            .findByMonitorTypeAndRegionRuleIsNull(mt)
                            .stream()
                            .map(ComplianceKeyword::getKeyword)
                            .toList();
                    return new GlobalFallbackItemResponse(
                            mt.getId(),
                            mt.getSubTypeCode(),
                            mt.getDefaultName(),
                            mt.getDescription(),
                            keywords
                    );
                })
                .toList();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public GlobalFallbackItemResponse updateGlobalFallback(UUID monitorTypeId, List<String> keywords) {
        ComplianceMonitorType monitorType = monitorTypeRepo.findById(monitorTypeId)
                .orElseThrow(() -> new EntityNotFoundException("Monitor type not found: " + monitorTypeId));

        keywordRepo.deleteByMonitorTypeAndRegionRuleIsNull(monitorType);

        List<ComplianceKeyword> newKeywords = coalesce(keywords).stream()
                .map(kw -> ComplianceKeyword.builder()
                        .monitorType(monitorType)
                        .keyword(kw)
                        .tier(KeywordTier.ENGLISH_FALLBACK)
                        .build())
                .toList();
        keywordRepo.saveAll(newKeywords);

        return new GlobalFallbackItemResponse(
                monitorType.getId(),
                monitorType.getSubTypeCode(),
                monitorType.getDefaultName(),
                monitorType.getDescription(),
                coalesce(keywords)
        );
    }

    private ComplianceRegionResponse toRegionResponse(ComplianceRegion r) {
        return new ComplianceRegionResponse(
                r.getId(), r.getCode(), r.getName(), r.isActive(), r.isGlobalDefault());
    }

    private ComplianceMonitorTypeResponse toMonitorTypeResponse(ComplianceMonitorType mt) {
        return new ComplianceMonitorTypeResponse(
                mt.getId(), mt.getSubTypeCode(), mt.getDefaultName(),
                mt.getDescription());
    }
}
