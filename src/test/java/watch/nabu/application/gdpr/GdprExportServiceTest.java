package watch.nabu.application.gdpr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.domain.model.*;
import watch.nabu.infrastructure.persistence.*;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GdprExportServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    OrganizationMemberRepository orgMemberRepository;
    @Mock
    WebsiteRepository websiteRepository;
    @Mock
    MonitorRepository monitorRepository;
    @Mock
    NotificationRepository notificationRepository;
    @Mock
    InternalMessageRepository internalMessageRepository;
    @Mock
    WebhookConfigRepository webhookConfigRepository;

    @InjectMocks
    GdprExportService service;

    // ── helpers ──────────────────────────────────────────────────────────────

    private static User user() {
        User u = User.builder()
                .email("user@example.com")
                .fullName("Test User")
                .build();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(u, "createdAt", Instant.now());
        return u;
    }

    private static Organization org() {
        Organization o = new Organization();
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        o.setName("Test Org");
        return o;
    }

    private static OrganizationMember membership(User user, Organization org) {
        OrganizationMember m = OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(OrganizationMember.Role.OWNER)
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
        ReflectionTestUtils.setField(w, "createdAt", Instant.now());
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

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    void exportUserData_includesAllCategories() {
        User u = user();
        Organization o = org();
        OrganizationMember mem = membership(u, o);
        Website w = website(o);
        Monitor mon = monitor(w);

        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        when(orgMemberRepository.findAllByUserIdWithOrganization(u.getId()))
                .thenReturn(List.of(mem));
        when(websiteRepository.findAllByOrganizationId(o.getId()))
                .thenReturn(List.of(w));
        when(monitorRepository.findByWebsiteIdIn(List.of(w.getId())))
                .thenReturn(List.of(mon));
        when(webhookConfigRepository.findByOrganizationId(o.getId()))
                .thenReturn(List.of());
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(u.getId()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(internalMessageRepository.findBySenderIdOrderByCreatedAtDesc(eq(u.getEmail()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(internalMessageRepository.findByReceiverIdOrderByCreatedAtDesc(eq(u.getEmail()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Map<String, Object> export = service.exportUserData(u.getId(), u.getEmail());

        assertThat(export).containsKeys("profile", "organizations", "notifications",
                "messagesSent", "messagesReceived", "exportedAt");

        // Profile does not contain secrets
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) export.get("profile");
        assertThat(profile).doesNotContainKeys("passwordHash", "totpSecret");
        assertThat(profile).containsEntry("email", "user@example.com");

        // Organizations contain website data
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orgs = (List<Map<String, Object>>) export.get("organizations");
        assertThat(orgs).hasSize(1);
        assertThat(orgs.get(0)).containsEntry("organizationName", "Test Org");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> websites = (List<Map<String, Object>>) orgs.get(0).get("websites");
        assertThat(websites).hasSize(1);
        assertThat(websites.get(0)).containsEntry("url", "https://example.com");
    }

    @Test
    void exportUserData_userNotFound_throws() {
        UUID unknownId = UUID.randomUUID();
        when(userRepository.findById(unknownId)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(NoSuchElementException.class,
                () -> service.exportUserData(unknownId, "x@x.com"));
    }

    @Test
    void exportUserData_noOrgs_returnsEmptyOrgList() {
        User u = user();
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        when(orgMemberRepository.findAllByUserIdWithOrganization(u.getId()))
                .thenReturn(List.of());
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(u.getId()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(internalMessageRepository.findBySenderIdOrderByCreatedAtDesc(eq(u.getEmail()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(internalMessageRepository.findByReceiverIdOrderByCreatedAtDesc(eq(u.getEmail()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Map<String, Object> export = service.exportUserData(u.getId(), u.getEmail());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orgs = (List<Map<String, Object>>) export.get("organizations");
        assertThat(orgs).isEmpty();
    }
}
