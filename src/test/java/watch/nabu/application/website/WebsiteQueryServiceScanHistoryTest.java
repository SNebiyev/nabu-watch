package watch.nabu.application.website;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.domain.model.*;
import watch.nabu.infrastructure.config.ScanLimitsProperties;
import watch.nabu.infrastructure.persistence.*;
import watch.nabu.infrastructure.web.dto.ScanBatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the configured scan-history limit in {@link WebsiteQueryService#getScanHistory}.
 */
@ExtendWith(MockitoExtension.class)
class WebsiteQueryServiceScanHistoryTest {

    @Mock
    WebsiteRepository websiteRepository;
    @Mock
    MonitorRepository monitorRepository;
    @Mock
    ScanLogRepository scanLogRepository;
    @Mock
    MonitorStatusOverrideRepository overrideRepository;
    @Mock
    ScanLimitsProperties scanLimits;
    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    WebsiteQueryService service;

    // ── helpers ────────────────────────────────────────────────────────────────

    private Organization createOrganization() {
        Organization org = Organization.builder().name("Test Org").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        return org;
    }

    private User createUser() {
        User user = User.builder()
                .fullName("Test User")
                .email("test@example.com")
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private Website createWebsite(User user) {
        Organization org = createOrganization();
        Website website = Website.builder()
                .organization(org)
                .addedBy(user)
                .url("https://example.com")
                .regionCode("DE")
                .build();
        ReflectionTestUtils.setField(website, "id", UUID.randomUUID());
        return website;
    }

    private Monitor createMonitor(Website website) {
        Monitor monitor = Monitor.builder()
                .website(website)
                .type(Monitor.MonitorType.COMPLIANCE)
                .subType(Monitor.SubType.PRIVACY_POLICY)
                .active(true)
                .build();
        ReflectionTestUtils.setField(monitor, "id", UUID.randomUUID());
        return monitor;
    }

    private ScanLog createScanLog(Monitor monitor, UUID batchId, Instant createdAt) {
        ScanLog log = ScanLog.builder()
                .monitor(monitor)
                .severity(ScanLog.Severity.INFO)
                .batchId(batchId)
                .resultJson(Map.of("status", "SUCCESS"))
                .build();
        ReflectionTestUtils.setField(log, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(log, "createdAt", createdAt);
        return log;
    }

    /**
     * Generates N scan logs with distinct batch IDs, simulating N batches.
     */
    private List<ScanLog> generateBatches(Monitor monitor, int count) {
        List<ScanLog> logs = new ArrayList<>();
        Instant base = Instant.now();
        for (int i = 0; i < count; i++) {
            logs.add(createScanLog(monitor, UUID.randomUUID(), base.minusSeconds(i * 600L)));
        }
        return logs;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    private List<ScanBatch> historyWithLimit(int limit, int batches) {
        User user = createUser();
        Website website = createWebsite(user);
        Monitor monitor = createMonitor(website);

        when(scanLimits.scanHistoryLimit()).thenReturn(limit);
        when(websiteRepository.findById(website.getId())).thenReturn(Optional.of(website));
        when(monitorRepository.findByWebsiteId(website.getId())).thenReturn(List.of(monitor));
        when(scanLogRepository.findRecentByWebsiteId(eq(website.getId()), anyInt()))
                .thenReturn(generateBatches(monitor, batches));

        return service.getScanHistory(website.getId());
    }

    @Test
    @DisplayName("limit=60 returns up to 60 batches")
    void limit60_returns60() {
        assertThat(historyWithLimit(60, 80)).hasSize(60);
    }

    @Test
    @DisplayName("default limit of 90 caps the history at 90 batches")
    void limit90_caps() {
        assertThat(historyWithLimit(90, 120)).hasSize(90);
    }

    @Test
    @DisplayName("Fewer batches than limit returns all available")
    void fewerBatchesThanLimit_returnsAll() {
        assertThat(historyWithLimit(90, 10)).hasSize(10);
    }
}
