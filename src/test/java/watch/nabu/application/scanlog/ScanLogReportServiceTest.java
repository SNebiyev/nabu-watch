package watch.nabu.application.scanlog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.notification.NotificationService;
import watch.nabu.domain.model.*;
import watch.nabu.infrastructure.persistence.ScanLogRepository;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScanLogReportServiceTest {

    @Mock
    ScanLogRepository scanLogRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    NotificationService notificationService;

    @InjectMocks
    ScanLogReportService reportService;

    private UUID orgId;
    private User reporter;
    private ScanLog scanLog;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();

        reporter = User.builder().email("user@test.com").build();
        ReflectionTestUtils.setField(reporter, "id", UUID.randomUUID());

        Organization org = Organization.builder().name("Test Org").build();
        ReflectionTestUtils.setField(org, "id", orgId);

        Website website = Website.builder().url("https://example.com").organization(org).build();
        ReflectionTestUtils.setField(website, "id", UUID.randomUUID());

        Monitor monitor = Monitor.builder()
                .website(website)
                .type(Monitor.MonitorType.COMPLIANCE)
                .subType(Monitor.SubType.PRIVACY_POLICY)
                .build();
        ReflectionTestUtils.setField(monitor, "id", UUID.randomUUID());

        scanLog = ScanLog.builder()
                .monitor(monitor)
                .severity(ScanLog.Severity.HIGH)
                .build();
        ReflectionTestUtils.setField(scanLog, "id", UUID.randomUUID());
    }

    @Test
    @DisplayName("report() sets reported flag and persists")
    void report_setsFieldsAndSaves() {
        when(scanLogRepository.findByIdWithDetails(scanLog.getId())).thenReturn(Optional.of(scanLog));
        when(scanLogRepository.saveAndFlush(any())).thenReturn(scanLog);
        when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of());

        reportService.report(scanLog.getId(), orgId, reporter, "Looks wrong");

        assertThat(scanLog.isReported()).isTrue();
        assertThat(scanLog.getReportNotes()).isEqualTo("Looks wrong");
        assertThat(scanLog.getReportedBy()).isEqualTo(reporter);
        assertThat(scanLog.getReportedAt()).isNotNull();
        verify(scanLogRepository).saveAndFlush(scanLog);
    }

    @Test
    @DisplayName("report() strips whitespace from notes")
    void report_stripsNotes() {
        when(scanLogRepository.findByIdWithDetails(scanLog.getId())).thenReturn(Optional.of(scanLog));
        when(scanLogRepository.saveAndFlush(any())).thenReturn(scanLog);
        when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of());

        reportService.report(scanLog.getId(), orgId, reporter, "  spaced notes  ");

        assertThat(scanLog.getReportNotes()).isEqualTo("spaced notes");
    }

    @Test
    @DisplayName("report() throws 403 when scan belongs to different org")
    void report_wrongOrg_throws403() {
        UUID otherOrg = UUID.randomUUID();
        when(scanLogRepository.findByIdWithDetails(scanLog.getId())).thenReturn(Optional.of(scanLog));

        assertThatThrownBy(() -> reportService.report(scanLog.getId(), otherOrg, reporter, "notes"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(scanLogRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("report() throws 404 when scan log not found")
    void report_notFound_throws404() {
        UUID unknownId = UUID.randomUUID();
        when(scanLogRepository.findByIdWithDetails(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.report(unknownId, orgId, reporter, "notes"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("resolve() clears all report fields")
    void resolve_clearsAllFields() {
        scanLog.setReported(true);
        scanLog.setReportNotes("some notes");
        scanLog.setReportedBy(reporter);

        when(scanLogRepository.findById(scanLog.getId())).thenReturn(Optional.of(scanLog));

        reportService.resolve(scanLog.getId());

        assertThat(scanLog.isReported()).isFalse();
        assertThat(scanLog.getReportNotes()).isNull();
        assertThat(scanLog.getReportedBy()).isNull();
        assertThat(scanLog.getReportedAt()).isNull();
        verify(scanLogRepository).save(scanLog);
    }

    @Test
    @DisplayName("resolve() throws 404 when scan log not found")
    void resolve_notFound_throws404() {
        UUID unknownId = UUID.randomUUID();
        when(scanLogRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.resolve(unknownId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
