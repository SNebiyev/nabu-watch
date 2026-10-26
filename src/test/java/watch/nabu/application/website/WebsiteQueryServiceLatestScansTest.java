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
import watch.nabu.infrastructure.persistence.*;
import watch.nabu.infrastructure.web.dto.LatestScanItem;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebsiteQueryServiceLatestScansTest {

    @Mock
    WebsiteRepository websiteRepository;
    @Mock
    MonitorRepository monitorRepository;
    @Mock
    ScanLogRepository scanLogRepository;
    @Mock
    MonitorStatusOverrideRepository overrideRepository;
    @Mock
    watch.nabu.infrastructure.config.ScanLimitsProperties scanLimits;
    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    WebsiteQueryService service;

    private User createUser() {
        User user = User.builder().fullName("Test").email("t@example.com").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private Organization createOrganization() {
        Organization org = Organization.builder().name("Test Org").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        return org;
    }

    private Website createWebsite(User user) {
        Organization org = createOrganization();
        Website website = Website.builder().organization(org).addedBy(user).url("https://example.com").regionCode("DE").build();
        ReflectionTestUtils.setField(website, "id", UUID.randomUUID());
        return website;
    }

    private Monitor createMonitor(Website website, Monitor.SubType subType, boolean active) {
        Monitor monitor = Monitor.builder()
                .website(website)
                .type(subType == Monitor.SubType.SSL ? Monitor.MonitorType.SSL : Monitor.MonitorType.COMPLIANCE)
                .subType(subType)
                .active(active)
                .build();
        ReflectionTestUtils.setField(monitor, "id", UUID.randomUUID());
        return monitor;
    }

    private ScanLog createScanLog(Monitor monitor, ScanLog.Severity severity) {
        ScanLog log = ScanLog.builder()
                .monitor(monitor)
                .severity(severity)
                .resultJson(Map.of())
                .build();
        ReflectionTestUtils.setField(log, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(log, "createdAt", Instant.now());
        return log;
    }

    @Test
    @DisplayName("Active monitor with scan result → status derived from severity")
    void activeMonitor_statusFromSeverity() {
        User user = createUser();
        Website website = createWebsite(user);
        Monitor ssl = createMonitor(website, Monitor.SubType.SSL, true);
        ScanLog scanLog = createScanLog(ssl, ScanLog.Severity.INFO);

        when(websiteRepository.findById(website.getId())).thenReturn(Optional.of(website));
        when(monitorRepository.findByWebsiteId(website.getId())).thenReturn(List.of(ssl));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(website.getId())).thenReturn(List.of(scanLog));

        List<LatestScanItem> result = service.getLatestScans(website.getId());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().status()).isEqualTo("PASSING");
    }

    @Test
    @DisplayName("Inactive monitor → status is INACTIVE regardless of scan severity")
    void inactiveMonitor_statusIsInactive() {
        User user = createUser();
        Website website = createWebsite(user);
        Monitor active = createMonitor(website, Monitor.SubType.SSL, true);
        Monitor inactive = createMonitor(website, Monitor.SubType.PRIVACY_POLICY, false);

        ScanLog activeScan = createScanLog(active, ScanLog.Severity.INFO);
        ScanLog inactiveScan = createScanLog(inactive, ScanLog.Severity.HIGH);

        when(websiteRepository.findById(website.getId())).thenReturn(Optional.of(website));
        when(monitorRepository.findByWebsiteId(website.getId())).thenReturn(List.of(active, inactive));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(website.getId()))
                .thenReturn(List.of(activeScan, inactiveScan));

        List<LatestScanItem> result = service.getLatestScans(website.getId());

        assertThat(result).hasSize(2);

        LatestScanItem sslItem = result.stream()
                .filter(i -> "SSL".equals(i.subType())).findFirst().orElseThrow();
        LatestScanItem ppItem = result.stream()
                .filter(i -> "PRIVACY_POLICY".equals(i.subType())).findFirst().orElseThrow();

        assertThat(sslItem.status()).isEqualTo("PASSING");
        assertThat(ppItem.status()).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("Inactive monitor with no scan log → status is still INACTIVE (not PENDING)")
    void inactiveMonitorNoScan_statusIsInactive() {
        User user = createUser();
        Website website = createWebsite(user);
        Monitor inactive = createMonitor(website, Monitor.SubType.COOKIE_POLICY, false);

        when(websiteRepository.findById(website.getId())).thenReturn(Optional.of(website));
        when(monitorRepository.findByWebsiteId(website.getId())).thenReturn(List.of(inactive));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(website.getId())).thenReturn(List.of());

        List<LatestScanItem> result = service.getLatestScans(website.getId());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().status()).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("Active monitor with CRITICAL severity → FAILING status")
    void activeMonitorCritical_failingStatus() {
        User user = createUser();
        Website website = createWebsite(user);
        Monitor monitor = createMonitor(website, Monitor.SubType.TERMS_OF_SERVICE, true);
        ScanLog scanLog = createScanLog(monitor, ScanLog.Severity.CRITICAL);

        when(websiteRepository.findById(website.getId())).thenReturn(Optional.of(website));
        when(monitorRepository.findByWebsiteId(website.getId())).thenReturn(List.of(monitor));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(website.getId())).thenReturn(List.of(scanLog));

        List<LatestScanItem> result = service.getLatestScans(website.getId());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().status()).isEqualTo("FAILING");
    }

    @Test
    @DisplayName("Active monitor with no scan log → PENDING status")
    void activeMonitorNoScan_pendingStatus() {
        User user = createUser();
        Website website = createWebsite(user);
        Monitor monitor = createMonitor(website, Monitor.SubType.IMPRESSUM, true);

        when(websiteRepository.findById(website.getId())).thenReturn(Optional.of(website));
        when(monitorRepository.findByWebsiteId(website.getId())).thenReturn(List.of(monitor));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(website.getId())).thenReturn(List.of());

        List<LatestScanItem> result = service.getLatestScans(website.getId());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().status()).isEqualTo("PENDING");
    }
}
