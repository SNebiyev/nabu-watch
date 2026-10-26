package watch.nabu.application.monitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.exception.DomainMismatchException;
import watch.nabu.application.exception.MonitorNotFoundException;
import watch.nabu.application.ratelimit.RateLimitPort;
import watch.nabu.application.scan.ScanBatchAggregatorPort;
import watch.nabu.infrastructure.config.ScanLimitsProperties;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.redis.TaskProducer;
import watch.nabu.infrastructure.web.dto.MonitorResponse;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitorServiceTest {

    @Mock
    MonitorRepository monitorRepository;
    @Mock
    TaskProducer taskProducer;
    @Mock
    RateLimitPort rateLimitPort;
    @Mock
    ScanBatchAggregatorPort scanBatchAggregator;
    @Mock
    ScanLimitsProperties scanLimits;

    @InjectMocks
    MonitorService monitorService;

    private Monitor sslMonitor;
    private Website website;
    private Organization organization;

    @BeforeEach
    void setUp() {
        organization = Organization.builder().name("Test Org").build();
        ReflectionTestUtils.setField(organization, "id", UUID.randomUUID());

        website = Website.builder()
                .url("https://example.de")
                .regionCode("DE")
                .organization(organization)
                .build();
        ReflectionTestUtils.setField(website, "id", UUID.randomUUID());

        sslMonitor = Monitor.builder()
                .website(website)
                .type(MonitorType.SSL)
                .subType(SubType.SSL)
                .intervalMinutes(60)
                .build();
        ReflectionTestUtils.setField(sslMonitor, "id", UUID.randomUUID());
    }

    // ── updateTargetUrl ───────────────────────────────────────────────────────

    @Test
    @DisplayName("updateTargetUrl with valid same-domain URL → sets targetUrl on monitor")
    void updateTargetUrl_validSameDomain_setsTargetUrl() {
        when(monitorRepository.findById(sslMonitor.getId())).thenReturn(Optional.of(sslMonitor));

        MonitorResponse response = monitorService.updateTargetUrl(
                sslMonitor.getId(), "https://sub.example.de");

        assertThat(sslMonitor.getTargetUrl()).isEqualTo("https://sub.example.de");
        assertThat(response.targetUrlSource()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("updateTargetUrl with null → clears targetUrl (AUTO mode)")
    void updateTargetUrl_null_clearsTargetUrl() {
        sslMonitor.setTargetUrl("https://old.example.de");
        when(monitorRepository.findById(sslMonitor.getId())).thenReturn(Optional.of(sslMonitor));

        MonitorResponse response = monitorService.updateTargetUrl(sslMonitor.getId(), null);

        assertThat(sslMonitor.getTargetUrl()).isNull();
        assertThat(response.targetUrlSource()).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("updateTargetUrl with blank string → clears targetUrl (AUTO mode)")
    void updateTargetUrl_blank_clearsTargetUrl() {
        when(monitorRepository.findById(sslMonitor.getId())).thenReturn(Optional.of(sslMonitor));

        MonitorResponse response = monitorService.updateTargetUrl(sslMonitor.getId(), "   ");

        assertThat(sslMonitor.getTargetUrl()).isNull();
        assertThat(response.targetUrlSource()).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("updateTargetUrl with different domain → throws DomainMismatchException")
    void updateTargetUrl_differentDomain_throwsDomainMismatchException() {
        when(monitorRepository.findById(sslMonitor.getId())).thenReturn(Optional.of(sslMonitor));

        assertThatThrownBy(() ->
                monitorService.updateTargetUrl(sslMonitor.getId(), "https://other-domain.com")
        ).isInstanceOf(DomainMismatchException.class);
    }

    @Test
    @DisplayName("updateTargetUrl with URL having leading/trailing whitespace → strips whitespace")
    void updateTargetUrl_withWhitespace_stripsUrl() {
        when(monitorRepository.findById(sslMonitor.getId())).thenReturn(Optional.of(sslMonitor));

        monitorService.updateTargetUrl(sslMonitor.getId(), "  https://example.de  ");

        assertThat(sslMonitor.getTargetUrl()).isEqualTo("https://example.de");
    }

    @Test
    @DisplayName("updateTargetUrl on unknown monitorId → throws MonitorNotFoundException")
    void updateTargetUrl_unknownMonitor_throwsMonitorNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(monitorRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                monitorService.updateTargetUrl(unknownId, "https://example.de")
        ).isInstanceOf(MonitorNotFoundException.class);
    }

    // ── rescanMonitor ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("rescanMonitor without force → checks rate limit, enqueues monitor with the configured domain cooldown")
    void rescanMonitor_withoutForce_checksRateLimitAndEnqueues() {
        UUID callerId = UUID.randomUUID();
        Duration domainCooldown = Duration.ofMinutes(15);
        when(monitorRepository.findById(sslMonitor.getId())).thenReturn(Optional.of(sslMonitor));
        when(scanLimits.domainCooldownMinutes()).thenReturn(15);

        monitorService.rescanMonitor(sslMonitor.getId(), callerId, false);

        verify(rateLimitPort).checkUserRateLimit(callerId);
        verify(rateLimitPort).checkDomainCooldown(website.getUrl());
        verify(taskProducer).enqueue(eq(sslMonitor), any(UUID.class));
        verify(rateLimitPort).setDomainCooldown(website.getUrl(), domainCooldown);
    }

    @Test
    @DisplayName("rescanMonitor with force=true → bypasses rate limit and cooldown")
    void rescanMonitor_withForce_bypassesRateLimit() {
        UUID callerId = UUID.randomUUID();
        when(monitorRepository.findById(sslMonitor.getId())).thenReturn(Optional.of(sslMonitor));

        monitorService.rescanMonitor(sslMonitor.getId(), callerId, true);

        verifyNoInteractions(rateLimitPort);
        verify(taskProducer).enqueue(eq(sslMonitor), any(UUID.class));
    }

    @Test
    @DisplayName("rescanMonitor on unknown monitorId → throws MonitorNotFoundException")
    void rescanMonitor_unknownMonitor_throwsMonitorNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(monitorRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                monitorService.rescanMonitor(unknownId, UUID.randomUUID(), false)
        ).isInstanceOf(MonitorNotFoundException.class);

        verifyNoInteractions(rateLimitPort, taskProducer);
    }
}
