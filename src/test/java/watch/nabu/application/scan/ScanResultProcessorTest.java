package watch.nabu.application.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.compliance.CompliancePathService;
import watch.nabu.application.notification.NotificationService;
import watch.nabu.domain.model.*;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.persistence.ScanLogRepository;
import watch.nabu.infrastructure.persistence.UserRepository;
import watch.nabu.infrastructure.redis.ScanResultMessage;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScanResultProcessorTest {

    @Mock
    MonitorRepository monitorRepository;
    @Mock
    ScanLogRepository scanLogRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    ObjectMapper objectMapper;
    @Mock
    NotificationService notificationService;
    @Mock
    ScanBatchAggregatorPort scanBatchAggregator;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    CompliancePathService compliancePathService;

    @InjectMocks ScanResultProcessor processor;

    private Monitor sslMonitor;
    private Monitor complianceMonitor;

    private void setId(Object entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }

    @BeforeEach
    void setUp() {
        Organization org = Organization.builder().name("Test Org").build();
        setId(org, UUID.randomUUID());

        User owner = User.builder()
                .fullName("Test User")
                .email("test@example.com")
                .passwordHash("hash")
                .build();
        setId(owner, UUID.randomUUID());

        Website website = Website.builder()
            .url("https://example.de")
            .regionCode("DE")
                .organization(org)
                .addedBy(owner)
            .build();
        setId(website, UUID.randomUUID());

        sslMonitor = Monitor.builder()
            .website(website)
            .type(MonitorType.SSL)
            .subType(SubType.SSL)
            .intervalMinutes(60)
            .build();
        setId(sslMonitor, UUID.randomUUID());

        complianceMonitor = Monitor.builder()
            .website(website)
            .type(MonitorType.COMPLIANCE)
            .subType(SubType.IMPRESSUM)
            .intervalMinutes(1440)
            .build();
        setId(complianceMonitor, UUID.randomUUID());

        // save() returns the scanLog with a fresh ID and createdAt (simulating Hibernate @CreationTimestamp)
        lenient().when(scanLogRepository.save(any(ScanLog.class))).thenAnswer(inv -> {
            ScanLog sl = inv.getArgument(0);
            setId(sl, UUID.randomUUID());
            sl.setCreatedAt(Instant.now());
            return sl;
        });

        lenient().when(monitorRepository.save(any(Monitor.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── SSL severity thresholds ───────────────────────────────────────────────

    @Test
    @DisplayName("SSL success with 6 days remaining → CRITICAL severity")
    void sslSuccess_daysRemaining6_returnsCritical() {
        when(monitorRepository.findById(sslMonitor.getId()))
            .thenReturn(Optional.of(sslMonitor));

        Optional<ScanResultSummary> result = processor.process(successSslMessage(sslMonitor, "6"));

        assertThat(result).isPresent();
        assertThat(result.get().severity()).isEqualTo(Severity.CRITICAL);
        assertPersistedScanLogSeverity(Severity.CRITICAL);
    }

    @Test
    @DisplayName("SSL success with 7 days remaining → HIGH severity")
    void sslSuccess_daysRemaining7_returnsHigh() {
        when(monitorRepository.findById(sslMonitor.getId()))
            .thenReturn(Optional.of(sslMonitor));

        processor.process(successSslMessage(sslMonitor, "7"));

        assertPersistedScanLogSeverity(Severity.HIGH);
    }

    @Test
    @DisplayName("SSL success with 14 days remaining → MEDIUM severity")
    void sslSuccess_daysRemaining14_returnsMedium() {
        when(monitorRepository.findById(sslMonitor.getId()))
            .thenReturn(Optional.of(sslMonitor));

        processor.process(successSslMessage(sslMonitor, "14"));

        assertPersistedScanLogSeverity(Severity.MEDIUM);
    }

    @Test
    @DisplayName("SSL success with 160 days remaining → INFO severity")
    void sslSuccess_daysRemaining160_returnsInfo() {
        when(monitorRepository.findById(sslMonitor.getId()))
            .thenReturn(Optional.of(sslMonitor));

        processor.process(successSslMessage(sslMonitor, "160"));

        assertPersistedScanLogSeverity(Severity.INFO);
    }

    @Test
    @DisplayName("SSL success with non-numeric days_remaining → falls back to INFO")
    void sslSuccess_invalidDaysRemaining_fallsBackToInfo() {
        when(monitorRepository.findById(sslMonitor.getId()))
            .thenReturn(Optional.of(sslMonitor));

        processor.process(successSslMessage(sslMonitor, "not-a-number"));

        assertPersistedScanLogSeverity(Severity.INFO);
    }

    @Test
    @DisplayName("SSL success with null payload → INFO severity")
    void sslSuccess_nullPayload_returnsInfo() {
        when(monitorRepository.findById(sslMonitor.getId()))
            .thenReturn(Optional.of(sslMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", sslMonitor.getId().toString(), "SUCCESS", null, Instant.now(), null, null
        );
        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.INFO);
    }

    // ── Failed scan → always HIGH ─────────────────────────────────────────────

    @Test
    @DisplayName("Failed SSL scan → HIGH regardless of payload days_remaining")
    void failedSslScan_returnsHighRegardlessOfPayload() {
        when(monitorRepository.findById(sslMonitor.getId()))
            .thenReturn(Optional.of(sslMonitor));

        ScanResultMessage msg = new ScanResultMessage(
            "task-1", sslMonitor.getId().toString(), "FAILED",
                Map.of("days_remaining", "200"), Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.HIGH);
    }

    @Test
    @DisplayName("Failed compliance scan → HIGH severity")
    void failedComplianceScan_returnsHigh() {
        when(monitorRepository.findById(complianceMonitor.getId()))
            .thenReturn(Optional.of(complianceMonitor));

        ScanResultMessage msg = new ScanResultMessage(
            "task-1", complianceMonitor.getId().toString(), "FAILED",
                Map.of("error", "connection refused"), Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.HIGH);
    }

    // ── Successful compliance → INFO when no issues ───────────────────────────

    @Test
    @DisplayName("Successful compliance scan with COMPLIANT status → INFO severity")
    void successfulComplianceScan_returnsInfo() {
        when(monitorRepository.findById(complianceMonitor.getId()))
            .thenReturn(Optional.of(complianceMonitor));

        ScanResultMessage msg = new ScanResultMessage(
            "task-1", complianceMonitor.getId().toString(), "SUCCESS",
                Map.of("status", "COMPLIANT"), Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.INFO);
    }

    @Test
    @DisplayName("Compliance scan with broken_keywords array → MEDIUM severity")
    void complianceScan_withBrokenKeywords_returnsMedium() {
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        // broken_keywords (links that could not be resolved) trigger MEDIUM severity;
        // missing_keywords alone (unfound synonyms) are informational and stay INFO
        ScanResultMessage msg = new ScanResultMessage(
                "task-1", complianceMonitor.getId().toString(), "SUCCESS",
                Map.of("broken_keywords", "[\"Datenschutz\"]"), Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.MEDIUM);
    }

    @Test
    @DisplayName("Compliance scan with language_inconsistency=true → MEDIUM severity")
    void complianceScan_withLanguageInconsistency_returnsMedium() {
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", complianceMonitor.getId().toString(), "SUCCESS",
                Map.of("language_inconsistency", "true"), Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.MEDIUM);
    }

    // ── Previous severity tracking ───────────────────────────────────────────

    @Test
    @DisplayName("first scan (no prior ScanLog) → persisted with previousSeverity=null")
    void firstScan_previousSeverityIsNull() {
        when(monitorRepository.findById(sslMonitor.getId()))
                .thenReturn(Optional.of(sslMonitor));
        when(scanLogRepository.findTopByMonitorIdOrderByCreatedAtDesc(sslMonitor.getId()))
                .thenReturn(Optional.empty());

        processor.process(successSslMessage(sslMonitor, "160"));

        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        assertThat(captor.getValue().getPreviousSeverity()).isNull();
        assertThat(captor.getValue().getSeverity()).isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("prior severity differs from current → previousSeverity = prior's severity")
    void subsequentScan_differentSeverity_usesPriorSeverity() {
        when(monitorRepository.findById(sslMonitor.getId()))
                .thenReturn(Optional.of(sslMonitor));
        ScanLog prior = ScanLog.builder().monitor(sslMonitor).severity(Severity.HIGH).build();
        when(scanLogRepository.findTopByMonitorIdOrderByCreatedAtDesc(sslMonitor.getId()))
                .thenReturn(Optional.of(prior));

        // Current scan → INFO (160 days remaining), so prev=HIGH current=INFO → RECOVERED
        processor.process(successSslMessage(sslMonitor, "160"));

        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        assertThat(captor.getValue().getPreviousSeverity()).isEqualTo(Severity.HIGH);
        assertThat(captor.getValue().getSeverity()).isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("prior severity matches current → previousSeverity = prior's severity (no carry-forward)")
    void subsequentScan_sameSeverity_usesPriorSeverityNotCarryForward() {
        when(monitorRepository.findById(sslMonitor.getId()))
                .thenReturn(Optional.of(sslMonitor));
        // Prior scan is INFO and its own previousSeverity is HIGH (i.e. it
        // recovered on the previous step). Under the new immediate-prior
        // semantics we do NOT carry HIGH forward — the current scan compares
        // only to the immediately-preceding severity (INFO), yielding UNCHANGED.
        ScanLog prior = ScanLog.builder()
                .monitor(sslMonitor)
                .severity(Severity.INFO)
                .previousSeverity(Severity.HIGH)
                .build();
        when(scanLogRepository.findTopByMonitorIdOrderByCreatedAtDesc(sslMonitor.getId()))
                .thenReturn(Optional.of(prior));

        processor.process(successSslMessage(sslMonitor, "160")); // also INFO

        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        assertThat(captor.getValue().getPreviousSeverity()).isEqualTo(Severity.INFO);
        assertThat(captor.getValue().getSeverity()).isEqualTo(Severity.INFO);
    }

    // ── SKIPPED_WAF → empty Optional, no ScanLog ─────────────────────────────

    @Test
    @DisplayName("SKIPPED_WAF status → Optional.empty(), no ScanLog persisted")
    void wafBlockedScan_returnsEmpty_noScanLogPersisted() {
        ScanResultMessage msg = new ScanResultMessage(
                "task-1", sslMonitor.getId().toString(), "SKIPPED_WAF",
                null, Instant.now(), null, null
        );

        Optional<ScanResultSummary> result = processor.process(msg);

        assertThat(result).isEmpty();
        verifyNoInteractions(scanLogRepository);
        verifyNoInteractions(monitorRepository);
    }

    // ── Summary values ────────────────────────────────────────────────────────

    @Test
    @DisplayName("process() returns summary with all correct fields populated")
    void process_returnsSummaryWithCorrectFields() {
        when(monitorRepository.findById(sslMonitor.getId()))
            .thenReturn(Optional.of(sslMonitor));

        Optional<ScanResultSummary> result = processor.process(successSslMessage(sslMonitor, "160"));

        assertThat(result).isPresent();
        ScanResultSummary summary = result.get();
        assertThat(summary.monitorId()).isEqualTo(sslMonitor.getId());
        assertThat(summary.monitorType()).isEqualTo(MonitorType.SSL);
        assertThat(summary.subType()).isEqualTo(SubType.SSL);
        assertThat(summary.websiteId()).isEqualTo(sslMonitor.getWebsite().getId());
        assertThat(summary.severity()).isEqualTo(Severity.INFO);
        assertThat(summary.scanLogId()).isNotNull();
        assertThat(summary.scannedAt()).isNotNull();
    }

    // ── COOKIE_BANNER severity mapping ───────────────────────────────────────

    @Test
    @DisplayName("COOKIE_BANNER: compliant result (banner + reject button) → INFO severity")
    void cookieBanner_compliant_returnsInfo() {
        Monitor bannerMonitor = Monitor.builder()
                .website(complianceMonitor.getWebsite())
                .type(MonitorType.COMPLIANCE)
                .subType(SubType.COOKIE_BANNER)
                .intervalMinutes(1440)
                .build();
        setId(bannerMonitor, UUID.randomUUID());
        when(monitorRepository.findById(bannerMonitor.getId()))
                .thenReturn(Optional.of(bannerMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", bannerMonitor.getId().toString(), "SUCCESS",
                Map.of(
                        "status", "COMPLIANT",
                        "banner_detected", "true",
                        "reject_button_found", "true"
                ),
                Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.INFO);
    }

    @Test
    @DisplayName("COOKIE_BANNER: no banner detected → NON_COMPLIANT → HIGH severity")
    void cookieBanner_noBannerDetected_returnsHigh() {
        Monitor bannerMonitor = Monitor.builder()
                .website(complianceMonitor.getWebsite())
                .type(MonitorType.COMPLIANCE)
                .subType(SubType.COOKIE_BANNER)
                .intervalMinutes(1440)
                .build();
        setId(bannerMonitor, UUID.randomUUID());
        when(monitorRepository.findById(bannerMonitor.getId()))
                .thenReturn(Optional.of(bannerMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", bannerMonitor.getId().toString(), "SUCCESS",
                Map.of(
                        "status", "NON_COMPLIANT",
                        "banner_detected", "false",
                        "reject_button_found", "false"
                ),
                Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.HIGH);
    }

    @Test
    @DisplayName("COOKIE_BANNER: banner present but no reject button → NON_COMPLIANT → HIGH severity")
    void cookieBanner_noRejectButton_returnsHigh() {
        Monitor bannerMonitor = Monitor.builder()
                .website(complianceMonitor.getWebsite())
                .type(MonitorType.COMPLIANCE)
                .subType(SubType.COOKIE_BANNER)
                .intervalMinutes(1440)
                .build();
        setId(bannerMonitor, UUID.randomUUID());
        when(monitorRepository.findById(bannerMonitor.getId()))
                .thenReturn(Optional.of(bannerMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", bannerMonitor.getId().toString(), "SUCCESS",
                Map.of(
                        "status", "NON_COMPLIANT",
                        "banner_detected", "true",
                        "reject_button_found", "false",
                        "compliance_issues", "[\"No reject/decline option found\"]"
                ),
                Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.HIGH);
    }

    // ── AI content quality severity ─────────────────────────────────────────

    @Test
    @DisplayName("Compliance scan with ai_content_score < 40 → MEDIUM severity")
    void complianceScan_lowAiContentScore_returnsMedium() {
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", complianceMonitor.getId().toString(), "SUCCESS",
                Map.of("status", "COMPLIANT", "ai_content_score", "25"),
                Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.MEDIUM);
    }

    @Test
    @DisplayName("Compliance scan with ai_content_score >= 40 and COMPLIANT → INFO severity")
    void complianceScan_goodAiContentScore_returnsInfo() {
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", complianceMonitor.getId().toString(), "SUCCESS",
                Map.of("status", "COMPLIANT", "ai_content_score", "75"),
                Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.INFO);
    }

    @Test
    @DisplayName("Compliance scan with invalid ai_content_score → falls back to normal logic")
    void complianceScan_invalidAiContentScore_fallsBackToNormalLogic() {
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", complianceMonitor.getId().toString(), "SUCCESS",
                Map.of("status", "COMPLIANT", "ai_content_score", "not-a-number"),
                Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.INFO);
    }

    // ── TRACKER_DETECTION severity mapping ─────────────────────────────────

    @Test
    @DisplayName("TRACKER_DETECTION: trackers found → INFO severity (advisory only)")
    void trackerDetection_trackersFound_returnsInfo() {
        Monitor trackerMonitor = Monitor.builder()
                .website(complianceMonitor.getWebsite())
                .type(MonitorType.COMPLIANCE)
                .subType(SubType.TRACKER_DETECTION)
                .intervalMinutes(1440)
                .build();
        setId(trackerMonitor, UUID.randomUUID());
        when(monitorRepository.findById(trackerMonitor.getId()))
                .thenReturn(Optional.of(trackerMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", trackerMonitor.getId().toString(), "SUCCESS",
                Map.of("status", "TRACKERS_FOUND", "tracker_count", "5"),
                Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.INFO);
    }

    @Test
    @DisplayName("TRACKER_DETECTION: clean (no trackers) → INFO severity")
    void trackerDetection_clean_returnsInfo() {
        Monitor trackerMonitor = Monitor.builder()
                .website(complianceMonitor.getWebsite())
                .type(MonitorType.COMPLIANCE)
                .subType(SubType.TRACKER_DETECTION)
                .intervalMinutes(1440)
                .build();
        setId(trackerMonitor, UUID.randomUUID());
        when(monitorRepository.findById(trackerMonitor.getId()))
                .thenReturn(Optional.of(trackerMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", trackerMonitor.getId().toString(), "SUCCESS",
                Map.of("status", "CLEAN", "tracker_count", "0"),
                Instant.now(), null, null
        );

        processor.process(msg);

        assertPersistedScanLogSeverity(Severity.INFO);
    }

    // ── Payload enrichment ────────────────────────────────────────────────────

    @Test
    @DisplayName("Persisted ScanLog includes status and task_id from message")
    void persistedScanLog_includesStatusAndTaskIdFromMessage() {
        when(monitorRepository.findById(sslMonitor.getId()))
            .thenReturn(Optional.of(sslMonitor));

        ScanResultMessage msg = new ScanResultMessage(
            "task-abc-123", sslMonitor.getId().toString(), "SUCCESS",
                Map.of("days_remaining", "90"), Instant.now(), null, null
        );
        processor.process(msg);

        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());

        Map<String, Object> json = captor.getValue().getResultJson();
        assertThat(json).containsEntry("status", "SUCCESS");
        assertThat(json).containsEntry("task_id", "task-abc-123");
    }

    // ── Monitor not found → empty Optional ───────────────────────────────────

    @Test
    @DisplayName("Unknown monitor ID → returns Optional.empty(), no ScanLog persisted")
    void unknownMonitorId_returnsEmpty_noScanLogPersisted() {
        UUID unknownId = UUID.randomUUID();
        when(monitorRepository.findById(unknownId)).thenReturn(Optional.empty());

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", unknownId.toString(), "SUCCESS", null, Instant.now(), null, null
        );

        Optional<ScanResultSummary> result = processor.process(msg);

        assertThat(result).isEmpty();
        verifyNoInteractions(scanLogRepository);
    }

    @Test
    @DisplayName("Invalid monitor ID format → throws IllegalArgumentException")
    void invalidMonitorIdFormat_throwsIllegalArgumentException() {
        ScanResultMessage msg = new ScanResultMessage(
                "task-1", "not-a-uuid", "SUCCESS", null, Instant.now(), null, null
        );

        assertThatThrownBy(() -> processor.process(msg))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid monitorId");
    }

    // ── batchId propagation ───────────────────────────────────────────────────

    @Test
    @DisplayName("Valid batchId UUID is stored on ScanLog")
    void validBatchId_isStoredOnScanLog() {
        when(monitorRepository.findById(sslMonitor.getId()))
                .thenReturn(Optional.of(sslMonitor));

        String batchId = UUID.randomUUID().toString();
        ScanResultMessage msg = new ScanResultMessage(
                "task-1", sslMonitor.getId().toString(), "SUCCESS",
                Map.of("days_remaining", "90"), Instant.now(), batchId, null
        );
        processor.process(msg);

        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        assertThat(captor.getValue().getBatchId()).isEqualTo(UUID.fromString(batchId));
    }

    @Test
    @DisplayName("Invalid batchId string is silently ignored (null batchId on ScanLog)")
    void invalidBatchId_isIgnored() {
        when(monitorRepository.findById(sslMonitor.getId()))
                .thenReturn(Optional.of(sslMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", sslMonitor.getId().toString(), "SUCCESS",
                Map.of("days_remaining", "90"), Instant.now(), "not-a-uuid", null
        );
        processor.process(msg);

        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        assertThat(captor.getValue().getBatchId()).isNull();
    }

    // ── Trigger type audit persistence ─────────────────────────────────────────

    @Test
    @DisplayName("Manual batch → ScanLog persisted with MANUAL triggerType and triggeredBy user")
    void manualBatch_persistsTriggerTypeAndTriggeredBy() {
        when(monitorRepository.findById(sslMonitor.getId()))
                .thenReturn(Optional.of(sslMonitor));

        UUID batchId = UUID.randomUUID();
        User caller = User.builder().fullName("Caller").email("caller@example.com").passwordHash("h").build();
        setId(caller, UUID.randomUUID());

        when(scanBatchAggregator.getBatchTriggerInfo(batchId))
                .thenReturn(Optional.of(new ScanBatchAggregatorPort.BatchTriggerInfo("MANUAL", caller.getId())));
        when(userRepository.findById(caller.getId()))
                .thenReturn(Optional.of(caller));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", sslMonitor.getId().toString(), "SUCCESS",
                Map.of("days_remaining", "90"), Instant.now(), batchId.toString(), null
        );

        processor.process(msg);

        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        ScanLog saved = captor.getValue();
        assertThat(saved.getTriggerType()).isEqualTo(TriggerType.MANUAL);
        assertThat(saved.getTriggeredBy()).isEqualTo(caller);
    }

    @Test
    @DisplayName("Scheduled batch → ScanLog persisted with SCHEDULED triggerType and null triggeredBy")
    void scheduledBatch_persistsTriggerTypeScheduled() {
        when(monitorRepository.findById(sslMonitor.getId()))
                .thenReturn(Optional.of(sslMonitor));

        UUID batchId = UUID.randomUUID();
        when(scanBatchAggregator.getBatchTriggerInfo(batchId))
                .thenReturn(Optional.of(new ScanBatchAggregatorPort.BatchTriggerInfo("SCHEDULED", UUID.randomUUID())));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", sslMonitor.getId().toString(), "SUCCESS",
                Map.of("days_remaining", "90"), Instant.now(), batchId.toString(), null
        );

        processor.process(msg);

        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        ScanLog saved = captor.getValue();
        assertThat(saved.getTriggerType()).isEqualTo(TriggerType.SCHEDULED);
        assertThat(saved.getTriggeredBy()).isNull();
    }

    @Test
    @DisplayName("No batchId → defaults to SCHEDULED triggerType")
    void noBatchId_defaultsToScheduled() {
        when(monitorRepository.findById(sslMonitor.getId()))
                .thenReturn(Optional.of(sslMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", sslMonitor.getId().toString(), "SUCCESS",
                Map.of("days_remaining", "90"), Instant.now(), null, null
        );

        processor.process(msg);

        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        ScanLog saved = captor.getValue();
        assertThat(saved.getTriggerType()).isEqualTo(TriggerType.SCHEDULED);
        assertThat(saved.getTriggeredBy()).isNull();
    }

    @Test
    @DisplayName("Expired batch metadata → defaults to SCHEDULED triggerType")
    void expiredBatchMeta_defaultsToScheduled() {
        when(monitorRepository.findById(sslMonitor.getId()))
                .thenReturn(Optional.of(sslMonitor));

        UUID batchId = UUID.randomUUID();
        when(scanBatchAggregator.getBatchTriggerInfo(batchId))
                .thenReturn(Optional.empty());

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", sslMonitor.getId().toString(), "SUCCESS",
                Map.of("days_remaining", "90"), Instant.now(), batchId.toString(), null
        );

        processor.process(msg);

        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        ScanLog saved = captor.getValue();
        assertThat(saved.getTriggerType()).isEqualTo(TriggerType.SCHEDULED);
        assertThat(saved.getTriggeredBy()).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ScanResultMessage complianceMessage(Monitor monitor, Map<String, String> payload) {
        return new ScanResultMessage(
                "task-1", monitor.getId().toString(), "SUCCESS",
                payload, Instant.now(), null, null);
    }

    private ScanResultMessage successSslMessage(Monitor monitor, String daysRemaining) {
        return new ScanResultMessage(
            "task-1", monitor.getId().toString(), "SUCCESS",
            Map.of("days_remaining", daysRemaining, "host", "example.de"),
                Instant.now(), null, null
        );
    }

    private void assertPersistedScanLogSeverity(Severity expected) {
        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(expected);
    }

    // ── Language preference override ─────────────────────────────────────────

    @Test
    @DisplayName("language_inconsistency with BOTH_ACCEPTED website → severity INFO instead of MEDIUM")
    void langInconsistency_englishAccepted_severityInfo() {
        complianceMonitor.getWebsite().setAcceptedLanguages(Website.AcceptedLanguages.BOTH_ACCEPTED);
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-1", complianceMonitor.getId().toString(), "SUCCESS",
                new java.util.HashMap<>(Map.of(
                        "status", "NON_COMPLIANT",
                        "language_inconsistency", "true",
                        "found_mandatory_native", "[]",
                        "found_english_fallback", "[\"Impressum\"]"
                )),
                Instant.now(), null, null);

        Optional<ScanResultSummary> result = processor.process(msg);

        assertThat(result).isPresent();
        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(Severity.INFO);
        assertThat(captor.getValue().getResultJson()).containsEntry("accepted_via_language_preference", "true");
    }

    @Test
    @DisplayName("language_inconsistency with NATIVE_ONLY website → severity stays MEDIUM")
    void langInconsistency_nativeOnly_severityMedium() {
        complianceMonitor.getWebsite().setAcceptedLanguages(Website.AcceptedLanguages.NATIVE_ONLY);
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-2", complianceMonitor.getId().toString(), "SUCCESS",
                new java.util.HashMap<>(Map.of(
                        "status", "NON_COMPLIANT",
                        "language_inconsistency", "true",
                        "found_mandatory_native", "[]"
                )),
                Instant.now(), null, null);

        Optional<ScanResultSummary> result = processor.process(msg);

        assertThat(result).isPresent();
        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(captor.getValue().getResultJson()).doesNotContainKey("accepted_via_language_preference");
    }


    @Test
    @DisplayName("no language_inconsistency → ENGLISH_ACCEPTED has no effect")
    void noLangInconsistency_englishAccepted_noOverride() {
        complianceMonitor.getWebsite().setAcceptedLanguages(Website.AcceptedLanguages.BOTH_ACCEPTED);
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        ScanResultMessage msg = new ScanResultMessage(
                "task-4", complianceMonitor.getId().toString(), "SUCCESS",
                new java.util.HashMap<>(Map.of(
                        "status", "NON_COMPLIANT",
                        "language_inconsistency", "false",
                        "found_mandatory_native", "[]"
                )),
                Instant.now(), null, null);

        Optional<ScanResultSummary> result = processor.process(msg);

        assertThat(result).isPresent();
        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepository).save(captor.capture());
        // NON_COMPLIANT without langIssue → still MEDIUM (from isNonCompliant)
        assertThat(captor.getValue().getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(captor.getValue().getResultJson()).doesNotContainKey("accepted_via_language_preference");
    }

    // ── Compliance path persistence (website_compliance_paths) ───────────────

    @Test
    @DisplayName("scanned_via contains FAST_PATH → recordFastPathSuccess, no upsert")
    void compliancePath_fastPathSuccess_recordsVerification() {
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        processor.process(complianceMessage(complianceMonitor, Map.of(
                "status", "COMPLIANT",
                "scanned_via", "STATIC+FAST_PATH"
        )));

        UUID websiteId = complianceMonitor.getWebsite().getId();
        verify(compliancePathService).recordFastPathSuccess(websiteId, "IMPRESSUM");
        verify(compliancePathService, never()).recordFastPathFailure(any(), any());
        verify(compliancePathService, never())
                .upsertFromScan(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("fast_path invalidated WITHOUT new path → recordFastPathFailure only")
    void compliancePath_invalidatedNoNewPath_recordsFailureOnly() {
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        processor.process(complianceMessage(complianceMonitor, Map.of(
                "status", "NON_COMPLIANT",
                "scanned_via", "HEADLESS",
                "path_fast_path_result", "invalidated"
        )));

        UUID websiteId = complianceMonitor.getWebsite().getId();
        verify(compliancePathService).recordFastPathFailure(websiteId, "IMPRESSUM");
        verify(compliancePathService, never())
                .upsertFromScan(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("fast_path invalidated WITH a new path discovery → upsert the new path (bug fix)")
    void compliancePath_invalidatedWithNewPath_upsertsNewPath() {
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        processor.process(complianceMessage(complianceMonitor, Map.of(
                "status", "COMPLIANT",
                "scanned_via", "HEADLESS",
                "path_fast_path_result", "invalidated",
                "path_anchor_href", "https://example.de/impressum",
                "path_anchor_text", "Impressum",
                "path_discovered_url", "https://example.de/impressum",
                "path_source", "RULE_BASED",
                "path_confidence", "high"
        )));

        UUID websiteId = complianceMonitor.getWebsite().getId();
        verify(compliancePathService).upsertFromScan(
                eq(websiteId), eq("IMPRESSUM"),
                eq("https://example.de/impressum"),
                eq("https://example.de/impressum"),
                eq("Impressum"),
                isNull(),
                eq("RULE_BASED"), eq("high"));
        // The stale-counter increment is superseded by the upsert (which resets
        // fail_count to 0); we should NOT double-count by calling failure too.
        verify(compliancePathService, never()).recordFastPathFailure(any(), any());
    }

    @Test
    @DisplayName("full scan with new path fields → upsertFromScan")
    void compliancePath_newDiscovery_upserts() {
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        processor.process(complianceMessage(complianceMonitor, Map.of(
                "status", "COMPLIANT",
                "scanned_via", "HEADLESS",
                "path_anchor_href", "/impressum",
                "path_anchor_text", "Impressum",
                "path_discovered_url", "https://example.de/impressum",
                "path_source", "RULE_BASED",
                "path_confidence", "high",
                "path_dom_selector", "a[href=\"/impressum\"]"
        )));

        verify(compliancePathService).upsertFromScan(
                eq(complianceMonitor.getWebsite().getId()), eq("IMPRESSUM"),
                eq("https://example.de/impressum"),
                eq("/impressum"),
                eq("Impressum"),
                eq("a[href=\"/impressum\"]"),
                eq("RULE_BASED"), eq("high"));
    }

    @Test
    @DisplayName("COOKIE_BANNER sub_type → compliance path service is never touched")
    void compliancePath_cookieBanner_skipped() {
        Monitor bannerMonitor = Monitor.builder()
                .website(complianceMonitor.getWebsite())
                .type(MonitorType.COMPLIANCE)
                .subType(SubType.COOKIE_BANNER)
                .intervalMinutes(1440)
                .build();
        setId(bannerMonitor, UUID.randomUUID());

        when(monitorRepository.findById(bannerMonitor.getId()))
                .thenReturn(Optional.of(bannerMonitor));

        processor.process(complianceMessage(bannerMonitor, Map.of(
                "status", "COMPLIANT",
                "scanned_via", "STATIC+FAST_PATH",
                "path_fast_path_result", "invalidated",
                "path_anchor_href", "x",
                "path_anchor_text", "x",
                "path_discovered_url", "x",
                "path_source", "RULE_BASED",
                "path_confidence", "high"
        )));

        verifyNoInteractions(compliancePathService);
    }

    // ── Fetch mode detection ─────────────────────────────────────────────────

    private ScanResultMessage complianceMsg(Monitor m, Map<String, String> payload) {
        return new ScanResultMessage(
                "task-1", m.getId().toString(), "SUCCESS",
                new java.util.HashMap<>(payload), Instant.now(), null, null
        );
    }

    @Test
    @DisplayName("scanned_via=HEADLESS sets website.preferredFetchMode to HEADLESS")
    void fetchModeDetection_headless() {
        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        processor.process(complianceMsg(complianceMonitor, Map.of(
                "scanned_via", "HEADLESS",
                "status", "COMPLIANT"
        )));

        Website w = complianceMonitor.getWebsite();
        assertThat(w.getPreferredFetchMode()).isEqualTo("HEADLESS");
        assertThat(w.getFetchModeDetectedAt()).isNotNull();
    }

    @Test
    @DisplayName("scanned_via=STATIC resets website.preferredFetchMode to null")
    void fetchModeDetection_static() {
        Website w = complianceMonitor.getWebsite();
        w.setPreferredFetchMode("HEADLESS");
        w.setFetchModeDetectedAt(Instant.now());

        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        processor.process(complianceMsg(complianceMonitor, Map.of(
                "scanned_via", "STATIC",
                "status", "COMPLIANT"
        )));

        assertThat(w.getPreferredFetchMode()).isNull();
        assertThat(w.getFetchModeDetectedAt()).isNotNull();
    }

    @Test
    @DisplayName("scanned_via with FAST_PATH does not update fetch mode")
    void fetchModeDetection_fastPathSkipped() {
        Website w = complianceMonitor.getWebsite();
        w.setPreferredFetchMode("HEADLESS");
        Instant before = Instant.now().minusSeconds(60);
        w.setFetchModeDetectedAt(before);

        when(monitorRepository.findById(complianceMonitor.getId()))
                .thenReturn(Optional.of(complianceMonitor));

        processor.process(complianceMsg(complianceMonitor, Map.of(
                "scanned_via", "HEADLESS+FAST_PATH",
                "status", "COMPLIANT"
        )));

        assertThat(w.getPreferredFetchMode()).isEqualTo("HEADLESS");
        assertThat(w.getFetchModeDetectedAt()).isEqualTo(before);
    }

    @Test
    @DisplayName("SSL monitors do not update fetch mode")
    void fetchModeDetection_sslIgnored() {
        when(monitorRepository.findById(sslMonitor.getId()))
                .thenReturn(Optional.of(sslMonitor));

        processor.process(complianceMsg(sslMonitor, Map.of(
                "scanned_via", "HEADLESS",
                "status", "COMPLIANT"
        )));

        Website w = sslMonitor.getWebsite();
        assertThat(w.getPreferredFetchMode()).isNull();
    }
}
