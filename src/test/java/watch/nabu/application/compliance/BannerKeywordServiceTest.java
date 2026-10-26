package watch.nabu.application.compliance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watch.nabu.domain.model.BannerKeyword;
import watch.nabu.domain.model.BannerKeyword.KeywordType;
import watch.nabu.domain.model.ComplianceRegion;
import watch.nabu.infrastructure.persistence.BannerKeywordRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BannerKeywordServiceTest {

    @Mock
    BannerKeywordRepository bannerKeywordRepo;

    @InjectMocks
    BannerKeywordService service;

    private static BannerKeyword kw(ComplianceRegion region, KeywordType type, String keyword) {
        return BannerKeyword.builder()
                .region(region)
                .keywordType(type)
                .keyword(keyword)
                .active(true)
                .build();
    }

    @Test
    void buildPayload_mergesRegionAndGlobalKeywordsAcrossAllFourTypes() {
        ComplianceRegion br = ComplianceRegion.builder().code("BR").name("Brazil").build();
        ComplianceRegion global = ComplianceRegion.builder()
                .code("GLOBAL").name("Global (Default)").globalDefault(true).build();

        when(bannerKeywordRepo.findAllForScan("BR")).thenReturn(List.of(
                kw(global, KeywordType.BANNER_PHRASE, "this site uses cookies"),
                kw(global, KeywordType.ACCEPT, "accept"),
                kw(global, KeywordType.REJECT, "reject"),
                kw(global, KeywordType.PREF, "settings"),
                kw(br, KeywordType.BANNER_PHRASE, "este site utiliza cookies"),
                kw(br, KeywordType.ACCEPT, "aceitar"),
                kw(br, KeywordType.REJECT, "recusar"),
                kw(br, KeywordType.REJECT, "apenas necessários"),
                kw(br, KeywordType.PREF, "configurar")
        ));

        BannerKeywordPayload payload = service.buildPayload("BR");

        assertThat(payload.bannerPhrases()).containsExactlyInAnyOrder(
                "this site uses cookies", "este site utiliza cookies");
        assertThat(payload.acceptTerms()).containsExactlyInAnyOrder("accept", "aceitar");
        assertThat(payload.rejectTerms()).containsExactlyInAnyOrder(
                "reject", "recusar", "apenas necessários");
        assertThat(payload.prefTerms()).containsExactlyInAnyOrder("settings", "configurar");
        assertThat(payload.isEmpty()).isFalse();
    }

    @Test
    void buildPayload_normalisesRegionCodeToUppercase() {
        ComplianceRegion global = ComplianceRegion.builder().code("GLOBAL").name("Global").build();
        when(bannerKeywordRepo.findAllForScan("BR"))
                .thenReturn(List.of(kw(global, KeywordType.BANNER_PHRASE, "pt-kw")));

        BannerKeywordPayload payload = service.buildPayload("br");

        assertThat(payload.bannerPhrases()).containsExactly("pt-kw");
    }

    @Test
    void buildPayload_nullRegionCodeResolvesToGlobal() {
        ComplianceRegion global = ComplianceRegion.builder().code("GLOBAL").name("Global").build();
        when(bannerKeywordRepo.findAllForScan("GLOBAL")).thenReturn(List.of(
                kw(global, KeywordType.BANNER_PHRASE, "this site uses cookies"),
                kw(global, KeywordType.ACCEPT, "accept"),
                kw(global, KeywordType.REJECT, "reject"),
                kw(global, KeywordType.PREF, "settings")
        ));

        BannerKeywordPayload payload = service.buildPayload(null);

        assertThat(payload.bannerPhrases()).containsExactly("this site uses cookies");
        assertThat(payload.acceptTerms()).containsExactly("accept");
        assertThat(payload.rejectTerms()).containsExactly("reject");
        assertThat(payload.prefTerms()).containsExactly("settings");
    }

    @Test
    void buildPayload_emptyResultYieldsEmptyPayload() {
        when(bannerKeywordRepo.findAllForScan("XX")).thenReturn(List.of());

        BannerKeywordPayload payload = service.buildPayload("XX");

        assertThat(payload.isEmpty()).isTrue();
        assertThat(payload.bannerPhrases()).isEmpty();
        assertThat(payload.acceptTerms()).isEmpty();
        assertThat(payload.rejectTerms()).isEmpty();
        assertThat(payload.prefTerms()).isEmpty();
    }
}
