package watch.nabu.application.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.scan.ScanSummaryData;
import watch.nabu.domain.model.*;
import watch.nabu.domain.model.Notification.NotificationType;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.infrastructure.persistence.NotificationRepository;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.OrganizationRepository;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID WEBSITE_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final String ORG_NAME = "Acme Corp";
    private static final String WEBSITE_URL = "https://example.com";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-30T10:00:00Z");
    @Mock
    NotificationRepository notificationRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    OrganizationMemberRepository organizationMemberRepository;
    @Mock
    OrganizationRepository organizationRepository;
    @Mock
    SimpMessagingTemplate messagingTemplate;
    @InjectMocks
    NotificationService service;

    /**
     * Simulates what Hibernate does on saveAndFlush: assigns id + sets @CreationTimestamp.
     */
    private void stubSaveAndFlush() {
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            ReflectionTestUtils.setField(n, "id", UUID.randomUUID());
            n.setCreatedAt(FIXED_INSTANT);
            return n;
        });
    }

    private User buildUser() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setEmail("user@example.com");
        user.setCurrentOrganizationId(ORG_ID);
        return user;
    }

    private User buildUser(UUID id, String email) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail(email);
        return user;
    }

    private Organization buildOrg() {
        Organization org = new Organization();
        ReflectionTestUtils.setField(org, "id", ORG_ID);
        org.setName(ORG_NAME);
        return org;
    }

    private OrganizationMember buildMember(UUID userId, String email, OrganizationMember.Role role) {
        User user = buildUser(userId, email);
        return OrganizationMember.builder()
                .organization(buildOrg())
                .user(user)
                .role(role)
                .build();
    }

    // ── sendScanSummaryNotification ───────────────────────────────────────────

    @Test
    @DisplayName("sendScanSummaryNotification: MANUAL trigger sends only to caller, not org members")
    void scanSummary_manual_sendsOnlyToCaller() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        ScanSummaryData summary = new ScanSummaryData(
                UUID.randomUUID(), WEBSITE_ID, WEBSITE_URL, USER_ID,
                "MANUAL",
                Map.of(Monitor.SubType.COOKIE_POLICY, Severity.MEDIUM),
                null,
                false
        );

        service.sendScanSummaryNotification(summary);

        // Only one notification saved — the caller
        verify(notificationRepository, times(1)).saveAndFlush(any());
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getOrganizationName()).isEqualTo(ORG_NAME);

        // Title has [Manual] prefix
        ArgumentCaptor<NotificationService.NotificationPayload> payloadCaptor =
                ArgumentCaptor.forClass(NotificationService.NotificationPayload.class);
        verify(messagingTemplate).convertAndSendToUser(any(), any(), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().title()).startsWith("[Manual]");
        assertThat(payloadCaptor.getValue().organizationName()).isEqualTo(ORG_NAME);
    }

    @Test
    @DisplayName("sendScanSummaryNotification: SCHEDULED trigger sends to all org members")
    void scanSummary_scheduled_sendsToAllOrgMembers() {
        stubSaveAndFlush();

        UUID member1Id = UUID.randomUUID();
        UUID member2Id = UUID.randomUUID();
        OrganizationMember m1 = buildMember(member1Id, "owner@example.com", OrganizationMember.Role.OWNER);
        OrganizationMember m2 = buildMember(member2Id, "member@example.com", OrganizationMember.Role.MEMBER);

        when(organizationMemberRepository.findActiveByOrganizationIdWithUser(ORG_ID))
                .thenReturn(List.of(m1, m2));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));
        when(userRepository.findById(member1Id)).thenReturn(Optional.of(buildUser(member1Id, "owner@example.com")));
        when(userRepository.findById(member2Id)).thenReturn(Optional.of(buildUser(member2Id, "member@example.com")));

        // For scheduled scans, userId is the orgId
        ScanSummaryData summary = new ScanSummaryData(
                UUID.randomUUID(), WEBSITE_ID, WEBSITE_URL, ORG_ID,
                "SCHEDULED",
                Map.of(Monitor.SubType.PRIVACY_POLICY, Severity.INFO),
                null,
                false
        );

        service.sendScanSummaryNotification(summary);

        // Two notifications saved — one per member
        verify(notificationRepository, times(2)).saveAndFlush(any());
        verify(messagingTemplate, times(2)).convertAndSendToUser(any(), any(), any());
    }

    @Test
    @DisplayName("sendScanSummaryNotification: persists and delivers payload with non-null createdAt")
    void scanSummary_happyPath() {
        stubSaveAndFlush();
        User user = buildUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        ScanSummaryData summary = new ScanSummaryData(
                UUID.randomUUID(), WEBSITE_ID, WEBSITE_URL, USER_ID,
                "MANUAL",
                Map.of(Monitor.SubType.PRIVACY_POLICY, Severity.INFO),
                null,
                false
        );

        service.sendScanSummaryNotification(summary);

        ArgumentCaptor<NotificationService.NotificationPayload> payloadCaptor =
                ArgumentCaptor.forClass(NotificationService.NotificationPayload.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq("user@example.com"), eq("/queue/notifications"), payloadCaptor.capture());

        NotificationService.NotificationPayload payload = payloadCaptor.getValue();
        assertThat(payload.createdAt()).isNotNull().isEqualTo(FIXED_INSTANT.toString());
        assertThat(payload.type()).isEqualTo(NotificationType.SCAN_SUMMARY.name());
        assertThat(payload.relatedEntityId()).isEqualTo(WEBSITE_ID);
        assertThat(payload.websiteUrl()).isEqualTo(WEBSITE_URL);
    }

    @Test
    @DisplayName("sendScanSummaryNotification: all-INFO results produce 'no issues' message")
    void scanSummary_allInfo_noIssuesMessage() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        ScanSummaryData summary = new ScanSummaryData(
                UUID.randomUUID(), WEBSITE_ID, WEBSITE_URL, USER_ID,
                "MANUAL",
                Map.of(
                        Monitor.SubType.PRIVACY_POLICY, Severity.INFO,
                        Monitor.SubType.COOKIE_POLICY, Severity.INFO
                ),
                null,
                false
        );

        service.sendScanSummaryNotification(summary);

        ArgumentCaptor<NotificationService.NotificationPayload> captor =
                ArgumentCaptor.forClass(NotificationService.NotificationPayload.class);
        verify(messagingTemplate).convertAndSendToUser(any(), any(), captor.capture());
        assertThat(captor.getValue().message()).contains("All 2 checks passed.");
    }

    @Test
    @DisplayName("sendScanSummaryNotification: non-INFO results produce 'issues found' message")
    void scanSummary_hasIssues_issueCountInMessage() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        ScanSummaryData summary = new ScanSummaryData(
                UUID.randomUUID(), WEBSITE_ID, WEBSITE_URL, USER_ID,
                "MANUAL",
                Map.of(
                        Monitor.SubType.PRIVACY_POLICY, Severity.MEDIUM,
                        Monitor.SubType.COOKIE_POLICY, Severity.INFO
                ),
                null,
                false
        );

        service.sendScanSummaryNotification(summary);

        ArgumentCaptor<NotificationService.NotificationPayload> captor =
                ArgumentCaptor.forClass(NotificationService.NotificationPayload.class);
        verify(messagingTemplate).convertAndSendToUser(any(), any(), captor.capture());
        assertThat(captor.getValue().message()).contains("PRIV:MEDIUM").contains("1 of 2 checks passed.");
    }

    @Test
    @DisplayName("sendScanSummaryNotification: no WS delivery when user not found, but notification is still saved")
    void scanSummary_userNotFound_noWsDelivery() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        ScanSummaryData summary = new ScanSummaryData(
                UUID.randomUUID(), WEBSITE_ID, WEBSITE_URL, USER_ID,
                "MANUAL",
                Map.of(),
                null,
                false
        );

        service.sendScanSummaryNotification(summary);

        verify(notificationRepository).saveAndFlush(any());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    // ── sendScanCompletedNotification ─────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = Severity.class, names = {"INFO", "MEDIUM", "HIGH", "CRITICAL"})
    @DisplayName("sendScanCompletedNotification: payload always has non-null createdAt for all severities")
    void scanCompleted_createdAtNonNull_allSeverities(Severity severity) {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

        service.sendScanCompletedNotification(USER_ID, WEBSITE_ID, WEBSITE_URL, severity,
                Monitor.SubType.SSL);

        ArgumentCaptor<NotificationService.NotificationPayload> captor =
                ArgumentCaptor.forClass(NotificationService.NotificationPayload.class);
        verify(messagingTemplate).convertAndSendToUser(any(), any(), captor.capture());
        assertThat(captor.getValue().createdAt()).isNotNull();
    }

    @Test
    @DisplayName("sendScanCompletedNotification: CRITICAL severity maps to CERTIFICATE_EXPIRY type")
    void scanCompleted_criticalSeverity_certificateExpiryType() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

        service.sendScanCompletedNotification(USER_ID, WEBSITE_ID, WEBSITE_URL, Severity.CRITICAL,
                Monitor.SubType.SSL);

        ArgumentCaptor<NotificationService.NotificationPayload> captor =
                ArgumentCaptor.forClass(NotificationService.NotificationPayload.class);
        verify(messagingTemplate).convertAndSendToUser(any(), any(), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(NotificationType.CERTIFICATE_EXPIRY.name());
    }

    @Test
    @DisplayName("sendScanCompletedNotification: INFO severity maps to SCAN_COMPLETED type")
    void scanCompleted_infoSeverity_scanCompletedType() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

        service.sendScanCompletedNotification(USER_ID, WEBSITE_ID, WEBSITE_URL, Severity.INFO,
                Monitor.SubType.SSL);

        ArgumentCaptor<NotificationService.NotificationPayload> captor =
                ArgumentCaptor.forClass(NotificationService.NotificationPayload.class);
        verify(messagingTemplate).convertAndSendToUser(any(), any(), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(NotificationType.SCAN_COMPLETED.name());
    }

    @Test
    @DisplayName("sendScanCompletedNotification: message line 2 contains abbreviated subType badge")
    void scanCompleted_messageContainsSubTypeBadgeOnSecondLine() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

        service.sendScanCompletedNotification(USER_ID, WEBSITE_ID, WEBSITE_URL, Severity.INFO,
                Monitor.SubType.PRIVACY_POLICY);

        ArgumentCaptor<NotificationService.NotificationPayload> captor =
                ArgumentCaptor.forClass(NotificationService.NotificationPayload.class);
        verify(messagingTemplate).convertAndSendToUser(any(), any(), captor.capture());
        String[] lines = captor.getValue().message().split("\n");
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
        assertThat(lines[1]).isEqualTo("PRIV:INFO");
    }

    @Test
    @DisplayName("COOKIE_BANNER HIGH severity → COMPLIANCE_ISSUE type, not CERTIFICATE_EXPIRY")
    void scanCompleted_cookieBannerHigh_complianceIssueType() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

        service.sendScanCompletedNotification(USER_ID, WEBSITE_ID, WEBSITE_URL, Severity.HIGH,
                Monitor.SubType.COOKIE_BANNER);

        ArgumentCaptor<Notification> notifCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(notifCaptor.capture());
        assertThat(notifCaptor.getValue().getType()).isEqualTo(NotificationType.COMPLIANCE_ISSUE);
        assertThat(notifCaptor.getValue().getMessage()).doesNotContain("SSL certificate expires");
        assertThat(notifCaptor.getValue().getMessage()).contains("CKBNR:HIGH");
    }

    @Test
    @DisplayName("SSL HIGH severity → CERTIFICATE_EXPIRY type with correct expiry message")
    void scanCompleted_sslHigh_certificateExpiryType() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

        service.sendScanCompletedNotification(USER_ID, WEBSITE_ID, WEBSITE_URL, Severity.HIGH,
                Monitor.SubType.SSL);

        ArgumentCaptor<Notification> notifCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(notifCaptor.capture());
        assertThat(notifCaptor.getValue().getType()).isEqualTo(NotificationType.CERTIFICATE_EXPIRY);
        assertThat(notifCaptor.getValue().getMessage()).contains("SSL certificate expires in < 14 days");
    }

    @Test
    @DisplayName("sendScanCompletedNotification: organizationId is set when provided")
    void scanCompleted_withOrgId_setsOrgFields() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        service.sendScanCompletedNotification(USER_ID, WEBSITE_ID, WEBSITE_URL, Severity.INFO,
                Monitor.SubType.SSL, ORG_ID);

        ArgumentCaptor<Notification> notifCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(notifCaptor.capture());
        assertThat(notifCaptor.getValue().getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(notifCaptor.getValue().getOrganizationName()).isEqualTo(ORG_NAME);
    }

    // ── sendScanCompletedToOrg ───────────────────────────────────────────────

    @Test
    @DisplayName("sendScanCompletedToOrg: sends to all org members")
    void scanCompletedToOrg_sendsToAllMembers() {
        stubSaveAndFlush();

        UUID ownerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        OrganizationMember owner = buildMember(ownerId, "owner@example.com", OrganizationMember.Role.OWNER);
        OrganizationMember member = buildMember(memberId, "member@example.com", OrganizationMember.Role.MEMBER);

        when(organizationMemberRepository.findActiveByOrganizationIdWithUser(ORG_ID))
                .thenReturn(List.of(owner, member));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(buildUser(ownerId, "owner@example.com")));
        when(userRepository.findById(memberId)).thenReturn(Optional.of(buildUser(memberId, "member@example.com")));

        service.sendScanCompletedToOrg(ORG_ID, WEBSITE_ID, WEBSITE_URL, Severity.INFO, Monitor.SubType.SSL);

        verify(notificationRepository, times(2)).saveAndFlush(any());
        verify(messagingTemplate).convertAndSendToUser(eq("owner@example.com"), any(), any());
        verify(messagingTemplate).convertAndSendToUser(eq("member@example.com"), any(), any());
    }

    // ── sendSecurityAlertToAdmins ─────────────────────────────────────────────

    @Test
    @DisplayName("sendSecurityAlertToAdmins: delivers payload with non-null createdAt to each admin")
    void securityAlert_createdAtNonNull_perAdmin() {
        stubSaveAndFlush();

        User admin1 = new User();
        ReflectionTestUtils.setField(admin1, "id", UUID.randomUUID());
        admin1.setEmail("admin1@example.com");

        User admin2 = new User();
        ReflectionTestUtils.setField(admin2, "id", UUID.randomUUID());
        admin2.setEmail("admin2@example.com");

        service.sendSecurityAlertToAdmins("attacker@example.com", FIXED_INSTANT, List.of(admin1, admin2));

        ArgumentCaptor<NotificationService.NotificationPayload> captor =
                ArgumentCaptor.forClass(NotificationService.NotificationPayload.class);
        verify(messagingTemplate, times(2))
                .convertAndSendToUser(any(), eq("/queue/notifications"), captor.capture());
        captor.getAllValues().forEach(p -> assertThat(p.createdAt()).isNotNull());
    }

    @Test
    @DisplayName("sendSecurityAlertToAdmins: empty admin list — no saves and no WS deliveries")
    void securityAlert_emptyAdminList_noInteractions() {
        service.sendSecurityAlertToAdmins("attacker@example.com", FIXED_INSTANT, List.of());

        verify(notificationRepository, never()).saveAndFlush(any());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    // ── markAsRead ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("markAsRead: marks notification as read when owned by user")
    void markAsRead_ownedByUser_marksRead() {
        UUID notifId = UUID.randomUUID();
        Notification notif = Notification.builder().userId(USER_ID).title("t").message("m")
                .type(NotificationType.SCAN_COMPLETED).build();
        ReflectionTestUtils.setField(notif, "id", notifId);
        notif.setRead(false);
        when(notificationRepository.findById(notifId)).thenReturn(Optional.of(notif));

        service.markAsRead(notifId, USER_ID);

        assertThat(notif.isRead()).isTrue();
        verify(notificationRepository).save(notif);
    }

    @Test
    @DisplayName("markAsRead: ignores when notification belongs to different user")
    void markAsRead_differentUser_ignores() {
        UUID notifId = UUID.randomUUID();
        Notification notif = Notification.builder().userId(UUID.randomUUID()).title("t").message("m")
                .type(NotificationType.SCAN_COMPLETED).build();
        ReflectionTestUtils.setField(notif, "id", notifId);
        when(notificationRepository.findById(notifId)).thenReturn(Optional.of(notif));

        service.markAsRead(notifId, USER_ID);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("markAsRead: ignores when notification already read")
    void markAsRead_alreadyRead_ignores() {
        UUID notifId = UUID.randomUUID();
        Notification notif = Notification.builder().userId(USER_ID).title("t").message("m")
                .type(NotificationType.SCAN_COMPLETED).build();
        ReflectionTestUtils.setField(notif, "id", notifId);
        notif.setRead(true);
        when(notificationRepository.findById(notifId)).thenReturn(Optional.of(notif));

        service.markAsRead(notifId, USER_ID);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("markAsRead: ignores when notification not found")
    void markAsRead_notFound_ignores() {
        when(notificationRepository.findById(any())).thenReturn(Optional.empty());

        service.markAsRead(UUID.randomUUID(), USER_ID);

        verify(notificationRepository, never()).save(any());
    }

    // ── markAllAsRead ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("markAllAsRead: delegates to repository bulk update")
    void markAllAsRead_delegates() {
        when(notificationRepository.markAllReadByUserId(USER_ID)).thenReturn(5);

        service.markAllAsRead(USER_ID);

        verify(notificationRepository).markAllReadByUserId(USER_ID);
    }

    // ── getNotificationPage ───────────────────────────────────────────────────

    @Test
    @DisplayName("getNotificationPage: delegates to repository")
    void getNotificationPage_delegates() {
        org.springframework.data.domain.Page<Notification> page =
                new org.springframework.data.domain.PageImpl<>(List.of());
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any()))
                .thenReturn(page);

        var result = service.getNotificationPage(USER_ID,
                org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    // ── getUnreadCount ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getUnreadCount: returns count from repository")
    void getUnreadCount_returnsCount() {
        when(notificationRepository.countByUserIdAndReadFalse(USER_ID)).thenReturn(7L);

        assertThat(service.getUnreadCount(USER_ID)).isEqualTo(7L);
    }

    // ── getUnreadNotifications ────────────────────────────────────────────────

    @Test
    @DisplayName("getUnreadNotifications: filters unread from page results")
    void getUnreadNotifications_filtersUnread() {
        Notification read = Notification.builder().userId(USER_ID).title("r").message("m")
                .type(NotificationType.SCAN_COMPLETED).build();
        ReflectionTestUtils.setField(read, "id", UUID.randomUUID());
        read.setRead(true);
        read.setCreatedAt(FIXED_INSTANT);

        Notification unread = Notification.builder().userId(USER_ID).title("u").message("m")
                .type(NotificationType.SCAN_COMPLETED).build();
        ReflectionTestUtils.setField(unread, "id", UUID.randomUUID());
        unread.setRead(false);
        unread.setCreatedAt(FIXED_INSTANT);

        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(read, unread)));

        List<Notification> result = service.getUnreadNotifications(USER_ID, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("u");
    }

    // ── sendNewMessageNotification ────────────────────────────────────────────

    @Test
    @DisplayName("sendNewMessageNotification: admin sender shows as 'Support Team'")
    void sendNewMessageNotification_adminSender_maskedAsSupport() {
        stubSaveAndFlush();
        User admin = buildUser(UUID.randomUUID(), "admin@example.com");
        admin.setRole(User.Role.ADMIN);
        User receiver = buildUser(UUID.randomUUID(), "user@example.com");

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(receiver));

        service.sendNewMessageNotification("user@example.com", "admin@example.com", UUID.randomUUID());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("Support Team");
    }

    @Test
    @DisplayName("sendNewMessageNotification: non-admin sender shows real email")
    void sendNewMessageNotification_regularSender_showsEmail() {
        stubSaveAndFlush();
        User sender = buildUser(UUID.randomUUID(), "sender@example.com");
        sender.setRole(User.Role.USER);
        User receiver = buildUser(UUID.randomUUID(), "receiver@example.com");

        when(userRepository.findByEmail("sender@example.com")).thenReturn(Optional.of(sender));
        when(userRepository.findByEmail("receiver@example.com")).thenReturn(Optional.of(receiver));

        service.sendNewMessageNotification("receiver@example.com", "sender@example.com", UUID.randomUUID());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("sender@example.com");
    }

    @Test
    @DisplayName("sendNewMessageNotification: receiver not found — no save")
    void sendNewMessageNotification_receiverNotFound_noSave() {
        User sender = buildUser(UUID.randomUUID(), "sender@example.com");
        sender.setRole(User.Role.USER);
        when(userRepository.findByEmail("sender@example.com")).thenReturn(Optional.of(sender));
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        service.sendNewMessageNotification("unknown@example.com", "sender@example.com", UUID.randomUUID());

        verify(notificationRepository, never()).saveAndFlush(any());
    }

    // ── sendScanSummaryNotification: single monitor result ────────────────────

    @Test
    @DisplayName("sendScanSummaryNotification: single monitor INFO shows 'All checks passed'")
    void scanSummary_singleMonitor_allChecks_passed() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        ScanSummaryData summary = new ScanSummaryData(
                UUID.randomUUID(), WEBSITE_ID, WEBSITE_URL, USER_ID,
                "MANUAL",
                Map.of(Monitor.SubType.SSL, Severity.INFO),
                null,
                false
        );

        service.sendScanSummaryNotification(summary);

        ArgumentCaptor<NotificationService.NotificationPayload> captor =
                ArgumentCaptor.forClass(NotificationService.NotificationPayload.class);
        verify(messagingTemplate).convertAndSendToUser(any(), any(), captor.capture());
        assertThat(captor.getValue().message()).contains("All 1 checks passed.");
        assertThat(captor.getValue().message()).contains("SSL:INFO");
    }

    @Test
    @DisplayName("sendScanSummaryNotification: single monitor CRITICAL shows 'Failed'")
    void scanSummary_singleMonitor_failed() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        ScanSummaryData summary = new ScanSummaryData(
                UUID.randomUUID(), WEBSITE_ID, WEBSITE_URL, USER_ID,
                "MANUAL",
                Map.of(Monitor.SubType.SSL, Severity.CRITICAL),
                null,
                false
        );

        service.sendScanSummaryNotification(summary);

        ArgumentCaptor<NotificationService.NotificationPayload> captor =
                ArgumentCaptor.forClass(NotificationService.NotificationPayload.class);
        verify(messagingTemplate).convertAndSendToUser(any(), any(), captor.capture());
        assertThat(captor.getValue().message()).contains("SSL:CRITICAL").contains("0 of 1 checks passed.");
    }

    // ── sendScanCompletedNotification: compliance sub-types ───────────────────

    @Test
    @DisplayName("sendScanCompletedNotification: MEDIUM compliance → COMPLIANCE_ISSUE type")
    void scanCompleted_mediumCompliance_complianceIssueType() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

        service.sendScanCompletedNotification(USER_ID, WEBSITE_ID, WEBSITE_URL, Severity.MEDIUM,
                Monitor.SubType.IMPRESSUM);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.COMPLIANCE_ISSUE);
    }

    @Test
    @DisplayName("sendScanCompletedNotification: INFO compliance → SCAN_COMPLETED type")
    void scanCompleted_infoCompliance_scanCompletedType() {
        stubSaveAndFlush();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

        service.sendScanCompletedNotification(USER_ID, WEBSITE_ID, WEBSITE_URL, Severity.INFO,
                Monitor.SubType.GDPR_REQUEST);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.SCAN_COMPLETED);
    }
}
