package watch.nabu.infrastructure.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.admin.BannerKeywordAdminService;
import watch.nabu.application.admin.ComplianceAdminService;
import watch.nabu.domain.model.BannerKeyword;
import watch.nabu.infrastructure.web.dto.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin REST API for the Compliance Matrix Builder.
 *
 * <p>HTTP-level access control is enforced by {@code hasRole("ADMIN")} on
 * {@code /api/v1/admin/**} in {@code SecurityConfig}.  Each service method
 * carries an additional {@code @PreAuthorize} as a second layer of defence.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET    /api/v1/admin/compliance/regions}                              — list all regions</li>
 *   <li>{@code PATCH  /api/v1/admin/compliance/regions/{id}}                         — toggle active flag</li>
 *   <li>{@code GET    /api/v1/admin/compliance/monitor-types}                        — list all document types</li>
 *   <li>{@code GET    /api/v1/admin/compliance/regions/{id}/rules}                   — rules for a region</li>
 *   <li>{@code PUT    /api/v1/admin/compliance/regions/{id}/rules/{monitorTypeId}}   — create or update a rule</li>
 *   <li>{@code DELETE /api/v1/admin/compliance/regions/{id}/rules/{monitorTypeId}}   — remove a rule</li>
 *   <li>{@code GET    /api/v1/admin/compliance/global-fallbacks}                     — list global fallback keywords</li>
 *   <li>{@code PUT    /api/v1/admin/compliance/global-fallbacks/{monitorTypeId}}     — replace fallback keywords</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/compliance")
@RequiredArgsConstructor
public class ComplianceAdminController {

    private final ComplianceAdminService complianceAdminService;
    private final BannerKeywordAdminService bannerKeywordAdminService;

    // ── Regions ────────────────────────────────────────────────────────────────

    @GetMapping("/regions")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ComplianceRegionResponse> listRegions() {
        return complianceAdminService.listRegions();
    }

    @PatchMapping("/regions/{regionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ComplianceRegionResponse patchRegionActive(
            @PathVariable UUID regionId,
            @RequestBody PatchRegionActiveRequest req) {
        return complianceAdminService.patchRegionActive(regionId, req.active());
    }

    // ── Monitor types ──────────────────────────────────────────────────────────

    @GetMapping("/monitor-types")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ComplianceMonitorTypeResponse> listMonitorTypes() {
        return complianceAdminService.listMonitorTypes();
    }

    // ── Region rules ───────────────────────────────────────────────────────────

    @GetMapping("/regions/{regionId}/rules")
    @PreAuthorize("hasRole('ADMIN')")
    public List<RegionRuleDetailResponse> listRegionRules(@PathVariable UUID regionId) {
        return complianceAdminService.listRegionRules(regionId);
    }

    @PutMapping("/regions/{regionId}/rules/{monitorTypeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public RegionRuleDetailResponse upsertRegionRule(
            @PathVariable UUID regionId,
            @PathVariable UUID monitorTypeId,
            @RequestBody UpsertRegionRuleRequest req) {
        return complianceAdminService.upsertRegionRule(regionId, monitorTypeId, req);
    }

    @DeleteMapping("/regions/{regionId}/rules/{monitorTypeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteRegionRule(
            @PathVariable UUID regionId,
            @PathVariable UUID monitorTypeId) {
        complianceAdminService.deleteRegionRule(regionId, monitorTypeId);
        return ResponseEntity.noContent().build();
    }

    // ── Global fallbacks ───────────────────────────────────────────────────────

    @GetMapping("/global-fallbacks")
    @PreAuthorize("hasRole('ADMIN')")
    public List<GlobalFallbackItemResponse> listGlobalFallbacks() {
        return complianceAdminService.listGlobalFallbacks();
    }

    @PutMapping("/global-fallbacks/{monitorTypeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public GlobalFallbackItemResponse updateGlobalFallback(
            @PathVariable UUID monitorTypeId,
            @RequestBody UpdateGlobalFallbackRequest req) {
        return complianceAdminService.updateGlobalFallback(monitorTypeId, req.keywords());
    }

    // ── Banner keywords ────────────────────────────────────────────────────────
    //
    // Cookie-banner keyword lists consumed by the Go worker's BannerScanner,
    // scoped per region + type (BANNER_PHRASE/ACCEPT/REJECT/PREF). The GLOBAL
    // region is a real row in compliance_regions, so these endpoints treat it
    // exactly like any ISO region.

    @GetMapping("/regions/{regionId}/banner-keywords")
    @PreAuthorize("hasRole('ADMIN')")
    public List<BannerKeywordGroupResponse> listBannerKeywordsForRegion(
            @PathVariable UUID regionId) {
        return bannerKeywordAdminService.listForRegion(regionId);
    }

    @PutMapping("/regions/{regionId}/banner-keywords/{keywordType}")
    @PreAuthorize("hasRole('ADMIN')")
    public BannerKeywordGroupResponse replaceBannerKeywordGroup(
            @PathVariable UUID regionId,
            @PathVariable BannerKeyword.KeywordType keywordType,
            @RequestBody UpdateBannerKeywordsRequest req) {
        return bannerKeywordAdminService.replaceGroup(regionId, keywordType, req.keywords());
    }
}
