package watch.nabu.application.website;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watch.nabu.application.exception.WebsiteNotFoundException;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.persistence.ScanLogRepository;
import watch.nabu.infrastructure.persistence.WebsiteRepository;
import watch.nabu.infrastructure.web.dto.ComplianceTrendBucket;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebsiteQueryServiceComplianceTrendTest {

    private final UUID websiteId = UUID.randomUUID();
    @Mock
    WebsiteRepository websiteRepository;
    @Mock
    MonitorRepository monitorRepository;
    @Mock
    ScanLogRepository scanLogRepository;
    @Mock
    ObjectMapper objectMapper;
    @InjectMocks
    WebsiteQueryService service;

    @Test
    @DisplayName("throws WebsiteNotFoundException when website does not exist")
    void websiteNotFound() {
        when(websiteRepository.findById(websiteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getComplianceTrend(websiteId, "MONTHLY", 6, null))
                .isInstanceOf(WebsiteNotFoundException.class);
    }

    @Test
    @DisplayName("returns empty list when no scan data exists")
    void emptyResult() {
        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(new Website()));
        when(scanLogRepository.findComplianceTrendRaw(eq(websiteId), eq("MONTHLY"), any(Instant.class), isNull()))
                .thenReturn(Collections.emptyList());

        List<ComplianceTrendBucket> result = service.getComplianceTrend(websiteId, "MONTHLY", 6, null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("maps raw rows to trend buckets with correct pass rate")
    void happyPath() {
        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(new Website()));
        when(scanLogRepository.findComplianceTrendRaw(eq(websiteId), eq("MONTHLY"), any(Instant.class), isNull()))
                .thenReturn(List.of(
                        new Object[]{"2026-01", 10L, 7L, 3L},
                        new Object[]{"2026-02", 8L, 8L, 0L},
                        new Object[]{"2026-03", 5L, 0L, 5L}
                ));

        List<ComplianceTrendBucket> result = service.getComplianceTrend(websiteId, "MONTHLY", 6, null);

        assertThat(result).hasSize(3);

        assertThat(result.get(0).period()).isEqualTo("2026-01");
        assertThat(result.get(0).passRate()).isEqualByComparingTo(new BigDecimal("70.00"));

        assertThat(result.get(1).passRate()).isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(result.get(2).passRate()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(result.get(2).failingCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("passes subType filter to repository")
    void subTypeFilter() {
        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(new Website()));
        when(scanLogRepository.findComplianceTrendRaw(eq(websiteId), eq("WEEKLY"), any(Instant.class), eq("PRIVACY_POLICY")))
                .thenReturn(Collections.emptyList());

        service.getComplianceTrend(websiteId, "WEEKLY", 3, "PRIVACY_POLICY");

        verify(scanLogRepository).findComplianceTrendRaw(
                eq(websiteId), eq("WEEKLY"), any(Instant.class), eq("PRIVACY_POLICY"));
    }
}
