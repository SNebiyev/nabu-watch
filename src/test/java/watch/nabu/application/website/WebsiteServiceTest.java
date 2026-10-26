package watch.nabu.application.website;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.compliance.ComplianceTaskBuilderService;
import watch.nabu.application.verification.DomainVerificationService;
import watch.nabu.application.exception.OtpExpiredException;
import watch.nabu.application.exception.UserNotFoundException;
import watch.nabu.application.exception.WebsiteNotFoundException;
import watch.nabu.application.monitor.MonitorIntervalConfigService;
import watch.nabu.application.ratelimit.RateLimitPort;
import watch.nabu.application.scan.ScanBatchAggregatorPort;
import watch.nabu.application.scheduler.MonitorDispatchService;
import watch.nabu.infrastructure.config.ScanLimitsProperties;
import watch.nabu.domain.model.*;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.User.AccountStatus;
import watch.nabu.domain.model.Website.WebsiteStatus;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.persistence.OrganizationRepository;
import watch.nabu.infrastructure.persistence.UserRepository;
import watch.nabu.infrastructure.persistence.WebsiteRepository;
import watch.nabu.infrastructure.redis.DeleteOtpService;
import watch.nabu.infrastructure.redis.TaskProducer;
import watch.nabu.infrastructure.web.dto.WebsiteResponse;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebsiteServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    WebsiteRepository websiteRepository;
    @Mock
    MonitorRepository monitorRepository;
    @Mock
    ComplianceTaskBuilderService complianceTaskBuilder;
    @Mock
    TaskProducer taskProducer;
    @Mock
    RateLimitPort rateLimitPort;
    @Mock
    DeleteOtpService deleteOtpService;
    @Mock
    ScanBatchAggregatorPort scanBatchAggregator;
    @Mock
    ScanLimitsProperties scanLimits;
    @Mock
    MonitorIntervalConfigService intervalConfigService;
    @Mock
    OrganizationRepository organizationRepository;
    @Mock
    MonitorDispatchService monitorDispatchService;
    @Mock
    DomainVerificationService domainVerificationService;
    @Mock
    watch.nabu.application.verification.UserVerificationService userVerificationService;
    @Mock
    watch.nabu.application.organization.OrganizationService organizationService;
    @Mock
    watch.nabu.infrastructure.persistence.AbuseReportRepository abuseReportRepository;

    @InjectMocks WebsiteService websiteService;
    @Mock SecurityContext  securityContext;
    @Mock Authentication   authentication;

    private User testUser;
    private Organization testOrg;
    private Website testWebsite;

    private void setId(Object entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }

    @BeforeEach
    void setUp() {
        testOrg = Organization.builder().name("Test Org").build();
        setId(testOrg, UUID.randomUUID());

        testUser = User.builder()
                .fullName("Test User")
                .email("test@example.com")
                .passwordHash("$2a$10$hash")
                .status(AccountStatus.ACTIVE)
                .currentOrganizationId(testOrg.getId())
                .build();
        setId(testUser, UUID.randomUUID());

        testWebsite = Website.builder()
                .organization(testOrg)
                .addedBy(testUser)
                .url("https://example.de")
                .regionCode("DE")
                .build();
        setId(testWebsite, UUID.randomUUID());

        SecurityContextHolder.setContext(securityContext);
        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        lenient().when(authentication.getName()).thenReturn(testUser.getEmail());

        lenient().when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        lenient().when(organizationRepository.getReferenceById(testOrg.getId())).thenReturn(testOrg);
        lenient().when(websiteRepository.save(any(Website.class))).thenReturn(testWebsite);
        lenient().when(intervalConfigService.resolveInterval(any(SubType.class)))
                .thenReturn(1440);
        lenient().when(scanLimits.pendingVerificationLimit()).thenReturn(5);
        lenient().when(scanLimits.pendingVerificationWindowDays()).thenReturn(30);
        lenient().when(scanLimits.manualRescanCooldownMinutes()).thenReturn(30);

        // Each monitor.save() returns the monitor with a fresh UUID
        lenient().when(monitorRepository.save(any(Monitor.class))).thenAnswer(inv -> {
            Monitor m = inv.getArgument(0);
            setId(m, UUID.randomUUID());
            return m;
        });
    }

    // ── DE region: 1 SSL + 4 COMPLIANCE monitors ─────────────────────────────

    @Test
    @DisplayName("DE website creates 1 SSL + 4 compliance monitors")
    void deWebsite_creates1SslAnd4ComplianceMonitors() {
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE"))).thenReturn(Set.of(
                SubType.IMPRESSUM, SubType.PRIVACY_POLICY, SubType.COOKIE_POLICY, SubType.GDPR_REQUEST
        ));

        CreateWebsiteCommand cmd = new CreateWebsiteCommand(
            "https://example.de", "DE", null, null, null
        );

        WebsiteResponse response = websiteService.create(cmd);

        // Total monitors saved = 1 SSL + 4 COMPLIANCE
        verify(monitorRepository, times(5)).save(any(Monitor.class));
        // All monitors enqueued as a batch with scout-first ordering
        verify(monitorDispatchService).dispatchBatch(anyList(), any(UUID.class));

        assertThat(response.monitorsByStatus()).containsKey("PENDING");
        assertThat(response.monitorsByStatus().get("PENDING")).hasSize(5);
    }

    @Test
    @DisplayName("DE website SSL monitor has subType SSL and fixed 1440-min interval")
    void deWebsite_createsSslMonitorWithSubTypeSsl() {
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE"))).thenReturn(Set.of(SubType.IMPRESSUM));

        websiteService.create(new CreateWebsiteCommand(
            "https://example.de", "DE", null, null, null
        ));

        ArgumentCaptor<Monitor> captor = ArgumentCaptor.forClass(Monitor.class);
        verify(monitorRepository, times(2)).save(captor.capture());

        Monitor sslMonitor = captor.getAllValues().stream()
            .filter(m -> m.getType() == MonitorType.SSL)
            .findFirst()
            .orElseThrow();

        assertThat(sslMonitor.getType()).isEqualTo(MonitorType.SSL);
        assertThat(sslMonitor.getSubType()).isEqualTo(SubType.SSL);
        assertThat(sslMonitor.getIntervalMinutes()).isEqualTo(1440); // fixed 24-h cadence
    }

    @Test
    @DisplayName("DE website creates one compliance monitor per region sub-type with daily interval")
    void deWebsite_createsComplianceMonitorPerSubType() {
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE"))).thenReturn(Set.of(
                SubType.IMPRESSUM, SubType.PRIVACY_POLICY
        ));

        websiteService.create(new CreateWebsiteCommand(
            "https://example.de", "DE", null, null, null
        ));

        ArgumentCaptor<Monitor> captor = ArgumentCaptor.forClass(Monitor.class);
        verify(monitorRepository, times(3)).save(captor.capture());

        List<Monitor> complianceMonitors = captor.getAllValues().stream()
            .filter(m -> m.getType() == MonitorType.COMPLIANCE)
            .toList();

        assertThat(complianceMonitors).hasSize(2);
        assertThat(complianceMonitors)
            .extracting(Monitor::getSubType)
            .containsExactlyInAnyOrder(SubType.IMPRESSUM, SubType.PRIVACY_POLICY);
        assertThat(complianceMonitors)
            .allMatch(m -> m.getIntervalMinutes() == 1440); // daily
    }

    // ── Initial batch registration ────────────────────────────────────────────

    @Test
    @DisplayName("create: registers a SCHEDULED batch before enqueueing monitors")
    void create_registersInitialBatchBeforeEnqueue() {
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE"))).thenReturn(Set.of(
                SubType.IMPRESSUM, SubType.PRIVACY_POLICY
        ));

        websiteService.create(new CreateWebsiteCommand("https://example.de", "DE", null, null, null));

        // 1 SSL + 2 compliance = 3 monitors → batch registered with size 3
        verify(scanBatchAggregator).registerBatch(
                any(UUID.class), eq(3),
                eq(testWebsite.getId()), eq(testWebsite.getUrl()),
                eq(testOrg.getId()), eq("SCHEDULED"));
    }

    @Test
    @DisplayName("create: all monitors share the same batchId passed to taskProducer")
    void create_allMonitorsEnqueuedWithSharedBatchId() {
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE"))).thenReturn(Set.of(
                SubType.IMPRESSUM, SubType.PRIVACY_POLICY
        ));

        websiteService.create(new CreateWebsiteCommand("https://example.de", "DE", null, null, null));

        ArgumentCaptor<UUID> batchIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(monitorDispatchService).dispatchBatch(anyList(), batchIdCaptor.capture());

        // Single batch call with one shared batchId
        assertThat(batchIdCaptor.getValue()).isNotNull();
    }

    // ── Unknown region: only SSL monitor ──────────────────────────────────────

    @Test
    @DisplayName("Unknown region creates only 1 SSL monitor (no compliance)")
    void unknownRegion_createsOnlySslMonitor() {
        when(complianceTaskBuilder.getSubTypesForRegion(eq("XX"))).thenReturn(Set.of());

        websiteService.create(new CreateWebsiteCommand(
            "https://example.xx", "XX", null, null, null
        ));

        verify(monitorRepository, times(1)).save(any(Monitor.class));
        verify(monitorDispatchService).dispatchBatch(anyList(), any(UUID.class));
    }

    // ── targetUrl normalization ────────────────────────────────────────────────

    @Test
    @DisplayName("Blank SSL targetUrl is normalized to null on the monitor")
    void blankSslTargetUrl_isNormalizedToNull() {
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE"))).thenReturn(Set.of());

        websiteService.create(new CreateWebsiteCommand(
            "https://example.de", "DE", "   ", null, null
        ));

        ArgumentCaptor<Monitor> captor = ArgumentCaptor.forClass(Monitor.class);
        verify(monitorRepository).save(captor.capture());
        assertThat(captor.getValue().getTargetUrl()).isNull();
    }

    @Test
    @DisplayName("Non-blank SSL targetUrl is stripped and preserved on monitor")
    void nonBlankSslTargetUrl_isPreservedOnMonitor() {
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE"))).thenReturn(Set.of());

        websiteService.create(new CreateWebsiteCommand(
            "https://example.de", "DE", "  https://sub.example.de  ", null, null
        ));

        ArgumentCaptor<Monitor> captor = ArgumentCaptor.forClass(Monitor.class);
        verify(monitorRepository).save(captor.capture());
        assertThat(captor.getValue().getTargetUrl()).isEqualTo("https://sub.example.de");
    }

    // ── selectedSubTypes override ─────────────────────────────────────────────

    @Test
    @DisplayName("Explicit selectedSubTypes overrides regional defaults")
    void selectedSubTypes_overridesRegionalDefaults() {
        CreateWebsiteCommand cmd = new CreateWebsiteCommand(
                "https://example.de", "DE", null, null,
                List.of(SubType.PRIVACY_POLICY, SubType.IMPRESSUM)
        );

        websiteService.create(cmd);

        ArgumentCaptor<Monitor> captor = ArgumentCaptor.forClass(Monitor.class);
        // 1 SSL + 2 compliance
        verify(monitorRepository, times(3)).save(captor.capture());
        List<Monitor> compliance = captor.getAllValues().stream()
                .filter(m -> m.getType() == MonitorType.COMPLIANCE).toList();
        assertThat(compliance).extracting(Monitor::getSubType)
                .containsExactlyInAnyOrder(SubType.PRIVACY_POLICY, SubType.IMPRESSUM);
        // ComplianceTaskBuilderService should NOT be consulted when selectedSubTypes is explicit
        verifyNoInteractions(complianceTaskBuilder);
    }

    // ── User not found ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown user email → UserNotFoundException, no monitors created")
    void unknownUserId_throwsUserNotFoundException() {
        String unknownEmail = "unknown@example.com";
        when(authentication.getName()).thenReturn(unknownEmail);
        when(userRepository.findByEmail(unknownEmail)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            websiteService.create(new CreateWebsiteCommand(
                "https://example.de", "DE", null, null, null
            ))
        ).isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(monitorRepository, taskProducer);
    }

    // ── updateStatus ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateStatus PAUSED → website status set to PAUSED")
    void updateStatus_paused_updatesWebsite() {
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        websiteService.updateStatus(testWebsite.getId(), WebsiteStatus.PAUSED);

        assertThat(testWebsite.getStatus()).isEqualTo(WebsiteStatus.PAUSED);
    }

    @Test
    @DisplayName("updateStatus ACTIVE → website status set to ACTIVE")
    void updateStatus_active_updatesWebsite() {
        testWebsite.setStatus(WebsiteStatus.PAUSED);
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        websiteService.updateStatus(testWebsite.getId(), WebsiteStatus.ACTIVE);

        assertThat(testWebsite.getStatus()).isEqualTo(WebsiteStatus.ACTIVE);
    }

    @Test
    @DisplayName("updateStatus unknown websiteId → WebsiteNotFoundException")
    void updateStatus_unknownWebsite_throwsWebsiteNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(websiteRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                websiteService.updateStatus(unknownId, WebsiteStatus.PAUSED)
        ).isInstanceOf(WebsiteNotFoundException.class);
    }

    // ── rescan ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rescan without force → rate-limit and cooldown checked, active monitors enqueued")
    void rescan_withoutForce_checksRateLimitAndEnqueuesMonitors() {
        UUID callerId = UUID.randomUUID();
        Monitor m1 = buildMonitor(testWebsite, MonitorType.SSL, SubType.SSL);
        Monitor m2 = buildMonitor(testWebsite, MonitorType.COMPLIANCE, SubType.IMPRESSUM);
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteIdAndActiveTrue(testWebsite.getId()))
                .thenReturn(List.of(m1, m2));

        websiteService.rescan(testWebsite.getId(), callerId, false);

        verify(rateLimitPort).checkUserRateLimit(callerId);
        verify(monitorDispatchService).dispatchBatch(anyList(), any(UUID.class));
    }

    @Test
    @DisplayName("rescan with force=true → rate-limit and cooldown bypassed")
    void rescan_withForce_bypassesRateLimitAndCooldown() {
        UUID callerId = UUID.randomUUID();
        Monitor m1 = buildMonitor(testWebsite, MonitorType.SSL, SubType.SSL);
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteIdAndActiveTrue(testWebsite.getId()))
                .thenReturn(List.of(m1));

        websiteService.rescan(testWebsite.getId(), callerId, true);

        verifyNoInteractions(rateLimitPort);
        verify(monitorDispatchService).dispatchBatch(anyList(), any(UUID.class));
    }

    @Test
    @DisplayName("rescan unknown websiteId → WebsiteNotFoundException")
    void rescan_unknownWebsite_throwsWebsiteNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(websiteRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                websiteService.rescan(unknownId, UUID.randomUUID(), false)
        ).isInstanceOf(WebsiteNotFoundException.class);
    }

    // ── syncAllMissingMonitors ─────────────────────────────────────────────────

    @Test
    @DisplayName("syncAllMissingMonitors: creates missing COOKIE_BANNER for each affected website")
    void syncAllMissingMonitors_createsMissingMonitorsForAllWebsites() {
        Monitor ssl = buildMonitor(testWebsite, MonitorType.SSL, SubType.SSL);
        Monitor privacy = buildMonitor(testWebsite, MonitorType.COMPLIANCE, SubType.PRIVACY_POLICY);

        when(websiteRepository.findAll()).thenReturn(List.of(testWebsite));
        when(monitorRepository.findByWebsiteIdIn(any()))
                .thenReturn(List.of(ssl, privacy));
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE")))
                .thenReturn(Set.of(SubType.PRIVACY_POLICY, SubType.COOKIE_BANNER));

        WebsiteService.MonitorSyncResult result =
                websiteService.syncAllMissingMonitors(null, null, false);

        assertThat(result.websitesScanned()).isEqualTo(1);
        assertThat(result.monitorsCreated()).isEqualTo(1);
        assertThat(result.dryRun()).isFalse();
        assertThat(result.createdBySubType()).containsKey("COOKIE_BANNER");

        ArgumentCaptor<Monitor> saveCaptor = ArgumentCaptor.forClass(Monitor.class);
        verify(monitorRepository, atLeastOnce()).save(saveCaptor.capture());
        assertThat(saveCaptor.getAllValues())
                .extracting(Monitor::getSubType)
                .contains(SubType.COOKIE_BANNER);
    }

    @Test
    @DisplayName("syncAllMissingMonitors dryRun=true: counts but does not persist")
    void syncAllMissingMonitors_dryRun_doesNotPersist() {
        Monitor ssl = buildMonitor(testWebsite, MonitorType.SSL, SubType.SSL);

        when(websiteRepository.findAll()).thenReturn(List.of(testWebsite));
        when(monitorRepository.findByWebsiteIdIn(any()))
                .thenReturn(List.of(ssl));
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE")))
                .thenReturn(Set.of(SubType.COOKIE_BANNER));

        WebsiteService.MonitorSyncResult result =
                websiteService.syncAllMissingMonitors(null, null, true);

        assertThat(result.monitorsCreated()).isEqualTo(1);
        assertThat(result.dryRun()).isTrue();
        // No save should have happened
        verify(monitorRepository, never()).save(any(Monitor.class));
    }

    @Test
    @DisplayName("syncAllMissingMonitors with subType filter: only creates the specified type")
    void syncAllMissingMonitors_withSubTypeFilter_onlyCreatesFilteredType() {
        Monitor ssl = buildMonitor(testWebsite, MonitorType.SSL, SubType.SSL);

        when(websiteRepository.findAll()).thenReturn(List.of(testWebsite));
        when(monitorRepository.findByWebsiteIdIn(any()))
                .thenReturn(List.of(ssl));
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE")))
                .thenReturn(Set.of(SubType.PRIVACY_POLICY, SubType.IMPRESSUM, SubType.COOKIE_BANNER));

        WebsiteService.MonitorSyncResult result =
                websiteService.syncAllMissingMonitors(null, List.of("COOKIE_BANNER"), false);

        // Only COOKIE_BANNER should be created, not PRIVACY_POLICY or IMPRESSUM
        assertThat(result.monitorsCreated()).isEqualTo(1);
        assertThat(result.createdBySubType()).containsOnlyKeys("COOKIE_BANNER");
    }

    @Test
    @DisplayName("syncAllMissingMonitors with regionCode filter: skips websites in other regions")
    void syncAllMissingMonitors_withRegionFilter_skipsOtherRegions() {
        Website frWebsite = Website.builder().organization(testOrg).addedBy(testUser).url("https://example.fr").regionCode("FR").build();
        setId(frWebsite, UUID.randomUUID());

        when(websiteRepository.findAll()).thenReturn(List.of(testWebsite, frWebsite)); // DE + FR
        when(monitorRepository.findByWebsiteIdIn(any())).thenReturn(List.of());
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE")))
                .thenReturn(Set.of(SubType.COOKIE_BANNER));

        WebsiteService.MonitorSyncResult result =
                websiteService.syncAllMissingMonitors(List.of("DE"), null, false);

        // Only 1 website scanned (DE), FR skipped
        assertThat(result.websitesScanned()).isEqualTo(1);
        assertThat(result.monitorsCreated()).isEqualTo(1);
        // FR website is filtered out before the batch fetch, so it never appears in the id list.
        org.mockito.ArgumentCaptor<java.util.Collection<UUID>> ids =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(monitorRepository).findByWebsiteIdIn(ids.capture());
        assertThat(ids.getValue()).doesNotContain(frWebsite.getId());
    }

    // ── requestDeleteOtp ───────────────────────────────────────────────────────

    @Test
    @DisplayName("requestDeleteOtp on another user's site → generates OTP")
    void requestDeleteOtp_otherOwnedSite_generatesOtp() {
        UUID adminId = UUID.randomUUID(); // different from website owner
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        websiteService.requestDeleteOtp(testWebsite.getId(), adminId, "admin@example.com");

        verify(deleteOtpService).generate(testWebsite.getId(), adminId, "admin@example.com");
    }

    @Test
    @DisplayName("requestDeleteOtp on own website → throws IllegalArgumentException")
    void requestDeleteOtp_selfOwnedSite_throwsIllegalArgumentException() {
        // testWebsite is owned by testUser; pass testUser's id as adminId
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        assertThatThrownBy(() ->
                websiteService.requestDeleteOtp(testWebsite.getId(), testUser.getId(), testUser.getEmail())
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self-owned");
    }

    @Test
    @DisplayName("requestDeleteOtp unknown websiteId → WebsiteNotFoundException")
    void requestDeleteOtp_unknownWebsite_throwsWebsiteNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(websiteRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                websiteService.requestDeleteOtp(unknownId, UUID.randomUUID(), "admin@example.com")
        ).isInstanceOf(WebsiteNotFoundException.class);
    }

    // ── delete ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete by website owner → hard-deletes website")
    void delete_bySelfOwner_hardDeletesWebsite() {
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        websiteService.delete(testWebsite.getId(), testUser.getId(), User.Role.USER, null);

        verify(websiteRepository).delete(testWebsite);
        verifyNoInteractions(deleteOtpService);
    }

    @Test
    @DisplayName("delete by admin on another user's site with valid OTP → hard-deletes")
    void delete_byAdminWithValidOtp_hardDeletes() {
        UUID adminId = UUID.randomUUID();
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        websiteService.delete(testWebsite.getId(), adminId, User.Role.ADMIN, "123456");

        verify(deleteOtpService).verify(testWebsite.getId(), adminId, "123456");
        verify(websiteRepository).delete(testWebsite);
    }

    @Test
    @DisplayName("delete: owner self-delete blocked when an open abuse report exists")
    void delete_bySelfOwner_blockedByOpenAbuseReport() {
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));
        when(abuseReportRepository.existsOpenByWebsiteId(testWebsite.getId())).thenReturn(true);

        assertThatThrownBy(() ->
                websiteService.delete(testWebsite.getId(), testUser.getId(), User.Role.USER, null)
        ).isInstanceOf(WebsiteHasOpenAbuseReportException.class);

        verify(websiteRepository, never()).delete(any(Website.class));
    }

    @Test
    @DisplayName("delete: admin OTP step-up bypasses the open-abuse-report gate")
    void delete_byAdminWithOtp_bypassesOpenAbuseReportGate() {
        UUID adminId = UUID.randomUUID();
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        websiteService.delete(testWebsite.getId(), adminId, User.Role.ADMIN, "123456");

        verify(abuseReportRepository, never()).existsOpenByWebsiteId(any());
        verify(websiteRepository).delete(testWebsite);
    }

    @Test
    @DisplayName("delete by non-admin on another user's site → AccessDeniedException")
    void delete_byNonAdminCrossUser_throwsAccessDeniedException() {
        UUID otherUserId = UUID.randomUUID();
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        assertThatThrownBy(() ->
                websiteService.delete(testWebsite.getId(), otherUserId, User.Role.USER, null)
        ).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(deleteOtpService);
    }

    @Test
    @DisplayName("delete by admin with wrong OTP → OtpExpiredException, website not deleted")
    void delete_byAdminWithWrongOtp_throwsOtpExpiredException() {
        UUID adminId = UUID.randomUUID();
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));
        doThrow(new OtpExpiredException()).when(deleteOtpService).verify(any(), any(), any());

        assertThatThrownBy(() ->
                websiteService.delete(testWebsite.getId(), adminId, User.Role.ADMIN, "000000")
        ).isInstanceOf(OtpExpiredException.class);

        verify(websiteRepository, never()).delete(any(Website.class));
    }

    @Test
    @DisplayName("delete unknown websiteId → WebsiteNotFoundException")
    void delete_unknownWebsite_throwsWebsiteNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(websiteRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                websiteService.delete(unknownId, testUser.getId(), User.Role.USER, null)
        ).isInstanceOf(WebsiteNotFoundException.class);
    }

    // ── create: duplicate submission ────────────────────────────────────────

    @Test
    @DisplayName("create: duplicate URL within same org throws WebsiteDuplicateException")
    void create_duplicateUrl_throwsDuplicateException() {
        when(websiteRepository.findByOrganizationIdAndUrl(any(), eq("https://example.de")))
                .thenReturn(Optional.of(testWebsite));

        assertThatThrownBy(() -> websiteService.create(new CreateWebsiteCommand(
                "https://example.de", "DE", null, null, null
        ))).isInstanceOf(WebsiteDuplicateException.class)
                .hasMessageContaining("https://example.de");

        // Should NOT attempt to create or sync anything
        verify(websiteRepository, never()).save(any());
        verify(monitorRepository, never()).saveAll(any());
    }

    // ── updateLanguagePreference ───────────────────────────────────────────

    @Test
    @DisplayName("updateLanguagePreference: updates setting and saves")
    void updateLanguagePreference_updatesAndSaves() {
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        websiteService.updateLanguagePreference(testWebsite.getId(), "ENGLISH_ONLY");

        assertThat(testWebsite.getAcceptedLanguages()).isEqualTo(Website.AcceptedLanguages.ENGLISH_ONLY);
        verify(websiteRepository).save(testWebsite);
    }

    @Test
    @DisplayName("updateLanguagePreference: invalid value throws IllegalArgumentException")
    void updateLanguagePreference_invalidValue_throws() {
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        assertThatThrownBy(() -> websiteService.updateLanguagePreference(testWebsite.getId(), "INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("updateLanguagePreference: unknown website throws WebsiteNotFoundException")
    void updateLanguagePreference_unknownWebsite_throws() {
        UUID unknownId = UUID.randomUUID();
        when(websiteRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> websiteService.updateLanguagePreference(unknownId, "NATIVE_ONLY"))
                .isInstanceOf(WebsiteNotFoundException.class);
    }

    // ── rescan: lastManualScanAt is updated ──────────────────────────────────

    @Test
    @DisplayName("rescan: stamps lastManualScanAt for non-force rescans")
    void rescan_updatesLastManualScanAt() {
        UUID callerId = UUID.randomUUID();
        Monitor m1 = buildMonitor(testWebsite, MonitorType.SSL, SubType.SSL);
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteIdAndActiveTrue(testWebsite.getId()))
                .thenReturn(List.of(m1));

        websiteService.rescan(testWebsite.getId(), callerId, false);

        assertThat(testWebsite.getLastManualScanAt()).isNotNull();
        verify(websiteRepository).save(testWebsite);
    }

    @Test
    @DisplayName("rescan with force=true: does not stamp lastManualScanAt")
    void rescan_force_doesNotStampLastManualScanAt() {
        UUID callerId = UUID.randomUUID();
        Monitor m1 = buildMonitor(testWebsite, MonitorType.SSL, SubType.SSL);
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteIdAndActiveTrue(testWebsite.getId()))
                .thenReturn(List.of(m1));

        websiteService.rescan(testWebsite.getId(), callerId, true);

        verify(websiteRepository, never()).save(testWebsite);
    }

    @Test
    @DisplayName("rescan with no active monitors: logs but does not register batch")
    void rescan_noActiveMonitors() {
        UUID callerId = UUID.randomUUID();
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteIdAndActiveTrue(testWebsite.getId()))
                .thenReturn(List.of());

        websiteService.rescan(testWebsite.getId(), callerId, true);

        verify(scanBatchAggregator, never()).registerBatch(any(), anyInt(), any(), any(), any(), any());
    }

    // ── create: firstLogin flag cleared ───────────────────────────────────────

    @Test
    @DisplayName("create: clears firstLogin flag for new users")
    void create_clearsFirstLoginFlag() {
        testUser.setFirstLogin(true);
        when(complianceTaskBuilder.getSubTypesForRegion(any())).thenReturn(Set.of());

        websiteService.create(new CreateWebsiteCommand(
                "https://example.de", "DE", null, null, null));

        assertThat(testUser.isFirstLogin()).isFalse();
        verify(userRepository).save(testUser);
    }

    // ── create: with compliance target URL ───────────────────────────────────

    @Test
    @DisplayName("create: custom compliance target URL is used when provided")
    void create_complianceTargetUrl() {
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE")))
                .thenReturn(Set.of(SubType.PRIVACY_POLICY));

        websiteService.create(new CreateWebsiteCommand(
                "https://example.de", "DE", null, "https://example.de/privacy", null));

        ArgumentCaptor<Monitor> captor = ArgumentCaptor.forClass(Monitor.class);
        verify(monitorRepository, times(2)).save(captor.capture());

        Monitor compliance = captor.getAllValues().stream()
                .filter(m -> m.getType() == MonitorType.COMPLIANCE)
                .findFirst().orElseThrow();
        assertThat(compliance.getTargetUrl()).isEqualTo("https://example.de/privacy");
    }

    @Test
    @DisplayName("create: blocks the 6th unverified site inside the window (429 PENDING_VERIFICATION_LIMIT)")
    void create_pendingVerificationLimit_blocksAnyUser() {
        when(websiteRepository.countPendingVerificationSince(any(), any())).thenReturn(5L);

        assertThatThrownBy(() -> websiteService.create(new CreateWebsiteCommand(
                "https://sixth.example", "DE", null, null, null)))
                .isInstanceOf(watch.nabu.application.exception.PendingVerificationLimitException.class)
                .hasMessageContaining("5 unverified websites in 30 days");

        // Site must not be persisted when the gate rejects
        verify(websiteRepository, never()).save(any(Website.class));
    }

    @Test
    @DisplayName("create: pending-verification limit of 0 disables the check")
    void create_pendingVerificationLimitZero_disablesCheck() {
        when(scanLimits.pendingVerificationLimit()).thenReturn(0);
        when(complianceTaskBuilder.getSubTypesForRegion(eq("DE")))
                .thenReturn(Set.of(SubType.PRIVACY_POLICY));

        websiteService.create(new CreateWebsiteCommand(
                "https://unlimited.example", "DE", null, null, null));

        verify(websiteRepository, never()).countPendingVerificationSince(any(), any());
        verify(monitorRepository, atLeastOnce()).save(any(Monitor.class));
    }

    @Test
    @DisplayName("rescan within the configured manual cooldown → ManualRescanCooldownException")
    void rescan_withinConfiguredCooldown_throws() {
        testWebsite.setLastManualScanAt(java.time.Instant.now().minus(java.time.Duration.ofMinutes(5)));
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        assertThatThrownBy(() -> websiteService.rescan(testWebsite.getId(), UUID.randomUUID(), false))
                .isInstanceOf(watch.nabu.application.exception.ManualRescanCooldownException.class);

        verify(monitorDispatchService, never()).dispatchBatch(anyList(), any(UUID.class));
    }

    @Test
    @DisplayName("rescan after the configured manual cooldown → allowed")
    void rescan_afterConfiguredCooldown_dispatches() {
        testWebsite.setLastManualScanAt(java.time.Instant.now().minus(java.time.Duration.ofMinutes(31)));
        Monitor m1 = buildMonitor(testWebsite, MonitorType.SSL, SubType.SSL);
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));
        when(monitorRepository.findByWebsiteIdAndActiveTrue(testWebsite.getId())).thenReturn(List.of(m1));

        websiteService.rescan(testWebsite.getId(), UUID.randomUUID(), false);

        verify(monitorDispatchService).dispatchBatch(anyList(), any(UUID.class));
    }

    @Test
    @DisplayName("updateScreenshotsEnabled: any owner can enable, no plan or token cost involved")
    void updateScreenshotsEnabled_enablesWithoutPlan() {
        UUID callerId = UUID.randomUUID();
        when(websiteRepository.findById(testWebsite.getId())).thenReturn(Optional.of(testWebsite));

        websiteService.updateScreenshotsEnabled(testWebsite.getId(), callerId, true);

        verify(organizationService).requireOwner(testOrg.getId(), callerId);
        assertThat(testWebsite.isScreenshotsEnabled()).isTrue();
        verify(websiteRepository).save(testWebsite);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Monitor buildMonitor(Website website, MonitorType type, SubType subType) {
        Monitor m = Monitor.builder()
                .website(website).type(type).subType(subType).intervalMinutes(60).active(true).build();
        setId(m, UUID.randomUUID());
        return m;
    }
}
