package watch.nabu.application.gdpr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.storage.FileStoragePort;
import watch.nabu.domain.model.*;
import watch.nabu.infrastructure.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    OrganizationMemberRepository orgMemberRepository;
    @Mock
    OrganizationRepository orgRepository;
    @Mock
    WebsiteRepository websiteRepository;
    @Mock
    MonitorRepository monitorRepository;
    @Mock
    ScanLogRepository scanLogRepository;
    @Mock
    StoredFileRepository storedFileRepository;
    @Mock
    MonitorStatusOverrideRepository monitorStatusOverrideRepository;
    @Mock
    WebsiteCompliancePathRepository websiteCompliancePathRepository;
    @Mock
    WebhookDeliveryRepository webhookDeliveryRepository;
    @Mock
    WebhookConfigRepository webhookConfigRepository;
    @Mock
    OrgNotificationSettingRepository orgNotificationSettingRepository;
    @Mock
    NotificationRepository notificationRepository;
    @Mock
    InternalMessageRepository internalMessageRepository;
    @Mock
    FileStoragePort fileStoragePort;

    @InjectMocks
    AccountDeletionService service;

    // ── helpers ──────────────────────────────────────────────────────────────

    private static User user() {
        User u = User.builder()
                .email("user@example.com")
                .fullName("Test User")
                .build();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    private static Organization org() {
        Organization o = new Organization();
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        o.setName("Test Org");
        return o;
    }

    private static OrganizationMember membership(User user, Organization org, OrganizationMember.Role role) {
        OrganizationMember m = OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(role)
                .build();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        return m;
    }

    private static Website website(Organization org) {
        Website w = Website.builder()
                .url("https://example.com")
                .organization(org)
                .regionCode("DE")
                .build();
        ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        return w;
    }

    private static Monitor monitor(Website website) {
        Monitor m = Monitor.builder()
                .website(website)
                .type(Monitor.MonitorType.COMPLIANCE)
                .subType(Monitor.SubType.PRIVACY_POLICY)
                .intervalMinutes(60)
                .build();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        return m;
    }

    // ── requestDeletion tests ───────────────────────────────────────────────

    @Test
    void requestDeletion_setsStatusAndTimestamp() {
        User u = user();
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));

        service.requestDeletion(u.getId());

        assertThat(u.getStatus()).isEqualTo(User.AccountStatus.DELETED);
        assertThat(u.getDeletionRequestedAt()).isNotNull();
        verify(userRepository).save(u);
    }

    @Test
    void requestDeletion_userNotFound_throws() {
        UUID unknownId = UUID.randomUUID();
        when(userRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestDeletion(unknownId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── purgeExpiredAccounts tests ───────────────────────────────────────────

    @Test
    void purgeExpiredAccounts_noExpiredUsers_returnsZero() {
        when(userRepository.findByStatusAndDeletionRequestedAtBefore(
                eq(User.AccountStatus.DELETED), any(Instant.class)))
                .thenReturn(List.of());

        int count = service.purgeExpiredAccounts();
        assertThat(count).isZero();
    }

    // ── hardDeleteUser — sole-owner org ─────────────────────────────────────

    @Test
    void hardDeleteUser_soleOwnerOrg_cascadeDeletesAll() {
        User u = user();
        Organization o = org();
        OrganizationMember mem = membership(u, o, OrganizationMember.Role.OWNER);
        Website w = website(o);
        Monitor mon = monitor(w);

        when(orgMemberRepository.findAllByUserId(u.getId())).thenReturn(List.of(mem));
        when(orgMemberRepository.countByOrganizationId(o.getId())).thenReturn(1L);
        when(websiteRepository.findAllByOrganizationIdIn(anyCollection())).thenReturn(List.of(w));
        when(monitorRepository.findByWebsiteIdIn(anyList())).thenReturn(List.of(mon));
        when(webhookConfigRepository.findByOrganizationIdIn(anyCollection())).thenReturn(List.of());
        when(storedFileRepository.findByOrganizationIdIn(anyCollection())).thenReturn(List.of());

        service.hardDeleteUser(u);

        // Verify cascade delete order
        verify(scanLogRepository).deleteByMonitorIdIn(List.of(mon.getId()));
        verify(monitorStatusOverrideRepository).deleteByMonitorIdIn(List.of(mon.getId()));
        verify(monitorRepository).deleteByWebsiteIdIn(List.of(w.getId()));
        verify(websiteCompliancePathRepository).deleteByWebsiteIdIn(List.of(w.getId()));
        verify(websiteRepository).deleteByOrganizationIdIn(anyCollection());
        verify(orgRepository).deleteAllByIdIn(anyCollection());

        // User-level cleanup
        verify(orgNotificationSettingRepository).deleteByUserId(u.getId());
        verify(orgMemberRepository).deleteByUserId(u.getId());
        verify(notificationRepository).deleteByUserId(u.getId());
        verify(internalMessageRepository).deleteBySenderOrReceiver(u.getEmail());
        verify(userRepository).delete(u);
    }

    // ── hardDeleteUser — shared org (member, not sole owner) ────────────────

    @Test
    void hardDeleteUser_sharedOrg_onlyNullifiesAndRemovesMembership() {
        User u = user();
        Organization o = org();
        OrganizationMember mem = membership(u, o, OrganizationMember.Role.MEMBER);

        when(orgMemberRepository.findAllByUserId(u.getId())).thenReturn(List.of(mem));
        // Not sole owner since role is MEMBER — enters sharedOrgIds path
        // countByOrganizationId not called because role != OWNER

        service.hardDeleteUser(u);

        // Shared org: should nullify FKs
        verify(scanLogRepository).nullifyTriggeredBy(u.getId());
        verify(monitorRepository).nullifyCreatedBy(u.getId());
        verify(websiteRepository).nullifyAddedBy(u.getId());

        // Should NOT cascade-delete org data
        verify(orgRepository, never()).deleteAllByIdIn(anyCollection());
        verify(websiteRepository, never()).deleteByOrganizationIdIn(anyCollection());

        // User-level cleanup still happens
        verify(orgNotificationSettingRepository).deleteByUserId(u.getId());
        verify(orgMemberRepository).deleteByUserId(u.getId());
        verify(notificationRepository).deleteByUserId(u.getId());
        verify(internalMessageRepository).deleteBySenderOrReceiver(u.getEmail());
        verify(userRepository).delete(u);
    }

    // ── hardDeleteUser — owner but not sole (other members exist) ───────────

    @Test
    void hardDeleteUser_ownerWithOtherMembers_treatedAsSharedOrg() {
        User u = user();
        Organization o = org();
        OrganizationMember mem = membership(u, o, OrganizationMember.Role.OWNER);

        when(orgMemberRepository.findAllByUserId(u.getId())).thenReturn(List.of(mem));
        when(orgMemberRepository.countByOrganizationId(o.getId())).thenReturn(3L); // 3 members

        service.hardDeleteUser(u);

        // Shared org path
        verify(scanLogRepository).nullifyTriggeredBy(u.getId());
        verify(monitorRepository).nullifyCreatedBy(u.getId());
        verify(websiteRepository).nullifyAddedBy(u.getId());

        // No cascade delete
        verify(orgRepository, never()).deleteAllByIdIn(anyCollection());
        verify(userRepository).delete(u);
    }

    // ── MinIO cleanup logs failures but does not abort ──────────────────────

    @Test
    void hardDeleteUser_minioFailure_logsButContinues() {
        User u = user();
        Organization o = org();
        OrganizationMember mem = membership(u, o, OrganizationMember.Role.OWNER);

        StoredFile sf = StoredFile.builder()
                .organizationId(o.getId())
                .entityType(StoredFile.EntityType.SCAN_LOG)
                .entityId(UUID.randomUUID())
                .originalName("screenshot.jpg")
                .storedName("abc123.jpg")
                .storagePath("screenshots/abc123.jpg")
                .contentType("image/jpeg")
                .fileSize(1024)
                .bucketName("nabu-files")
                .build();
        ReflectionTestUtils.setField(sf, "id", UUID.randomUUID());

        when(orgMemberRepository.findAllByUserId(u.getId())).thenReturn(List.of(mem));
        when(orgMemberRepository.countByOrganizationId(o.getId())).thenReturn(1L);
        when(websiteRepository.findAllByOrganizationIdIn(anyCollection())).thenReturn(List.of());
        when(webhookConfigRepository.findByOrganizationIdIn(anyCollection())).thenReturn(List.of());
        when(storedFileRepository.findByOrganizationIdIn(anyCollection())).thenReturn(List.of(sf));
        doThrow(new RuntimeException("MinIO down")).when(fileStoragePort)
                .delete("nabu-files", "screenshots/abc123.jpg");

        // Should not throw despite MinIO failure
        service.hardDeleteUser(u);

        verify(fileStoragePort).delete("nabu-files", "screenshots/abc123.jpg");
        verify(userRepository).delete(u);
    }
}
