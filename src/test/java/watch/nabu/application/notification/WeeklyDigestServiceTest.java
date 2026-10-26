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
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.OrganizationMember;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.ScanLogRepository;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WeeklyDigestServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private OrganizationMemberRepository organizationMemberRepository;
    @Mock
    private ScanLogRepository scanLogRepository;
    @Mock
    private OrgNotificationSettingsService orgNotificationSettingsService;
    @Mock
    private MessageDispatcher messageDispatcher;
    @Mock
    private UnsubscribeTokenService unsubscribeTokenService;

    @InjectMocks
    private WeeklyDigestService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseUrl", "https://app.nabu.test");
        lenient().when(unsubscribeTokenService.buildUnsubscribeUrl(any(), eq("digest")))
                .thenReturn("https://app.nabu.test/unsubscribe?token=test-token");
    }

    @Test
    @DisplayName("happy path: user with scans + failing websites receives a digest email")
    void happyPathSendsDigest() {
        User user = user("digest@example.com", true);
        Organization org = org("Acme");

        when(organizationMemberRepository.findAllByUserIdWithOrganization(user.getId()))
                .thenReturn(List.of(member(user, org)));
        when(orgNotificationSettingsService.getDigestEnabledOrgIds(user.getId()))
                .thenReturn(Set.of(org.getId()));
        when(scanLogRepository.countByOrganizationIdSince(eq(org.getId()), any(Instant.class)))
                .thenReturn(42L);
        when(scanLogRepository.countOpenHighCriticalByOrganizationId(org.getId())).thenReturn(3L);
        when(scanLogRepository.findFailingWebsitesByOrganizationId(eq(org.getId()), anyInt()))
                .thenReturn(List.of(
                        new Object[]{UUID.randomUUID(), "https://broken.example.com", 2L},
                        new Object[]{UUID.randomUUID(), "https://another.example.com", 1L}));

        boolean sent = service.buildAndSendFor(user, Instant.now(), new HashMap<>());

        assertThat(sent).isTrue();
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageDispatcher, times(1)).dispatch(captor.capture());
        Message message = captor.getValue();
        assertThat(message.recipient()).isEqualTo("digest@example.com");
        assertThat(message.subject()).contains("weekly");
        assertThat(message.htmlBody()).contains("Acme").contains("42").contains("broken.example.com");
        assertThat(message.textBody()).contains("Acme").contains("42 scans").contains("3 open");
    }

    @Test
    @DisplayName("AND logic: user opted-in but org-level digest disabled → org skipped, no email")
    void orgLevelDigestDisabledSkipsOrg() {
        User user = user("digest@example.com", true);
        Organization org = org("DisabledOrg");

        when(organizationMemberRepository.findAllByUserIdWithOrganization(user.getId()))
                .thenReturn(List.of(member(user, org)));
        // Owner has NOT enabled digest for this user at org level
        when(orgNotificationSettingsService.getDigestEnabledOrgIds(user.getId()))
                .thenReturn(Set.of());

        boolean sent = service.buildAndSendFor(user, Instant.now(), new HashMap<>());

        assertThat(sent).isFalse();
        verify(messageDispatcher, never()).dispatch(any(Message.class));
    }

    @Test
    @DisplayName("user with no memberships → no email sent")
    void noMembershipsSendsNothing() {
        User user = user("lonely@example.com", true);
        when(organizationMemberRepository.findAllByUserIdWithOrganization(user.getId()))
                .thenReturn(List.of());

        boolean sent = service.buildAndSendFor(user, Instant.now(), new HashMap<>());

        assertThat(sent).isFalse();
        verify(messageDispatcher, never()).dispatch(any(Message.class));
    }

    @Test
    @DisplayName("user with zero scans, zero open issues, no failing websites → no email sent")
    void noActivitySendsNothing() {
        User user = user("quiet@example.com", true);
        Organization org = org("QuietOrg");

        when(organizationMemberRepository.findAllByUserIdWithOrganization(user.getId()))
                .thenReturn(List.of(member(user, org)));
        when(orgNotificationSettingsService.getDigestEnabledOrgIds(user.getId()))
                .thenReturn(Set.of(org.getId()));
        when(scanLogRepository.countByOrganizationIdSince(eq(org.getId()), any(Instant.class)))
                .thenReturn(0L);
        when(scanLogRepository.countOpenHighCriticalByOrganizationId(org.getId())).thenReturn(0L);
        when(scanLogRepository.findFailingWebsitesByOrganizationId(eq(org.getId()), anyInt()))
                .thenReturn(List.of());

        boolean sent = service.buildAndSendFor(user, Instant.now(), new HashMap<>());

        assertThat(sent).isFalse();
        verify(messageDispatcher, never()).dispatch(any(Message.class));
    }

    @Test
    @DisplayName("scheduled tick only consults DB-filtered active+opted-in users")
    void scheduledTickFiltersUsers() {
        User active = user("active@example.com", true);
        active.setStatus(User.AccountStatus.ACTIVE);

        when(userRepository.findAllByWeeklyDigestEnabledTrueAndStatus(User.AccountStatus.ACTIVE))
                .thenReturn(List.of(active));
        when(organizationMemberRepository.findAllByUserIdWithOrganization(active.getId()))
                .thenReturn(List.of());

        service.sendWeeklyDigests();

        verify(messageDispatcher, never()).dispatch(any(Message.class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User user(String email, boolean weeklyDigestEnabled) {
        User u = User.builder()
                .email(email)
                .fullName("Test " + email)
                .weeklyDigestEnabled(weeklyDigestEnabled)
                .status(User.AccountStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    private Organization org(String name) {
        Organization o = Organization.builder().name(name).enabled(true).build();
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        return o;
    }

    private OrganizationMember member(User user, Organization org) {
        OrganizationMember m = OrganizationMember.builder()
                .user(user)
                .organization(org)
                .role(OrganizationMember.Role.OWNER)
                .build();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        return m;
    }
}
