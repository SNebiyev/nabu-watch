package watch.nabu.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import watch.nabu.application.scan.ScanResultEvent;
import watch.nabu.application.scheduler.MonitoringScheduler;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.User.AccountStatus;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Full-stack integration test: pushes a message through {@link ResultConsumer}
 * and asserts that the database is updated correctly.
 *
 * <p>Both PostgreSQL and Redis are started via Testcontainers.
 * The {@link MonitoringScheduler} is mocked to prevent cron interference.
 */
@SpringBootTest
@Testcontainers
@RecordApplicationEvents
class ResultConsumerIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("nabu_watch_test")
            .withUsername("nabu")
            .withPassword("nabu");

    @SuppressWarnings("resource")
    @Container
    @ServiceConnection("redis")
    static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    // Prevent the scheduler from firing during the test run
    @MockitoBean MonitoringScheduler monitoringScheduler;

    @Autowired
    ApplicationEvents applicationEvents;
    @Autowired
    ResultConsumer resultConsumer;
    @Autowired
    OrganizationRepository organizationRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    WebsiteRepository websiteRepository;
    @Autowired
    MonitorRepository monitorRepository;
    @Autowired
    ScanLogRepository scanLogRepository;
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    ObjectMapper objectMapper;

    private Monitor sslMonitor;
    private Monitor complianceMonitor;

    @BeforeEach
    void seedDatabase() {
        Organization org = organizationRepository.save(
                Organization.builder().name("Integration Org").build());

        User user = userRepository.save(User.builder()
            .fullName("Integration Tester")
            .email("integration@example.com")
            .passwordHash("$2a$10$testhash")
            .status(AccountStatus.ACTIVE)
                .currentOrganizationId(org.getId())
            .build());

        Website website = websiteRepository.save(Website.builder()
                .organization(org)
                .addedBy(user)
            .url("https://integration.example.de")
            .regionCode("DE")
            .build());

        sslMonitor = monitorRepository.save(Monitor.builder()
            .website(website)
            .type(MonitorType.SSL)
            .subType(SubType.SSL)
            .intervalMinutes(60)
            .build());

        complianceMonitor = monitorRepository.save(Monitor.builder()
            .website(website)
            .type(MonitorType.COMPLIANCE)
            .subType(SubType.IMPRESSUM)
            .intervalMinutes(1440)
            .build());
    }

    @AfterEach
    void cleanDatabase() {
        notificationRepository.deleteAll();
        scanLogRepository.deleteAll();
        monitorRepository.deleteAll();
        websiteRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    // ── ScanLog persistence ───────────────────────────────────────────────────

    @Test
    void sslSuccessMessage_persistsScanLogWithCorrectSeverity() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
            "task_id",        "1-0",
            "monitor_id",     sslMonitor.getId().toString(),
            "status",         "SUCCESS",
            "payload",        Map.of("days_remaining", "160", "host", "integration.example.de"),
            "published_at",   Instant.now().toString()
        ));

        resultConsumer.onMessage(fakeRecord("0-1", json));

        List<ScanLog> logs = scanLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getSeverity()).isEqualTo(Severity.INFO);
        assertThat(logs.get(0).getResultJson()).containsKey("status");
    }

    @Test
    void sslCriticalMessage_persistsScanLogWithCriticalSeverity() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
            "task_id",      "2-0",
            "monitor_id",   sslMonitor.getId().toString(),
            "status",       "SUCCESS",
            "payload",      Map.of("days_remaining", "3"),
            "published_at", Instant.now().toString()
        ));

        resultConsumer.onMessage(fakeRecord("0-2", json));

        ScanLog log = scanLogRepository.findAll().get(0);
        assertThat(log.getSeverity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void failedScan_persistsScanLogWithHighSeverity() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
            "task_id",      "3-0",
            "monitor_id",   complianceMonitor.getId().toString(),
            "status",       "FAILED",
            "payload",      Map.of("error", "connection timeout"),
            "published_at", Instant.now().toString()
        ));

        resultConsumer.onMessage(fakeRecord("0-3", json));

        ScanLog log = scanLogRepository.findAll().get(0);
        assertThat(log.getSeverity()).isEqualTo(Severity.HIGH);
    }

    // ── Monitor.lastRunAt updated ─────────────────────────────────────────────

    @Test
    void afterProcessing_monitorLastRunAtIsStamped() throws Exception {
        Instant before = Instant.now().minusSeconds(1);

        String json = objectMapper.writeValueAsString(Map.of(
            "task_id",      "4-0",
            "monitor_id",   sslMonitor.getId().toString(),
            "status",       "SUCCESS",
            "payload",      Map.of("days_remaining", "90"),
            "published_at", Instant.now().toString()
        ));

        resultConsumer.onMessage(fakeRecord("0-4", json));

        Monitor updated = monitorRepository.findById(sslMonitor.getId()).orElseThrow();
        assertThat(updated.getLastRunAt()).isAfter(before);
    }

    // ── Event published ───────────────────────────────────────────────────────

    @Test
    void afterProcessing_scanResultEventIsPublished() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
            "task_id",      "5-0",
            "monitor_id",   sslMonitor.getId().toString(),
            "status",       "SUCCESS",
            "payload",      Map.of("days_remaining", "90"),
            "published_at", Instant.now().toString()
        ));

        resultConsumer.onMessage(fakeRecord("0-5", json));

        assertThat(applicationEvents.stream(ScanResultEvent.class)).hasSize(1);
    }

    // ── Malformed message — ACKed without crashing ────────────────────────────

    @Test
    void missingResultField_doesNotThrowAndSavesNoLog() {
        // Record with no "result" field — ResultConsumer should log a warning and ACK
        resultConsumer.onMessage(fakeRecord("0-99", null));

        assertThat(scanLogRepository.findAll()).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static MapRecord<String, String, String> fakeRecord(String recordId, String json) {
        MapRecord<String, String, String> record = Mockito.mock(MapRecord.class);
        Mockito.when(record.getId()).thenReturn(RecordId.of(recordId));
        if (json != null) {
            Mockito.when(record.getValue()).thenReturn(Map.of("result", json));
        } else {
            Mockito.when(record.getValue()).thenReturn(Map.of());
        }
        return record;
    }
}
