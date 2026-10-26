package watch.nabu.application.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.domain.model.*;
import watch.nabu.infrastructure.persistence.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    WebsiteRepository websiteRepository;
    @Mock
    MonitorRepository monitorRepository;
    @Mock
    ScanLogRepository scanLogRepository;
    @Mock
    OrganizationRepository organizationRepository;
    @Mock
    OrganizationMemberRepository organizationMemberRepository;

    @InjectMocks
    AdminDashboardService adminDashboardService;

    // ── getStats ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getStats returns correct counts and failure rate")
    void getStats_returnsCorrectSnapshot() {
        when(userRepository.count()).thenReturn(10L);
        when(websiteRepository.count()).thenReturn(5L);
        when(monitorRepository.count()).thenReturn(20L);
        when(scanLogRepository.countByCreatedAtAfter(any(Instant.class))).thenReturn(7L);
        when(scanLogRepository.count()).thenReturn(100L);
        when(scanLogRepository.countFailedScans()).thenReturn(5L);

        AdminDashboardService.AdminStats stats = adminDashboardService.getStats();

        assertThat(stats.totalUsers()).isEqualTo(10L);
        assertThat(stats.totalWebsites()).isEqualTo(5L);
        assertThat(stats.totalMonitors()).isEqualTo(20L);
        assertThat(stats.activeScansLastHour()).isEqualTo(7L);
        assertThat(stats.systemFailureRate()).isEqualTo(5.0); // 5/100 = 5.00%
    }

    @Test
    @DisplayName("getStats with zero scans → failure rate is 0.0 (no division by zero)")
    void getStats_noScans_failureRateIsZero() {
        when(userRepository.count()).thenReturn(0L);
        when(websiteRepository.count()).thenReturn(0L);
        when(monitorRepository.count()).thenReturn(0L);
        when(scanLogRepository.countByCreatedAtAfter(any(Instant.class))).thenReturn(0L);
        when(scanLogRepository.count()).thenReturn(0L);
        when(scanLogRepository.countFailedScans()).thenReturn(0L);

        AdminDashboardService.AdminStats stats = adminDashboardService.getStats();

        assertThat(stats.systemFailureRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("getStats failure rate is rounded to 2 decimal places")
    void getStats_failureRateRoundedToTwoDecimalPlaces() {
        when(userRepository.count()).thenReturn(1L);
        when(websiteRepository.count()).thenReturn(1L);
        when(monitorRepository.count()).thenReturn(1L);
        when(scanLogRepository.countByCreatedAtAfter(any(Instant.class))).thenReturn(0L);
        when(scanLogRepository.count()).thenReturn(3L);
        when(scanLogRepository.countFailedScans()).thenReturn(1L); // 1/3 = 33.33%

        AdminDashboardService.AdminStats stats = adminDashboardService.getStats();

        assertThat(stats.systemFailureRate()).isEqualTo(33.33);
    }

    // ── listRecentStatusChanges ──────────────────────────────────────────────

    @Test
    @DisplayName("listRecentStatusChanges maps rows and computes status change")
    void listRecentStatusChanges_mapsRowsCorrectly() {
        UUID scanLogId = UUID.randomUUID();
        UUID monitorId = UUID.randomUUID();
        UUID websiteId = UUID.randomUUID();
        UUID prevScanLogId = UUID.randomUUID();
        Timestamp ts = Timestamp.from(Instant.now());
        Object[] row = {scanLogId, monitorId, websiteId, "https://example.de",
                "COMPLIANCE", "PRIVACY_POLICY", "INFO", "HIGH", ts, "Acme GmbH", prevScanLogId};
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(row);
        when(scanLogRepository.findRecentStatusChangesRaw(20)).thenReturn(rows);

        var result = adminDashboardService.listRecentStatusChanges(20);

        assertThat(result).hasSize(1);
        AdminDashboardService.StatusChangeView view = result.get(0);
        assertThat(view.scanLogId()).isEqualTo(scanLogId);
        assertThat(view.websiteUrl()).isEqualTo("https://example.de");
        assertThat(view.severity()).isEqualTo("INFO");
        assertThat(view.previousSeverity()).isEqualTo("HIGH");
        assertThat(view.statusChange()).isEqualTo("RECOVERED");
        assertThat(view.orgName()).isEqualTo("Acme GmbH");
        assertThat(view.previousScanLogId()).isEqualTo(prevScanLogId);
    }

    @Test
    @DisplayName("listRecentStatusChanges caps limit at 50")
    void listRecentStatusChanges_capsLimitAt50() {
        when(scanLogRepository.findRecentStatusChangesRaw(50)).thenReturn(List.of());

        adminDashboardService.listRecentStatusChanges(999);

        verify(scanLogRepository).findRecentStatusChangesRaw(50);
    }

    @Test
    @DisplayName("listRecentStatusChanges enforces minimum limit of 1")
    void listRecentStatusChanges_minimumLimitIs1() {
        when(scanLogRepository.findRecentStatusChangesRaw(1)).thenReturn(List.of());

        adminDashboardService.listRecentStatusChanges(0);

        verify(scanLogRepository).findRecentStatusChangesRaw(1);
    }

    @Test
    @DisplayName("listRecentStatusChanges computes REGRESSED_TO_FAILING for INFO->HIGH")
    void listRecentStatusChanges_regressedToFailing() {
        UUID scanLogId = UUID.randomUUID();
        Timestamp ts = Timestamp.from(Instant.now());
        Object[] row = {scanLogId, UUID.randomUUID(), UUID.randomUUID(), "https://test.com",
                "SSL", "SSL", "HIGH", "INFO", ts, "Test Org", UUID.randomUUID()};
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(row);
        when(scanLogRepository.findRecentStatusChangesRaw(20)).thenReturn(rows);

        var result = adminDashboardService.listRecentStatusChanges(20);

        assertThat(result.get(0).statusChange()).isEqualTo("REGRESSED_TO_FAILING");
    }

    // ── listUsers ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listUsers delegates to userRepository and returns result")
    void listUsers_delegatesToRepository() {
        UserAdminView view = new UserAdminView(
                UUID.randomUUID(), "Alice", "alice@example.com",
                User.Role.USER, User.AccountStatus.ACTIVE,
                Instant.now(), 3L, null
        );
        when(userRepository.findAllWithSiteCount()).thenReturn(List.of(view));

        List<UserAdminView> result = adminDashboardService.listUsers();

        assertThat(result).containsExactly(view);
    }

    // ── listOrganizationsPaged ────────────────────────────────────────────────

    @Test
    @DisplayName("listOrganizationsPaged returns orgs with member/website counts")
    void listOrganizationsPaged_returnsOrgsWithCounts() {
        Organization org = Organization.builder().name("Acme Corp").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(org, "createdAt", Instant.now());

        when(organizationRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(org)));
        when(organizationMemberRepository.countByOrganizationId(org.getId())).thenReturn(3L);
        when(websiteRepository.countByOrganizationId(org.getId()))
                .thenReturn(5L);

        Page<AdminDashboardService.OrgAdminView> result =
                adminDashboardService.listOrganizationsPaged(null, 0, 20);

        assertThat(result.getContent()).hasSize(1);
        AdminDashboardService.OrgAdminView view = result.getContent().get(0);
        assertThat(view.name()).isEqualTo("Acme Corp");
        assertThat(view.memberCount()).isEqualTo(3L);
        assertThat(view.websiteCount()).isEqualTo(5L);
        assertThat(view.enabled()).isTrue();
    }

    @Test
    @DisplayName("listOrganizationsPaged with search delegates to searchByName")
    void listOrganizationsPaged_withSearch_usesSearchRepo() {
        when(organizationRepository.searchByName(eq("acme"), any(Pageable.class)))
                .thenReturn(Page.empty());

        adminDashboardService.listOrganizationsPaged("acme", 0, 20);

        verify(organizationRepository).searchByName(eq("acme"), any(Pageable.class));
    }

    // ── listOrganizationMembers ───────────────────────────────────────────────

    @Test
    @DisplayName("listOrganizationMembers returns members with user details")
    void listOrganizationMembers_returnsMembersWithDetails() {
        UUID orgId = UUID.randomUUID();
        Organization org = Organization.builder().name("Test Org").build();
        ReflectionTestUtils.setField(org, "id", orgId);

        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setFullName("John Doe");
        user.setEmail("john@example.com");

        OrganizationMember member = OrganizationMember.builder()
                .organization(org).user(user).role(OrganizationMember.Role.OWNER).build();
        ReflectionTestUtils.setField(member, "joinedAt", Instant.now());

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationMemberRepository.findAllByOrganizationIdWithUser(orgId))
                .thenReturn(List.of(member));

        List<AdminDashboardService.OrgMemberAdminView> result =
                adminDashboardService.listOrganizationMembers(orgId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).fullName()).isEqualTo("John Doe");
        assertThat(result.get(0).role()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("listOrganizationMembers throws when org not found")
    void listOrganizationMembers_orgNotFound_throws() {
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminDashboardService.listOrganizationMembers(orgId))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── updateOrganizationEnabled ─────────────────────────────────────────────

    @Test
    @DisplayName("updateOrganizationEnabled disables an org")
    void updateOrganizationEnabled_disablesOrg() {
        UUID orgId = UUID.randomUUID();
        Organization org = Organization.builder().name("Test").enabled(true).build();
        ReflectionTestUtils.setField(org, "id", orgId);

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        adminDashboardService.updateOrganizationEnabled(orgId, false);

        assertThat(org.isEnabled()).isFalse();
        verify(organizationRepository).save(org);
    }

    @Test
    @DisplayName("updateOrganizationEnabled throws when org not found")
    void updateOrganizationEnabled_orgNotFound_throws() {
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminDashboardService.updateOrganizationEnabled(orgId, false))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── listUsersPaged ────────────────────────────────────────────────────────

    @Test
    @DisplayName("listUsersPaged delegates to repository with safe page size")
    void listUsersPaged_delegates() {
        when(userRepository.findAllWithSiteCountPaged(any(Pageable.class)))
                .thenReturn(Page.empty());

        Page<UserAdminView> result = adminDashboardService.listUsersPaged(0, 50);

        assertThat(result.getContent()).isEmpty();
        verify(userRepository).findAllWithSiteCountPaged(any(Pageable.class));
    }

    @Test
    @DisplayName("listUsersPaged clamps size to 100")
    void listUsersPaged_capsSize() {
        when(userRepository.findAllWithSiteCountPaged(any(Pageable.class)))
                .thenReturn(Page.empty());

        adminDashboardService.listUsersPaged(0, 999);

        verify(userRepository).findAllWithSiteCountPaged(
                argThat((Pageable p) -> p.getPageSize() == 100));
    }

    // ── getUserWebsites ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getUserWebsites returns all websites for user's org")
    void getUserWebsites_returnsAll() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setCurrentOrganizationId(orgId);

        Website active = Website.builder().url("https://a.com").build();
        active.setStatus(Website.WebsiteStatus.ACTIVE);
        Website paused = Website.builder().url("https://p.com").build();
        paused.setStatus(Website.WebsiteStatus.PAUSED);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(websiteRepository.findAllByOrganizationId(orgId)).thenReturn(List.of(active, paused));

        List<Website> result = adminDashboardService.getUserWebsites(userId);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("getUserWebsites throws when user not found")
    void getUserWebsites_userNotFound_throws() {
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminDashboardService.getUserWebsites(UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── getUserRecentScans ────────────────────────────────────────────────────

    @Test
    @DisplayName("getUserRecentScans returns recent scans for user's org")
    void getUserRecentScans_delegatesToRepo() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setCurrentOrganizationId(orgId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(scanLogRepository.findRecentByOrganizationId(eq(orgId), any())).thenReturn(List.of());

        List<ScanLog> result = adminDashboardService.getUserRecentScans(userId);

        assertThat(result).isEmpty();
        verify(scanLogRepository).findRecentByOrganizationId(eq(orgId), any());
    }

    @Test
    @DisplayName("getUserRecentScans throws when user not found")
    void getUserRecentScans_userNotFound_throws() {
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminDashboardService.getUserRecentScans(UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── updateUserStatus ──────────────────────────────────────────────────────

    @Test
    @DisplayName("updateUserStatus updates status and saves")
    void updateUserStatus_updatesAndSaves() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setStatus(User.AccountStatus.ACTIVE);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        adminDashboardService.updateUserStatus(userId, User.AccountStatus.SUSPENDED);

        assertThat(user.getStatus()).isEqualTo(User.AccountStatus.SUSPENDED);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("updateUserStatus throws when user not found")
    void updateUserStatus_userNotFound_throws() {
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminDashboardService.updateUserStatus(
                UUID.randomUUID(), User.AccountStatus.SUSPENDED))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── getScanHealthReport ──────────────────────────────────────────────────

    @Test
    @DisplayName("getScanHealthReport maps raw rows correctly")
    void getScanHealthReport_mapsRowsCorrectly() {
        UUID websiteId = UUID.randomUUID();
        UUID monitorId = UUID.randomUUID();
        UUID scanLogId = UUID.randomUUID();
        Timestamp ts = Timestamp.from(Instant.now());
        Object[] row = {websiteId, "https://example.de", "DE", monitorId, "SSL", "INFO", ts, scanLogId, "SUCCESS", "229 days remaining"};
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(row);

        when(scanLogRepository.countHealthReportRows()).thenReturn(1L);
        when(scanLogRepository.findHealthReportRaw(50, 0)).thenReturn(rows);

        AdminDashboardService.ScanHealthPage result = adminDashboardService.getScanHealthReport(0, 50);

        assertThat(result.content()).hasSize(1);
        AdminDashboardService.ScanHealthRow r = result.content().get(0);
        assertThat(r.websiteId()).isEqualTo(websiteId);
        assertThat(r.url()).isEqualTo("https://example.de");
        assertThat(r.region()).isEqualTo("DE");
        assertThat(r.monitorId()).isEqualTo(monitorId);
        assertThat(r.subType()).isEqualTo("SSL");
        assertThat(r.severity()).isEqualTo("INFO");
        assertThat(r.scanLogId()).isEqualTo(scanLogId);
        assertThat(r.status()).isEqualTo("SUCCESS");
        assertThat(r.summary()).isEqualTo("229 days remaining");
        assertThat(r.scannedAt()).isNotNull();
    }

    @Test
    @DisplayName("getScanHealthReport returns correct pagination metadata")
    void getScanHealthReport_paginationMetadata() {
        when(scanLogRepository.countHealthReportRows()).thenReturn(120L);
        when(scanLogRepository.findHealthReportRaw(50, 50)).thenReturn(List.of());

        AdminDashboardService.ScanHealthPage result = adminDashboardService.getScanHealthReport(1, 50);

        assertThat(result.totalElements()).isEqualTo(120L);
        assertThat(result.totalPages()).isEqualTo(3); // ceil(120/50)
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("getScanHealthReport clamps size to max 100")
    void getScanHealthReport_clampsMaxSize() {
        when(scanLogRepository.countHealthReportRows()).thenReturn(0L);
        when(scanLogRepository.findHealthReportRaw(100, 0)).thenReturn(List.of());

        adminDashboardService.getScanHealthReport(0, 999);

        verify(scanLogRepository).findHealthReportRaw(100, 0);
    }

    @Test
    @DisplayName("getScanHealthReport clamps size to min 1")
    void getScanHealthReport_clampsMinSize() {
        when(scanLogRepository.countHealthReportRows()).thenReturn(0L);
        when(scanLogRepository.findHealthReportRaw(1, 0)).thenReturn(List.of());

        adminDashboardService.getScanHealthReport(0, 0);

        verify(scanLogRepository).findHealthReportRaw(1, 0);
    }

    @Test
    @DisplayName("getScanHealthReport calculates correct offset for page > 0")
    void getScanHealthReport_correctOffset() {
        when(scanLogRepository.countHealthReportRows()).thenReturn(200L);
        when(scanLogRepository.findHealthReportRaw(30, 60)).thenReturn(List.of());

        adminDashboardService.getScanHealthReport(2, 30);

        verify(scanLogRepository).findHealthReportRaw(30, 60);
    }

    @Test
    @DisplayName("getScanHealthReport empty result returns empty content with correct totals")
    void getScanHealthReport_emptyResult() {
        when(scanLogRepository.countHealthReportRows()).thenReturn(0L);
        when(scanLogRepository.findHealthReportRaw(50, 0)).thenReturn(List.of());

        AdminDashboardService.ScanHealthPage result = adminDashboardService.getScanHealthReport(0, 50);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isEqualTo(0);
    }

    @Test
    @DisplayName("getScanHealthReport handles null summary gracefully")
    void getScanHealthReport_nullSummary() {
        UUID websiteId = UUID.randomUUID();
        UUID monitorId = UUID.randomUUID();
        UUID scanLogId = UUID.randomUUID();
        Timestamp ts = Timestamp.from(Instant.now());
        Object[] row = {websiteId, "https://example.com", "US", monitorId, "PRIVACY_POLICY", "HIGH", ts, scanLogId, "FAILED", null};
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(row);

        when(scanLogRepository.countHealthReportRows()).thenReturn(1L);
        when(scanLogRepository.findHealthReportRaw(50, 0)).thenReturn(rows);

        AdminDashboardService.ScanHealthPage result = adminDashboardService.getScanHealthReport(0, 50);

        assertThat(result.content().get(0).summary()).isNull();
        assertThat(result.content().get(0).severity()).isEqualTo("HIGH");
    }
}
