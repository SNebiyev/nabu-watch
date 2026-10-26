package watch.nabu.application.monitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.exception.UserNotFoundException;
import watch.nabu.application.exception.WebsiteNotFoundException;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.User.AccountStatus;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.persistence.UserRepository;
import watch.nabu.infrastructure.persistence.WebsiteRepository;
import watch.nabu.infrastructure.redis.TaskProducer;
import watch.nabu.infrastructure.web.dto.SyncMonitorsResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitorSyncServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    WebsiteRepository websiteRepository;
    @Mock
    MonitorRepository monitorRepository;
    @Mock
    TaskProducer taskProducer;

    @InjectMocks
    MonitorSyncService monitorSyncService;

    @Mock
    SecurityContext securityContext;
    @Mock
    Authentication authentication;

    private User testUser;
    private Organization testOrg;
    private Website testWebsite;

    @BeforeEach
    void setUp() {
        testOrg = Organization.builder().name("Test Org").build();
        ReflectionTestUtils.setField(testOrg, "id", UUID.randomUUID());

        testUser = User.builder()
                .fullName("Test User")
                .email("test@example.com")
                .passwordHash("hash")
                .status(AccountStatus.ACTIVE)
                .currentOrganizationId(testOrg.getId())
                .build();
        ReflectionTestUtils.setField(testUser, "id", UUID.randomUUID());

        testWebsite = Website.builder()
                .organization(testOrg)
                .addedBy(testUser)
                .url("https://example.de")
                .regionCode("DE")
                .build();
        ReflectionTestUtils.setField(testWebsite, "id", UUID.randomUUID());

        SecurityContextHolder.setContext(securityContext);
        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        lenient().when(authentication.getName()).thenReturn(testUser.getEmail());
        lenient().when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        lenient().when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));


        // monitor.save returns the monitor with a fresh UUID
        lenient().when(monitorRepository.save(any(Monitor.class))).thenAnswer(inv -> {
            Monitor m = inv.getArgument(0);
            if (m.getId() == null) ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
            return m;
        });
    }

    // ── New monitor creation ──────────────────────────────────────────────────

    @Test
    @DisplayName("Sync with new sub-type → creates monitor and enqueues task")
    void sync_newSubType_createsAndEnqueues() {
        when(monitorRepository.findByWebsiteId(testWebsite.getId())).thenReturn(List.of());

        SyncMonitorsResult result = monitorSyncService.sync(
                testWebsite.getId(), List.of("PRIVACY_POLICY"));

        assertThat(result.activated()).containsExactly("PRIVACY_POLICY");
        assertThat(result.deactivated()).isEmpty();
        assertThat(result.unchanged()).isEmpty();
        verify(monitorRepository).save(any(Monitor.class));
        verify(taskProducer).enqueue(any(Monitor.class));
    }

    // ── Reactivate inactive monitor ───────────────────────────────────────────

    @Test
    @DisplayName("Sync with previously inactive sub-type → reactivates and enqueues")
    void sync_inactiveSubType_reactivatesAndEnqueues() {
        Monitor inactive = buildComplianceMonitor(SubType.PRIVACY_POLICY, false);
        when(monitorRepository.findByWebsiteId(testWebsite.getId())).thenReturn(List.of(inactive));

        SyncMonitorsResult result = monitorSyncService.sync(
                testWebsite.getId(), List.of("PRIVACY_POLICY"));

        assertThat(result.activated()).containsExactly("PRIVACY_POLICY");
        assertThat(inactive.isActive()).isTrue();
        verify(monitorRepository).save(inactive);
        verify(taskProducer).enqueue(inactive);
    }

    // ── Unchanged (already active) ────────────────────────────────────────────

    @Test
    @DisplayName("Sync with already-active sub-type → unchanged, just re-enqueues")
    void sync_alreadyActiveSubType_unchanged_reEnqueues() {
        Monitor active = buildComplianceMonitor(SubType.IMPRESSUM, true);
        when(monitorRepository.findByWebsiteId(testWebsite.getId())).thenReturn(List.of(active));

        SyncMonitorsResult result = monitorSyncService.sync(
                testWebsite.getId(), List.of("IMPRESSUM"));

        assertThat(result.unchanged()).containsExactly("IMPRESSUM");
        assertThat(result.activated()).isEmpty();
        verify(taskProducer).enqueue(active);
    }

    // ── Deactivate removed monitors ───────────────────────────────────────────

    @Test
    @DisplayName("Sync without a previously active sub-type → deactivates it")
    void sync_removedSubType_deactivates() {
        Monitor active = buildComplianceMonitor(SubType.IMPRESSUM, true);
        when(monitorRepository.findByWebsiteId(testWebsite.getId())).thenReturn(List.of(active));

        SyncMonitorsResult result = monitorSyncService.sync(
                testWebsite.getId(), List.of("PRIVACY_POLICY")); // IMPRESSUM not in new list

        assertThat(result.deactivated()).containsExactly("IMPRESSUM");
        assertThat(active.isActive()).isFalse();
        verify(monitorRepository, atLeastOnce()).save(active);
    }

    // ── SSL sub-type is excluded ──────────────────────────────────────────────

    @Test
    @DisplayName("SSL sub-type in list is silently excluded (SSL monitor never managed here)")
    void sync_sslSubTypeInList_isExcluded() {
        when(monitorRepository.findByWebsiteId(testWebsite.getId())).thenReturn(List.of());

        SyncMonitorsResult result = monitorSyncService.sync(
                testWebsite.getId(), List.of("SSL"));

        assertThat(result.activated()).isEmpty();
        verifyNoInteractions(taskProducer);
    }

    // ── Unknown sub-type string is silently skipped ───────────────────────────

    @Test
    @DisplayName("Unknown sub-type string → silently filtered, no exception")
    void sync_unknownSubTypeString_isFilteredSilently() {
        when(monitorRepository.findByWebsiteId(testWebsite.getId())).thenReturn(List.of());

        SyncMonitorsResult result = monitorSyncService.sync(
                testWebsite.getId(), List.of("UNKNOWN_TYPE"));

        assertThat(result.activated()).isEmpty();
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown user email → UserNotFoundException")
    void sync_unknownUser_throwsUserNotFoundException() {
        when(authentication.getName()).thenReturn("ghost@example.com");
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                monitorSyncService.sync(testWebsite.getId(), List.of("IMPRESSUM"))
        ).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("Unknown websiteId → WebsiteNotFoundException")
    void sync_unknownWebsite_throwsWebsiteNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(websiteRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                monitorSyncService.sync(unknownId, List.of("IMPRESSUM"))
        ).isInstanceOf(WebsiteNotFoundException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Monitor buildComplianceMonitor(SubType subType, boolean active) {
        Monitor m = Monitor.builder()
                .website(testWebsite)
                .type(MonitorType.COMPLIANCE)
                .subType(subType)
                .intervalMinutes(1440)
                .active(active)
                .build();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        return m;
    }
}
