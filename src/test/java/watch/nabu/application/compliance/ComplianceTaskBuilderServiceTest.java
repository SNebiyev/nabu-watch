package watch.nabu.application.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watch.nabu.domain.model.ComplianceKeyword;
import watch.nabu.domain.model.ComplianceKeyword.KeywordTier;
import watch.nabu.domain.model.ComplianceRegion;
import watch.nabu.domain.model.Monitor;
import watch.nabu.infrastructure.persistence.ComplianceKeywordRepository;
import watch.nabu.infrastructure.persistence.ComplianceRegionRepository;
import watch.nabu.infrastructure.persistence.RegionRuleRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplianceTaskBuilderServiceTest {

    @Mock
    ComplianceRegionRepository regionRepo;
    @Mock
    RegionRuleRepository regionRuleRepo;
    @Mock
    ComplianceKeywordRepository keywordRepo;

    @InjectMocks
    ComplianceTaskBuilderService service;

    private void stubGlobalDefault(String code) {
        ComplianceRegion global = ComplianceRegion.builder().code(code).name("Global").globalDefault(true).build();
        when(regionRepo.findByGlobalDefaultTrue()).thenReturn(Optional.of(global));
    }

    // ── getSubTypesForRegion (no plan filter) ─────────────────────────────────

    @Test
    @DisplayName("getSubTypesForRegion: returns sub-types for known region")
    void getSubTypesForRegion_knownRegion() {
        when(regionRuleRepo.findActiveSubTypeCodesByRegionCode("DE"))
                .thenReturn(List.of("PRIVACY_POLICY", "IMPRESSUM"));

        Set<Monitor.SubType> result = service.getSubTypesForRegion("DE");

        assertThat(result).containsExactlyInAnyOrder(Monitor.SubType.PRIVACY_POLICY, Monitor.SubType.IMPRESSUM);
    }

    @Test
    @DisplayName("getSubTypesForRegion: falls back to global when region has no rules")
    void getSubTypesForRegion_fallbackToGlobal() {
        when(regionRuleRepo.findActiveSubTypeCodesByRegionCode("XX")).thenReturn(List.of());
        stubGlobalDefault("GLOBAL");
        when(regionRuleRepo.findActiveSubTypeCodesByRegionCode("GLOBAL"))
                .thenReturn(List.of("PRIVACY_POLICY"));

        Set<Monitor.SubType> result = service.getSubTypesForRegion("XX");

        assertThat(result).containsExactly(Monitor.SubType.PRIVACY_POLICY);
    }

    @Test
    @DisplayName("getSubTypesForRegion: null region code falls back to global")
    void getSubTypesForRegion_nullRegion() {
        stubGlobalDefault("GLOBAL");
        when(regionRuleRepo.findActiveSubTypeCodesByRegionCode("GLOBAL"))
                .thenReturn(List.of("COOKIE_POLICY"));

        Set<Monitor.SubType> result = service.getSubTypesForRegion(null);

        assertThat(result).containsExactly(Monitor.SubType.COOKIE_POLICY);
    }

    @Test
    @DisplayName("getSubTypesForRegion: blank region code falls back to global")
    void getSubTypesForRegion_blankRegion() {
        stubGlobalDefault("GLOBAL");
        when(regionRuleRepo.findActiveSubTypeCodesByRegionCode("GLOBAL")).thenReturn(List.of());

        Set<Monitor.SubType> result = service.getSubTypesForRegion("  ");

        assertThat(result).isEmpty();
    }

    // ── buildPayload ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildPayload: returns empty payload for COOKIE_BANNER")
    void buildPayload_cookieBanner_returnsEmpty() {
        ComplianceTaskPayload payload = service.buildPayload("DE", Monitor.SubType.COOKIE_BANNER);

        assertThat(payload.requiredKeywords()).isEmpty();
        assertThat(payload.optionalKeywords()).isEmpty();
        assertThat(payload.fallbackKeywords()).isEmpty();
        verifyNoInteractions(keywordRepo);
    }

    @Test
    @DisplayName("buildPayload: returns empty payload for TRACKER_DETECTION")
    void buildPayload_trackerDetection_returnsEmpty() {
        ComplianceTaskPayload payload = service.buildPayload("DE", Monitor.SubType.TRACKER_DETECTION);

        assertThat(payload.requiredKeywords()).isEmpty();
        verifyNoInteractions(keywordRepo);
    }

    @Test
    @DisplayName("buildPayload: separates mandatory and optional keywords")
    void buildPayload_separatesKeywordTiers() {
        ComplianceKeyword mandatory = ComplianceKeyword.builder()
                .keyword("Datenschutz").tier(KeywordTier.MANDATORY_NATIVE).build();
        ComplianceKeyword optional = ComplianceKeyword.builder()
                .keyword("Datenschutzbeauftragter").tier(KeywordTier.OPTIONAL_NATIVE).build();

        when(keywordRepo.findNativeByRegionAndSubType("DE", "PRIVACY_POLICY"))
                .thenReturn(List.of(mandatory, optional));
        when(keywordRepo.findFallbackKeywordsBySubTypeCode("PRIVACY_POLICY"))
                .thenReturn(List.of("Privacy Policy"));

        ComplianceTaskPayload payload = service.buildPayload("DE", Monitor.SubType.PRIVACY_POLICY);

        assertThat(payload.requiredKeywords()).containsExactly("Datenschutz");
        assertThat(payload.optionalKeywords()).containsExactly("Datenschutzbeauftragter");
        assertThat(payload.fallbackKeywords()).containsExactly("Privacy Policy");
    }

    @Test
    @DisplayName("buildPayload: English region suppresses fallback keywords")
    void buildPayload_englishRegion_suppressesFallback() {
        ComplianceKeyword mandatory = ComplianceKeyword.builder()
                .keyword("Privacy Policy").tier(KeywordTier.MANDATORY_NATIVE).build();
        when(keywordRepo.findNativeByRegionAndSubType("GB", "PRIVACY_POLICY"))
                .thenReturn(List.of(mandatory));

        ComplianceTaskPayload payload = service.buildPayload("GB", Monitor.SubType.PRIVACY_POLICY);

        assertThat(payload.fallbackKeywords()).isEmpty();
        verify(keywordRepo, never()).findFallbackKeywordsBySubTypeCode(any());
    }

    @Test
    @DisplayName("buildPayload: US region also suppresses fallback keywords")
    void buildPayload_usRegion_suppressesFallback() {
        ComplianceKeyword mandatory = ComplianceKeyword.builder()
                .keyword("Privacy Policy").tier(KeywordTier.MANDATORY_NATIVE).build();
        when(keywordRepo.findNativeByRegionAndSubType("US", "PRIVACY_POLICY"))
                .thenReturn(List.of(mandatory));

        ComplianceTaskPayload payload = service.buildPayload("US", Monitor.SubType.PRIVACY_POLICY);

        assertThat(payload.fallbackKeywords()).isEmpty();
    }

    @Test
    @DisplayName("buildPayload: no native keywords falls back to global")
    void buildPayload_noNativeKeywords_fallbackToGlobal() {
        when(keywordRepo.findNativeByRegionAndSubType("XX", "PRIVACY_POLICY")).thenReturn(List.of());
        stubGlobalDefault("GLOBAL");
        when(keywordRepo.findNativeByRegionAndSubType("GLOBAL", "PRIVACY_POLICY")).thenReturn(List.of());

        ComplianceTaskPayload payload = service.buildPayload("XX", Monitor.SubType.PRIVACY_POLICY);

        assertThat(payload).isEqualTo(ComplianceTaskPayload.empty());
    }

    @Test
    @DisplayName("buildPayload: null region code falls back to global default")
    void buildPayload_nullRegion_fallbackToGlobal() {
        stubGlobalDefault("GLOBAL");
        ComplianceKeyword mandatory = ComplianceKeyword.builder()
                .keyword("Privacy").tier(KeywordTier.MANDATORY_NATIVE).build();
        when(keywordRepo.findNativeByRegionAndSubType("GLOBAL", "PRIVACY_POLICY"))
                .thenReturn(List.of(mandatory));
        when(keywordRepo.findFallbackKeywordsBySubTypeCode("PRIVACY_POLICY")).thenReturn(List.of());

        ComplianceTaskPayload payload = service.buildPayload(null, Monitor.SubType.PRIVACY_POLICY);

        assertThat(payload.requiredKeywords()).containsExactly("Privacy");
    }

    @Test
    @DisplayName("buildPayload: case-insensitive english region check (lowercase 'gb')")
    void buildPayload_lowercaseEnglishRegion() {
        ComplianceKeyword mandatory = ComplianceKeyword.builder()
                .keyword("Privacy Policy").tier(KeywordTier.MANDATORY_NATIVE).build();
        when(keywordRepo.findNativeByRegionAndSubType("GB", "PRIVACY_POLICY"))
                .thenReturn(List.of(mandatory));

        // "gb" is uppercased before checking ENGLISH_REGIONS
        ComplianceTaskPayload payload = service.buildPayload("gb", Monitor.SubType.PRIVACY_POLICY);

        assertThat(payload.fallbackKeywords()).isEmpty();
    }
}
