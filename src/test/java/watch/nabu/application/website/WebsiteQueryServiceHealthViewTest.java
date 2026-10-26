package watch.nabu.application.website;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.exception.WebsiteNotFoundException;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.domain.model.Website;
import watch.nabu.domain.model.WebsiteHealth.HealthColor;
import watch.nabu.infrastructure.persistence.*;
import watch.nabu.infrastructure.web.dto.MonitorConfigItem;
import watch.nabu.infrastructure.web.dto.WebsiteHealthListItem;
import watch.nabu.infrastructure.web.dto.WebsitePageResponse;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebsiteQueryServiceHealthViewTest {

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

    private UUID websiteId;
    private Website testWebsite;
    private Organization testOrg;

    @BeforeEach
    void setUp() {
        websiteId = UUID.randomUUID();
        testOrg = Organization.builder().name("Org").build();
        ReflectionTestUtils.setField(testOrg, "id", UUID.randomUUID());

        testWebsite = Website.builder()
                .url("https://example.de").regionCode("DE")
                .organization(testOrg).build();
        ReflectionTestUtils.setField(testWebsite, "id", websiteId);

        // Default stub for the 24h status-change counts (used by listHealthPaged*)
        org.mockito.Mockito.lenient()
                .when(scanLogRepository.countStatusChanges(any(), any()))
                .thenReturn(new Object[]{new Object[]{0L, 0L}});
    }

    private Monitor buildMonitor(MonitorType type, SubType subType) {
        Monitor m = Monitor.builder()
                .website(testWebsite).type(type).subType(subType)
                .intervalMinutes(1440).active(true).build();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(m, "createdAt", Instant.now());
        return m;
    }

    private ScanLog buildScanLog(Monitor monitor, Severity severity, Map<String, Object> resultJson) {
        ScanLog sl = ScanLog.builder()
                .monitor(monitor).severity(severity).resultJson(resultJson).build();
        ReflectionTestUtils.setField(sl, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(sl, "createdAt", Instant.now());
        return sl;
    }

    // ── getHealthView ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getHealthView: returns GREEN when all monitors INFO")
    void getHealthView_allInfo_green() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);
        ScanLog sslLog = buildScanLog(ssl, Severity.INFO, Map.of("status", "SUCCESS", "days_remaining", "90"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(ssl));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(sslLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.color()).isEqualTo(HealthColor.GREEN);
        assertThat(view.monitors()).hasSize(1);
        assertThat(view.monitors().get(0).summary()).contains("SSL certificate expires in 90 days");
    }

    @Test
    @DisplayName("getHealthView: returns YELLOW when MEDIUM severity")
    void getHealthView_medium_yellow() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);
        ScanLog sslLog = buildScanLog(ssl, Severity.MEDIUM, Map.of("status", "SUCCESS", "days_remaining", "10"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(ssl));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(sslLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.color()).isEqualTo(HealthColor.YELLOW);
    }

    @Test
    @DisplayName("getHealthView: returns RED when CRITICAL severity")
    void getHealthView_critical_red() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);
        ScanLog sslLog = buildScanLog(ssl, Severity.CRITICAL, Map.of("status", "SUCCESS", "days_remaining", "0"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(ssl));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(sslLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.color()).isEqualTo(HealthColor.RED);
    }

    @Test
    @DisplayName("getHealthView: no scan data shows 'No scan data yet'")
    void getHealthView_noScanData_pendingSummary() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(ssl));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of());

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.monitors().get(0).summary()).isEqualTo("No scan data yet");
    }

    @Test
    @DisplayName("getHealthView: website not found throws")
    void getHealthView_notFound_throws() {
        when(websiteRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHealthView(UUID.randomUUID()))
                .isInstanceOf(WebsiteNotFoundException.class);
    }

    @Test
    @DisplayName("getHealthView: SSL FAILED scan shows error")
    void getHealthView_sslFailed_showsError() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);
        ScanLog sslLog = buildScanLog(ssl, Severity.HIGH,
                Map.of("status", "FAILED", "error", "connection timeout"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(ssl));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(sslLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.monitors().get(0).summary()).contains("connection timeout");
    }

    @Test
    @DisplayName("getHealthView: compliance missing keywords shows them")
    void getHealthView_complianceMissing_showsKeywords() {
        Monitor pp = buildMonitor(MonitorType.COMPLIANCE, SubType.PRIVACY_POLICY);
        ScanLog ppLog = buildScanLog(pp, Severity.HIGH,
                Map.of("status", "SUCCESS", "missing_keywords", "[\"Datenschutz\",\"Impressum\"]"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(pp));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(ppLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.monitors().get(0).summary()).contains("Non-compliant")
                .contains("Datenschutz").contains("Impressum");
    }

    @Test
    @DisplayName("getHealthView: compliance all found shows 'Compliant'")
    void getHealthView_complianceAllFound_compliant() {
        Monitor pp = buildMonitor(MonitorType.COMPLIANCE, SubType.PRIVACY_POLICY);
        ScanLog ppLog = buildScanLog(pp, Severity.INFO,
                Map.of("status", "SUCCESS", "missing_keywords", "[]",
                        "found_keywords", "[\"Datenschutz\"]"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(pp));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(ppLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.monitors().get(0).summary()).contains("Compliant");
    }

    @Test
    @DisplayName("getHealthView: SSL certificate expired")
    void getHealthView_sslExpired() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);
        ScanLog sslLog = buildScanLog(ssl, Severity.CRITICAL,
                Map.of("status", "SUCCESS", "days_remaining", "-1"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(ssl));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(sslLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.monitors().get(0).summary()).contains("EXPIRED");
    }

    @Test
    @DisplayName("getHealthView: SSL 1 day remaining")
    void getHealthView_ssl1Day() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);
        ScanLog sslLog = buildScanLog(ssl, Severity.CRITICAL,
                Map.of("status", "SUCCESS", "days_remaining", "1"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(ssl));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(sslLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.monitors().get(0).summary()).isEqualTo("SSL certificate expires in 1 day");
    }

    @Test
    @DisplayName("getHealthView: SSL no days_remaining")
    void getHealthView_sslNoDaysRemaining() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);
        ScanLog sslLog = buildScanLog(ssl, Severity.INFO, Map.of("status", "SUCCESS"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(ssl));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(sslLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.monitors().get(0).summary()).isEqualTo("SSL scan completed");
    }

    @Test
    @DisplayName("getHealthView: SSL non-numeric days_remaining")
    void getHealthView_sslNonNumericDays() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);
        ScanLog sslLog = buildScanLog(ssl, Severity.INFO,
                Map.of("status", "SUCCESS", "days_remaining", "N/A"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(ssl));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(sslLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.monitors().get(0).summary()).isEqualTo("SSL scan completed");
    }

    // ── getHealthListItemById ─────────────────────────────────────────────────

    @Test
    @DisplayName("getHealthListItemById: returns health item for website")
    void getHealthListItemById_returnsItem() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);
        ScanLog sslLog = buildScanLog(ssl, Severity.INFO, Map.of());

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(ssl));
        when(scanLogRepository.findLatestPerMonitorByWebsiteIds(List.of(websiteId)))
                .thenReturn(List.of(sslLog));

        WebsiteHealthListItem item = service.getHealthListItemById(websiteId);

        assertThat(item.url()).isEqualTo("https://example.de");
        assertThat(item.monitors()).hasSize(1);
    }

    @Test
    @DisplayName("getHealthListItemById: website not found throws")
    void getHealthListItemById_notFound_throws() {
        when(websiteRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHealthListItemById(UUID.randomUUID()))
                .isInstanceOf(WebsiteNotFoundException.class);
    }

    // ── getMonitorConfigs ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getMonitorConfigs: returns configs for existing website")
    void getMonitorConfigs_returnsConfigs() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);

        when(websiteRepository.existsById(websiteId)).thenReturn(true);
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(ssl));

        List<MonitorConfigItem> configs = service.getMonitorConfigs(websiteId);

        assertThat(configs).hasSize(1);
    }

    @Test
    @DisplayName("getMonitorConfigs: website not found throws")
    void getMonitorConfigs_notFound_throws() {
        when(websiteRepository.existsById(any())).thenReturn(false);

        assertThatThrownBy(() -> service.getMonitorConfigs(UUID.randomUUID()))
                .isInstanceOf(WebsiteNotFoundException.class);
    }

    // ── listHealthPagedByOrganization ─────────────────────────────────────────

    /**
     * Stubs the dashboard DB queries so the service can build a page of health items.
     */
    private void stubDashboardQueries(UUID orgId, List<Website> websites) {
        // findDashboardPage returns rows: [website_id, total_count]
        long total = websites.size();
        List<Object[]> pageRows = websites.stream()
                .map(w -> new Object[]{w.getId(), total})
                .toList();
        when(websiteRepository.findDashboardPage(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(pageRows);

        // findDashboardStats returns [total, failing, passing, pending, ssl, compliance]
        when(websiteRepository.findDashboardStats(orgId))
                .thenReturn(new Object[]{new Object[]{(long) total, 0L, (long) total, 0L, 0L, 0L}});

        // findAllById for the page
        when(websiteRepository.findAllById(any())).thenReturn(websites);
    }

    @Test
    @DisplayName("listHealthPagedByOrganization: empty website list returns empty response")
    void listHealthPaged_emptyWebsites() {
        UUID orgId = UUID.randomUUID();
        when(websiteRepository.findDashboardPage(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(websiteRepository.findDashboardStats(orgId))
                .thenReturn(new Object[]{new Object[]{0L, 0L, 0L, 0L, 0L, 0L}});

        WebsitePageResponse response = service.listHealthPagedByOrganization(
                orgId, null, null, null, null, 0, 10);

        assertThat(response.content()).isEmpty();
    }

    @Test
    @DisplayName("listHealthPagedByOrganization: builds health items for page")
    void listHealthPaged_buildsItems() {
        UUID orgId = testOrg.getId();
        stubDashboardQueries(orgId, List.of(testWebsite));
        when(monitorRepository.findAllByWebsiteIdIn(any())).thenReturn(List.of());
        when(scanLogRepository.findLatestPerMonitorByWebsiteIds(any())).thenReturn(List.of());

        WebsitePageResponse response = service.listHealthPagedByOrganization(
                orgId, null, null, null, null, 0, 10);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).url()).isEqualTo("https://example.de");
    }

    @Test
    @DisplayName("listHealthPagedByOrganization: pagination delegates to DB query")
    void listHealthPaged_pagination() {
        UUID orgId = testOrg.getId();
        // DB returns only 2 items for the page but total is 5
        List<Website> pageWebsites = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Website w = Website.builder().url("https://site" + i + ".com")
                    .organization(testOrg).build();
            ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
            pageWebsites.add(w);
        }
        List<Object[]> pageRows = pageWebsites.stream()
                .map(w -> new Object[]{w.getId(), 5L})
                .toList();
        when(websiteRepository.findDashboardPage(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(pageRows);
        when(websiteRepository.findDashboardStats(orgId))
                .thenReturn(new Object[]{new Object[]{5L, 0L, 5L, 0L, 0L, 0L}});
        when(websiteRepository.findAllById(any())).thenReturn(pageWebsites);
        when(monitorRepository.findAllByWebsiteIdIn(any())).thenReturn(List.of());
        when(scanLogRepository.findLatestPerMonitorByWebsiteIds(any())).thenReturn(List.of());

        WebsitePageResponse response = service.listHealthPagedByOrganization(
                orgId, null, null, null, null, 0, 2);

        assertThat(response.content()).hasSize(2);
        assertThat(response.totalElements()).isEqualTo(5);
    }

    // ── buildHealthListItem static method edge cases ──────────────────────────

    @Test
    @DisplayName("buildHealthListItem: inactive monitor gets INACTIVE status")
    void buildHealthListItem_inactiveMonitor() {
        Monitor inactive = buildMonitor(MonitorType.SSL, SubType.SSL);
        inactive.setActive(false);

        WebsiteHealthListItem item = WebsiteQueryService.buildHealthListItem(
                testWebsite, List.of(inactive), Map.of());

        assertThat(item.monitors().get(0).status()).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("buildHealthListItem: no active monitors gives PENDING site status")
    void buildHealthListItem_noActiveMonitors_pending() {
        Monitor inactive = buildMonitor(MonitorType.SSL, SubType.SSL);
        inactive.setActive(false);

        WebsiteHealthListItem item = WebsiteQueryService.buildHealthListItem(
                testWebsite, List.of(inactive), Map.of());

        assertThat(item.status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("buildHealthListItem: CRITICAL severity monitor makes site FAILING")
    void buildHealthListItem_criticalMonitor_siteFailing() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);
        ScanLog sslLog = buildScanLog(ssl, Severity.CRITICAL, Map.of());

        WebsiteHealthListItem item = WebsiteQueryService.buildHealthListItem(
                testWebsite, List.of(ssl), Map.of(ssl.getId(), sslLog));

        assertThat(item.status()).isEqualTo("FAILING");
    }

    @Test
    @DisplayName("buildHealthListItem: INFO severity monitor makes site PASSING")
    void buildHealthListItem_infoMonitor_sitePassing() {
        Monitor ssl = buildMonitor(MonitorType.SSL, SubType.SSL);
        ScanLog sslLog = buildScanLog(ssl, Severity.INFO, Map.of());

        WebsiteHealthListItem item = WebsiteQueryService.buildHealthListItem(
                testWebsite, List.of(ssl), Map.of(ssl.getId(), sslLog));

        assertThat(item.status()).isEqualTo("PASSING");
    }

    @Test
    @DisplayName("buildHealthListItem: language_inconsistency flag is detected")
    void buildHealthListItem_languageInconsistency() {
        Monitor pp = buildMonitor(MonitorType.COMPLIANCE, SubType.PRIVACY_POLICY);
        ScanLog ppLog = buildScanLog(pp, Severity.MEDIUM,
                Map.of("language_inconsistency", "true"));

        WebsiteHealthListItem item = WebsiteQueryService.buildHealthListItem(
                testWebsite, List.of(pp), Map.of(pp.getId(), ppLog));

        assertThat(item.monitors().get(0).languageInconsistency()).isTrue();
    }

    // ── compliance with broken_keywords ───────────────────────────────────────

    @Test
    @DisplayName("getHealthView: compliance broken keywords")
    void getHealthView_complianceBroken() {
        Monitor pp = buildMonitor(MonitorType.COMPLIANCE, SubType.PRIVACY_POLICY);
        ScanLog ppLog = buildScanLog(pp, Severity.HIGH,
                Map.of("status", "SUCCESS", "missing_keywords", "[]",
                        "broken_keywords", "[\"Datenschutz\"]"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(pp));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(ppLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.monitors().get(0).summary()).contains("broken: Datenschutz");
    }

    @Test
    @DisplayName("getHealthView: compliance with both missing and broken")
    void getHealthView_complianceMissingAndBroken() {
        Monitor pp = buildMonitor(MonitorType.COMPLIANCE, SubType.PRIVACY_POLICY);
        ScanLog ppLog = buildScanLog(pp, Severity.HIGH,
                Map.of("status", "SUCCESS",
                        "missing_keywords", "[\"Impressum\"]",
                        "broken_keywords", "[\"Cookie\"]"));

        when(websiteRepository.findById(websiteId)).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteId(websiteId)).thenReturn(List.of(pp));
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of(ppLog));

        WebsiteHealthView view = service.getHealthView(websiteId);

        assertThat(view.monitors().get(0).summary())
                .contains("missing: Impressum").contains("broken: Cookie");
    }

    // Sorting tests removed — sorting is now DB-level (native SQL ORDER BY)
    // and requires integration tests against a real database, not unit mocks.
}
