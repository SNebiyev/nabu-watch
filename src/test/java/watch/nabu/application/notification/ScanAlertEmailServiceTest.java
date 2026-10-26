package watch.nabu.application.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.messaging.Message;
import watch.nabu.application.messaging.MessageDispatcher;
import watch.nabu.application.scan.ScanResultEvent;
import watch.nabu.application.scan.ScanResultSummary;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.OrganizationMember;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.ScanLogRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScanAlertEmailServiceTest {

    private static final UUID SCAN_LOG_ID = UUID.randomUUID();
    private static final UUID MONITOR_ID = UUID.randomUUID();
    private static final UUID WEBSITE_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final String URL = "https://example.com";

    @Mock
    private ScanLogRepository scanLogRepository;
    @Mock
    private OrganizationMemberRepository organizationMemberRepository;
    @Mock
    private OrgNotificationSettingsService orgNotificationSettingsService;
    @Mock
    private MessageDispatcher messageDispatcher;
    @Mock
    private UnsubscribeTokenService unsubscribeTokenService;

    @InjectMocks
    private ScanAlertEmailService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseUrl", "https://app.nabu.test");
        lenient().when(unsubscribeTokenService.buildUnsubscribeUrl(any(), eq("alerts")))
                .thenReturn("https://app.nabu.test/unsubscribe?token=test-token");
    }

    @Test
    @DisplayName("regression INFO→HIGH sends alert emails to opted-in members only")
    void regressionIntoHighSendsToOptedInMembers() {
        ScanResultEvent event = buildEvent(Severity.HIGH);
        ScanLog scanLog = scanLogWithPreviousSeverity(Severity.INFO);
        when(scanLogRepository.findById(SCAN_LOG_ID)).thenReturn(Optional.of(scanLog));

        User optedIn = user("optin@example.com", true);
        User optedOut = user("optout@example.com", false);
        when(organizationMemberRepository.findActiveByOrganizationIdWithUser(ORG_ID))
                .thenReturn(List.of(member(optedIn), member(optedOut)));
        when(orgNotificationSettingsService.getAlertEnabledUserIds(ORG_ID))
                .thenReturn(Set.of(optedIn.getId(), optedOut.getId()));

        service.handle(event);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageDispatcher, times(1)).dispatch(captor.capture());
        Message sent = captor.getValue();
        assertThat(sent.recipient()).isEqualTo("optin@example.com");
        assertThat(sent.subject()).contains("HIGH").contains(URL);
        assertThat(sent.htmlBody()).contains("HIGH").contains(URL);
    }

    @Test
    @DisplayName("first-ever failing scan (previousSeverity=null, current=CRITICAL) is treated as regression")
    void firstEverFailingScanIsRegression() {
        ScanResultEvent event = buildEvent(Severity.CRITICAL);
        ScanLog scanLog = scanLogWithPreviousSeverity(null);
        when(scanLogRepository.findById(SCAN_LOG_ID)).thenReturn(Optional.of(scanLog));

        User recipient = user("first@example.com", true);
        when(organizationMemberRepository.findActiveByOrganizationIdWithUser(ORG_ID))
                .thenReturn(List.of(member(recipient)));
        when(orgNotificationSettingsService.getAlertEnabledUserIds(ORG_ID))
                .thenReturn(Set.of(recipient.getId()));

        service.handle(event);

        verify(messageDispatcher, times(1)).dispatch(any(Message.class));
    }

    @Test
    @DisplayName("AND logic: user opted-in but org-level disabled → no email")
    void orgLevelDisabledBlocksAlert() {
        ScanResultEvent event = buildEvent(Severity.HIGH);
        ScanLog scanLog = scanLogWithPreviousSeverity(Severity.INFO);
        when(scanLogRepository.findById(SCAN_LOG_ID)).thenReturn(Optional.of(scanLog));

        User userOptedIn = user("user@example.com", true);
        when(organizationMemberRepository.findActiveByOrganizationIdWithUser(ORG_ID))
                .thenReturn(List.of(member(userOptedIn)));
        // Owner has NOT enabled alerts for this user at org level
        when(orgNotificationSettingsService.getAlertEnabledUserIds(ORG_ID))
                .thenReturn(Set.of());

        service.handle(event);

        verify(messageDispatcher, never()).dispatch(any(Message.class));
    }

    @Test
    @DisplayName("non-regression (HIGH→HIGH) does NOT send any email")
    void sameSeverityIsNotRegression() {
        ScanResultEvent event = buildEvent(Severity.HIGH);
        ScanLog scanLog = scanLogWithPreviousSeverity(Severity.HIGH);
        when(scanLogRepository.findById(SCAN_LOG_ID)).thenReturn(Optional.of(scanLog));

        service.handle(event);

        verify(messageDispatcher, never()).dispatch(any(Message.class));
        verify(organizationMemberRepository, never()).findActiveByOrganizationIdWithUser(any());
    }

    @Test
    @DisplayName("non-failing current severity (MEDIUM) does NOT send any email")
    void nonFailingSeverityDoesNothing() {
        ScanResultEvent event = buildEvent(Severity.MEDIUM);

        service.handle(event);

        verify(scanLogRepository, never()).findById(any());
        verify(messageDispatcher, never()).dispatch(any(Message.class));
    }

    @Test
    @DisplayName("all members opted out at user level → zero emails sent")
    void optOutRespectedForAllMembers() {
        ScanResultEvent event = buildEvent(Severity.HIGH);
        ScanLog scanLog = scanLogWithPreviousSeverity(Severity.INFO);
        when(scanLogRepository.findById(SCAN_LOG_ID)).thenReturn(Optional.of(scanLog));

        User a = user("a@e.com", false);
        User b = user("b@e.com", false);
        when(organizationMemberRepository.findActiveByOrganizationIdWithUser(ORG_ID))
                .thenReturn(List.of(member(a), member(b)));
        when(orgNotificationSettingsService.getAlertEnabledUserIds(ORG_ID))
                .thenReturn(Set.of(a.getId(), b.getId()));

        service.handle(event);

        verify(messageDispatcher, never()).dispatch(any(Message.class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ScanResultEvent buildEvent(Severity severity) {
        ScanResultSummary summary = new ScanResultSummary(
                SCAN_LOG_ID, MONITOR_ID, Monitor.MonitorType.COMPLIANCE,
                Monitor.SubType.PRIVACY_POLICY, WEBSITE_ID, URL, ORG_ID,
                severity, Instant.now());
        return new ScanResultEvent(this, summary);
    }

    private ScanLog scanLogWithPreviousSeverity(Severity previous) {
        ScanLog log = ScanLog.builder()
                .severity(Severity.HIGH)
                .previousSeverity(previous)
                .build();
        return log;
    }

    private User user(String email, boolean emailAlertsEnabled) {
        User u = User.builder()
                .email(email)
                .fullName("Test " + email)
                .emailAlertsEnabled(emailAlertsEnabled)
                .build();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    private OrganizationMember member(User user) {
        return OrganizationMember.builder()
                .user(user)
                .role(OrganizationMember.Role.OWNER)
                .build();
    }
}
