package watch.nabu.application.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.website.WebsiteHealthView;
import watch.nabu.application.website.WebsiteQueryService;
import watch.nabu.domain.model.*;
import watch.nabu.infrastructure.persistence.ScanLogRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComplianceReportServiceTest {

    @Mock
    private WebsiteQueryService websiteQueryService;
    @Mock
    private ScanLogRepository scanLogRepository;

    @InjectMocks
    private ComplianceReportService service;

    private UUID websiteId;
    private UUID orgId;
    private Website website;

    @BeforeEach
    void setUp() {
        websiteId = UUID.randomUUID();
        orgId = UUID.randomUUID();

        Organization org = new Organization();
        ReflectionTestUtils.setField(org, "id", orgId);
        org.setName("Acme Agency");

        website = Website.builder()
                .organization(org)
                .url("https://example.com")
                .regionCode("DE")
                .build();
        ReflectionTestUtils.setField(website, "id", websiteId);
    }

    @Test
    void generatePdf_happyPath_returnsValidPdfBytes() {
        UUID monitorId = UUID.randomUUID();
        WebsiteHealthView view = new WebsiteHealthView(
                websiteId,
                "https://example.com",
                "DE",
                WebsiteHealth.HealthColor.RED,
                List.of(
                        new WebsiteHealthView.MonitorSummary(
                                monitorId,
                                Monitor.MonitorType.COMPLIANCE,
                                Monitor.SubType.PRIVACY_POLICY,
                                ScanLog.Severity.CRITICAL,
                                Instant.now(),
                                "Non-compliant — missing: Datenschutz"),
                        new WebsiteHealthView.MonitorSummary(
                                UUID.randomUUID(),
                                Monitor.MonitorType.SSL,
                                Monitor.SubType.SSL,
                                ScanLog.Severity.INFO,
                                Instant.now(),
                                "SSL certificate expires in 40 days")
                ),
                Instant.now(),
                "B",
                82
        );

        when(websiteQueryService.getHealthView(websiteId)).thenReturn(view);
        when(scanLogRepository.findLatestPerMonitorByWebsiteId(websiteId)).thenReturn(List.of());

        byte[] pdf = service.generatePdf(website);

        assertThat(pdf).isNotNull();
        assertThat(pdf.length).isGreaterThan(200);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void generatePdf_emptyMonitors_stillRendersValidPdf() {
        WebsiteHealthView view = new WebsiteHealthView(
                websiteId,
                "https://example.com",
                "DE",
                WebsiteHealth.HealthColor.GREEN,
                List.of(),
                Instant.now(),
                null,
                null
        );

        when(websiteQueryService.getHealthView(websiteId)).thenReturn(view);

        byte[] pdf = service.generatePdf(website);

        assertThat(pdf).isNotNull();
        assertThat(pdf.length).isGreaterThan(200);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void domainOf_extractsHostFromUrl() {
        assertThat(ComplianceReportService.domainOf("https://www.example.com/path")).isEqualTo("example.com");
        assertThat(ComplianceReportService.domainOf("example.com")).isEqualTo("example.com");
        assertThat(ComplianceReportService.domainOf("")).isEqualTo("website");
    }
}
