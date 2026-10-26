package watch.nabu.application.admin;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.domain.model.*;
import watch.nabu.domain.model.ComplianceKeyword.KeywordTier;
import watch.nabu.infrastructure.persistence.*;
import watch.nabu.infrastructure.web.dto.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplianceAdminServiceTest {

    @Mock
    ComplianceRegionRepository regionRepo;
    @Mock
    ComplianceMonitorTypeRepository monitorTypeRepo;
    @Mock
    RegionRuleRepository ruleRepo;
    @Mock
    ComplianceKeywordRepository keywordRepo;

    @InjectMocks
    ComplianceAdminService service;

    private ComplianceRegion testRegion;
    private ComplianceMonitorType testMonitorType;
    private UUID regionId;
    private UUID monitorTypeId;

    @BeforeEach
    void setUp() {
        regionId = UUID.randomUUID();
        monitorTypeId = UUID.randomUUID();

        testRegion = ComplianceRegion.builder().code("DE").name("Germany").active(true).globalDefault(false).build();
        ReflectionTestUtils.setField(testRegion, "id", regionId);

        testMonitorType = ComplianceMonitorType.builder()
                .subTypeCode("PRIVACY_POLICY").defaultName("Privacy Policy").description("desc").build();
        ReflectionTestUtils.setField(testMonitorType, "id", monitorTypeId);
    }

    // ── listRegions ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("listRegions: returns sorted regions (global first, then by name)")
    void listRegions_returnsSorted() {
        ComplianceRegion global = ComplianceRegion.builder().code("GLOBAL").name("Global").active(true).globalDefault(true).build();
        ReflectionTestUtils.setField(global, "id", UUID.randomUUID());

        when(regionRepo.findAll()).thenReturn(List.of(testRegion, global));

        List<ComplianceRegionResponse> result = service.listRegions();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).code()).isEqualTo("GLOBAL"); // global first
    }

    // ── patchRegionActive ─────────────────────────────────────────────────────

    @Test
    @DisplayName("patchRegionActive: updates and returns region")
    void patchRegionActive_updatesAndReturns() {
        when(regionRepo.findById(regionId)).thenReturn(Optional.of(testRegion));

        service.patchRegionActive(regionId, false);

        verify(regionRepo).updateActive(regionId, false);
    }

    @Test
    @DisplayName("patchRegionActive: region not found throws")
    void patchRegionActive_notFound_throws() {
        when(regionRepo.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.patchRegionActive(UUID.randomUUID(), false))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── listMonitorTypes ──────────────────────────────────────────────────────

    @Test
    @DisplayName("listMonitorTypes: returns all monitor types")
    void listMonitorTypes_returnsAll() {
        when(monitorTypeRepo.findAll()).thenReturn(List.of(testMonitorType));

        List<ComplianceMonitorTypeResponse> result = service.listMonitorTypes();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).subTypeCode()).isEqualTo(testMonitorType.getSubTypeCode());
    }

    // ── listRegionRules ───────────────────────────────────────────────────────

    @Test
    @DisplayName("listRegionRules: returns rules with keywords")
    void listRegionRules_returnsWithKeywords() {
        when(regionRepo.findById(regionId)).thenReturn(Optional.of(testRegion));

        RegionRule rule = RegionRule.builder().region(testRegion).monitorType(testMonitorType)
                .mandatory(true).active(true).build();
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());

        ComplianceKeyword kw = ComplianceKeyword.builder()
                .keyword("Datenschutz").tier(KeywordTier.MANDATORY_NATIVE).build();
        when(ruleRepo.findByRegionIdWithMonitorType(regionId)).thenReturn(List.of(rule));
        when(keywordRepo.findByRegionRule(rule)).thenReturn(List.of(kw));

        List<RegionRuleDetailResponse> result = service.listRegionRules(regionId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).mandatoryKeywords()).contains("Datenschutz");
        assertThat(result.get(0).mandatory()).isTrue();
    }

    @Test
    @DisplayName("listRegionRules: region not found throws")
    void listRegionRules_regionNotFound_throws() {
        when(regionRepo.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listRegionRules(UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── upsertRegionRule ──────────────────────────────────────────────────────

    @Test
    @DisplayName("upsertRegionRule: creates new rule when not exists")
    void upsertRegionRule_createsNew() {
        when(regionRepo.findById(regionId)).thenReturn(Optional.of(testRegion));
        when(monitorTypeRepo.findById(monitorTypeId)).thenReturn(Optional.of(testMonitorType));
        when(ruleRepo.findByRegionAndMonitorType(testRegion, testMonitorType)).thenReturn(Optional.empty());

        RegionRule saved = RegionRule.builder().region(testRegion).monitorType(testMonitorType)
                .mandatory(true).active(true).build();
        ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
        when(ruleRepo.save(any())).thenReturn(saved);

        UpsertRegionRuleRequest req = new UpsertRegionRuleRequest(true, true,
                List.of("Privacy"), List.of("Data protection"));

        RegionRuleDetailResponse result = service.upsertRegionRule(regionId, monitorTypeId, req);

        assertThat(result.mandatory()).isTrue();
        verify(ruleRepo).save(any());
        verify(keywordRepo).deleteByRegionRule(saved);
        verify(keywordRepo).saveAll(anyList());
    }

    @Test
    @DisplayName("upsertRegionRule: updates existing rule")
    void upsertRegionRule_updatesExisting() {
        when(regionRepo.findById(regionId)).thenReturn(Optional.of(testRegion));
        when(monitorTypeRepo.findById(monitorTypeId)).thenReturn(Optional.of(testMonitorType));

        RegionRule existing = RegionRule.builder().region(testRegion).monitorType(testMonitorType)
                .mandatory(false).active(true).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(ruleRepo.findByRegionAndMonitorType(testRegion, testMonitorType))
                .thenReturn(Optional.of(existing));
        when(ruleRepo.findById(existing.getId())).thenReturn(Optional.of(existing));

        UpsertRegionRuleRequest req = new UpsertRegionRuleRequest(true, true, null, null);

        service.upsertRegionRule(regionId, monitorTypeId, req);

        verify(ruleRepo).updateMandatoryAndActive(existing.getId(), true, true);
    }

    // ── deleteRegionRule ──────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteRegionRule: deletes rule and its keywords")
    void deleteRegionRule_deletesRuleAndKeywords() {
        when(regionRepo.findById(regionId)).thenReturn(Optional.of(testRegion));
        when(monitorTypeRepo.findById(monitorTypeId)).thenReturn(Optional.of(testMonitorType));

        RegionRule rule = RegionRule.builder().region(testRegion).monitorType(testMonitorType).build();
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());
        when(ruleRepo.findByRegionAndMonitorType(testRegion, testMonitorType))
                .thenReturn(Optional.of(rule));

        service.deleteRegionRule(regionId, monitorTypeId);

        verify(keywordRepo).deleteByRegionRule(rule);
        verify(ruleRepo).delete(rule);
    }

    @Test
    @DisplayName("deleteRegionRule: silently returns when region or type not found")
    void deleteRegionRule_notFound_silentReturn() {
        when(regionRepo.findById(any())).thenReturn(Optional.empty());

        service.deleteRegionRule(UUID.randomUUID(), UUID.randomUUID());

        verify(ruleRepo, never()).delete(any());
    }

    // ── listGlobalFallbacks ───────────────────────────────────────────────────

    @Test
    @DisplayName("listGlobalFallbacks: returns monitor types with fallback keywords")
    void listGlobalFallbacks_returnsFallbackKeywords() {
        ComplianceKeyword kw = ComplianceKeyword.builder()
                .keyword("Privacy Policy").tier(KeywordTier.ENGLISH_FALLBACK).build();
        when(monitorTypeRepo.findAll()).thenReturn(List.of(testMonitorType));
        when(keywordRepo.findByMonitorTypeAndRegionRuleIsNull(testMonitorType)).thenReturn(List.of(kw));

        List<GlobalFallbackItemResponse> result = service.listGlobalFallbacks();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).fallbackKeywords()).contains("Privacy Policy");
    }

    // ── updateGlobalFallback ──────────────────────────────────────────────────

    @Test
    @DisplayName("updateGlobalFallback: replaces fallback keywords")
    void updateGlobalFallback_replacesKeywords() {
        when(monitorTypeRepo.findById(monitorTypeId)).thenReturn(Optional.of(testMonitorType));

        GlobalFallbackItemResponse result = service.updateGlobalFallback(
                monitorTypeId, List.of("Privacy Policy", "Terms of Use"));

        verify(keywordRepo).deleteByMonitorTypeAndRegionRuleIsNull(testMonitorType);
        verify(keywordRepo).saveAll(anyList());
        assertThat(result.fallbackKeywords()).containsExactly("Privacy Policy", "Terms of Use");
    }

    @Test
    @DisplayName("updateGlobalFallback: null keywords treated as empty")
    void updateGlobalFallback_nullKeywords_treatedAsEmpty() {
        when(monitorTypeRepo.findById(monitorTypeId)).thenReturn(Optional.of(testMonitorType));

        GlobalFallbackItemResponse result = service.updateGlobalFallback(monitorTypeId, null);

        assertThat(result.fallbackKeywords()).isEmpty();
    }

    @Test
    @DisplayName("updateGlobalFallback: monitor type not found throws")
    void updateGlobalFallback_notFound_throws() {
        when(monitorTypeRepo.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateGlobalFallback(UUID.randomUUID(), List.of()))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
