package watch.nabu.application.admin;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.domain.model.BannerKeyword;
import watch.nabu.domain.model.BannerKeyword.KeywordType;
import watch.nabu.domain.model.ComplianceRegion;
import watch.nabu.infrastructure.persistence.BannerKeywordRepository;
import watch.nabu.infrastructure.persistence.ComplianceRegionRepository;
import watch.nabu.infrastructure.web.dto.BannerKeywordGroupResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BannerKeywordAdminServiceTest {

    @Mock
    BannerKeywordRepository bannerKeywordRepo;
    @Mock
    ComplianceRegionRepository regionRepo;

    @InjectMocks
    BannerKeywordAdminService service;

    private ComplianceRegion brRegion;
    private UUID brRegionId;

    @BeforeEach
    void setUp() {
        brRegionId = UUID.randomUUID();
        brRegion = ComplianceRegion.builder().code("BR").name("Brazil").active(true).build();
        ReflectionTestUtils.setField(brRegion, "id", brRegionId);
    }

    private BannerKeyword row(KeywordType type, String keyword) {
        return BannerKeyword.builder()
                .region(brRegion).keywordType(type).keyword(keyword).active(true).build();
    }

    @Test
    void listForRegion_returnsAllFourGroupsEvenWhenEmpty() {
        when(regionRepo.findById(brRegionId)).thenReturn(Optional.of(brRegion));
        when(bannerKeywordRepo.findByRegionId(brRegionId)).thenReturn(List.of(
                row(KeywordType.ACCEPT, "aceitar"),
                row(KeywordType.REJECT, "recusar"),
                row(KeywordType.REJECT, "apenas necessários")
        ));

        List<BannerKeywordGroupResponse> groups = service.listForRegion(brRegionId);

        assertThat(groups).hasSize(4);
        assertThat(groups).allMatch(g -> "BR".equals(g.regionCode()));

        BannerKeywordGroupResponse accept = groups.stream()
                .filter(g -> "ACCEPT".equals(g.keywordType())).findFirst().orElseThrow();
        assertThat(accept.keywords()).containsExactly("aceitar");

        BannerKeywordGroupResponse reject = groups.stream()
                .filter(g -> "REJECT".equals(g.keywordType())).findFirst().orElseThrow();
        assertThat(reject.keywords()).containsExactly("recusar", "apenas necessários");

        BannerKeywordGroupResponse phrase = groups.stream()
                .filter(g -> "BANNER_PHRASE".equals(g.keywordType())).findFirst().orElseThrow();
        assertThat(phrase.keywords()).isEmpty();

        BannerKeywordGroupResponse pref = groups.stream()
                .filter(g -> "PREF".equals(g.keywordType())).findFirst().orElseThrow();
        assertThat(pref.keywords()).isEmpty();
    }

    @Test
    void listForRegion_unknownRegionThrows() {
        UUID bogus = UUID.randomUUID();
        when(regionRepo.findById(bogus)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listForRegion(bogus))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Region not found");
    }

    @Test
    void replaceGroup_deletesOldThenSavesNew() {
        when(regionRepo.findById(brRegionId)).thenReturn(Optional.of(brRegion));

        BannerKeywordGroupResponse result = service.replaceGroup(
                brRegionId, KeywordType.REJECT,
                List.of("recusar", "rejeitar", "apenas necessários"));

        verify(bannerKeywordRepo).deleteByRegionIdAndKeywordType(brRegionId, KeywordType.REJECT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BannerKeyword>> saveCaptor = ArgumentCaptor.forClass(List.class);
        verify(bannerKeywordRepo).saveAll(saveCaptor.capture());
        List<BannerKeyword> saved = saveCaptor.getValue();

        assertThat(saved).hasSize(3);
        assertThat(saved).allMatch(k -> k.getRegion() == brRegion);
        assertThat(saved).allMatch(k -> k.getKeywordType() == KeywordType.REJECT);
        assertThat(saved).extracting(BannerKeyword::getKeyword)
                .containsExactly("recusar", "rejeitar", "apenas necessários");

        assertThat(result.regionCode()).isEqualTo("BR");
        assertThat(result.keywords()).containsExactly("recusar", "rejeitar", "apenas necessários");
    }

    @Test
    void replaceGroup_cleansBlanksAndDuplicates() {
        when(regionRepo.findById(brRegionId)).thenReturn(Optional.of(brRegion));

        service.replaceGroup(brRegionId, KeywordType.REJECT,
                Arrays.asList("recusar", "  ", null, "RECUSAR", "rejeitar"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BannerKeyword>> saveCaptor = ArgumentCaptor.forClass(List.class);
        verify(bannerKeywordRepo).saveAll(saveCaptor.capture());
        List<BannerKeyword> saved = saveCaptor.getValue();

        assertThat(saved).extracting(BannerKeyword::getKeyword)
                .containsExactly("recusar", "rejeitar");
    }

    @Test
    void replaceGroup_emptyKeywordsClearsWithoutSaving() {
        when(regionRepo.findById(brRegionId)).thenReturn(Optional.of(brRegion));

        BannerKeywordGroupResponse result = service.replaceGroup(
                brRegionId, KeywordType.PREF, List.of());

        verify(bannerKeywordRepo).deleteByRegionIdAndKeywordType(brRegionId, KeywordType.PREF);
        verify(bannerKeywordRepo, never()).saveAll(any());
        assertThat(result.keywords()).isEmpty();
    }

    @Test
    void replaceGroup_unknownRegionThrows() {
        UUID bogus = UUID.randomUUID();
        when(regionRepo.findById(bogus)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.replaceGroup(bogus, KeywordType.ACCEPT, List.of("x")))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Region not found");
    }

    @Test
    void replaceGroup_exceedingSizeCapRejected() {
        List<String> tooMany = java.util.stream.IntStream
                .range(0, BannerKeywordAdminService.MAX_KEYWORDS_PER_GROUP + 1)
                .mapToObj(i -> "kw-" + i)
                .toList();

        assertThatThrownBy(() ->
                service.replaceGroup(brRegionId, KeywordType.ACCEPT, tooMany))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        verify(bannerKeywordRepo, never()).deleteByRegionIdAndKeywordType(any(), any());
        verify(bannerKeywordRepo, never()).saveAll(any());
    }

    @Test
    void replaceGroup_keywordExceedingMaxLengthIsDropped() {
        when(regionRepo.findById(brRegionId)).thenReturn(Optional.of(brRegion));

        String tooLong = "x".repeat(BannerKeywordAdminService.MAX_KEYWORD_LENGTH + 1);
        service.replaceGroup(brRegionId, KeywordType.REJECT, List.of("recusar", tooLong));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BannerKeyword>> saveCaptor = ArgumentCaptor.forClass(List.class);
        verify(bannerKeywordRepo).saveAll(saveCaptor.capture());
        assertThat(saveCaptor.getValue()).extracting(BannerKeyword::getKeyword)
                .containsExactly("recusar");
    }
}
