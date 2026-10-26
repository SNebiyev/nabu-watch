package watch.nabu.application.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.*;
import watch.nabu.infrastructure.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Admin-only use-cases for dashboard analytics and user management.
 *
 * <p>Every public method is guarded by {@code @PreAuthorize("hasRole('ADMIN')")}
 * which is enforced by Spring's method-security proxy (activated via
 * {@code @EnableMethodSecurity} in {@code SecurityConfig}).  The HTTP layer
 * enforces the same role restriction via {@code hasRole("ADMIN")} on the
 * {@code /api/v1/admin/**} matcher — providing defence-in-depth.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final WebsiteRepository websiteRepository;
    private final MonitorRepository monitorRepository;
    private final ScanLogRepository scanLogRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository organizationMemberRepository;

    // ── Stats ──────────────────────────────────────────────────────────────────

    /**
     * Returns a system-wide snapshot used by the admin dashboard.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public AdminStats getStats() {
        long totalUsers    = userRepository.count();
        long totalWebsites = websiteRepository.count();
        long totalMonitors = monitorRepository.count();

        Instant oneHourAgo     = Instant.now().minus(1, ChronoUnit.HOURS);
        long activeScansLastHour = scanLogRepository.countByCreatedAtAfter(oneHourAgo);

        long totalScans  = scanLogRepository.count();
        long failedScans = scanLogRepository.countFailedScans();
        double failureRate = totalScans > 0
                ? Math.round((double) failedScans / totalScans * 10_000.0) / 100.0
            : 0.0;

        log.debug("Admin stats: users={} websites={} monitors={} scansLastHour={} failureRate={}%",
            totalUsers, totalWebsites, totalMonitors, activeScansLastHour, failureRate);

        return new AdminStats(totalUsers, totalWebsites, totalMonitors, activeScansLastHour, failureRate);
    }

    // ── Status changes ──────────────────────────────────────────────────────────

    private static StatusChangeView mapStatusChangeRow(Object[] row) {
        UUID scanLogId = (UUID) row[0];
        UUID monitorId = (UUID) row[1];
        UUID websiteId = (UUID) row[2];
        String websiteUrl = (String) row[3];
        String monitorType = (String) row[4];
        String subType = (String) row[5];
        String severity = (String) row[6];
        String previousSeverity = (String) row[7];
        Instant scannedAt = toInstant(row[8]);
        String orgName = (String) row[9];
        UUID previousScanLogId = (UUID) row[10];

        ScanLog.Severity prev = ScanLog.Severity.valueOf(previousSeverity);
        ScanLog.Severity curr = ScanLog.Severity.valueOf(severity);
        String statusChange = ScanStatusChange.compute(prev, curr).name();

        return new StatusChangeView(scanLogId, monitorId, websiteId, websiteUrl,
                monitorType, subType, severity, previousSeverity, statusChange,
                scannedAt, orgName, previousScanLogId);
    }

    private static ScanHealthRow mapHealthRow(Object[] row) {
        UUID websiteId = (UUID) row[0];
        String url = (String) row[1];
        String region = (String) row[2];
        UUID monitorId = (UUID) row[3];
        String subType = (String) row[4];
        String severity = (String) row[5];
        Instant scannedAt = toInstant(row[6]);
        UUID scanLogId = (UUID) row[7];
        String status = (String) row[8];
        String summary = (String) row[9];
        return new ScanHealthRow(websiteId, url, region, monitorId, subType, severity, scannedAt, scanLogId, status, summary);
    }

    private static Instant toInstant(Object val) {
        if (val instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (val instanceof Instant i) return i;
        return null;
    }

    // ── Scan health report ──────────────────────────────────────────────────────

    @PreAuthorize("hasRole('ADMIN')")
    public List<StatusChangeView> listRecentStatusChanges(int limit) {
        int safeLimit = Math.clamp(limit, 1, 50);
        return scanLogRepository.findRecentStatusChangesRaw(safeLimit)
                .stream()
                .map(AdminDashboardService::mapStatusChangeRow)
                .toList();
    }

    /**
     * Returns the latest scan result per active monitor across all active websites.
     * Ordered by severity (CRITICAL first) then by recency.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public ScanHealthPage getScanHealthReport(int page, int size) {
        int safeSize = Math.clamp(size, 1, 100);
        int offset = page * safeSize;
        long total = scanLogRepository.countHealthReportRows();
        List<ScanHealthRow> content = scanLogRepository.findHealthReportRaw(safeSize, offset).stream()
                .map(AdminDashboardService::mapHealthRow)
                .toList();
        return new ScanHealthPage(content, total, page, safeSize);
    }

    public record ScanHealthPage(List<ScanHealthRow> content, long totalElements, int page, int size) {
        public int totalPages() {
            return (int) Math.ceil((double) totalElements / size);
        }
    }

    public record ScanHealthRow(
            UUID websiteId, String url, String region,
            UUID monitorId, String subType, String severity,
            Instant scannedAt, UUID scanLogId, String status, String summary
    ) {
    }

    // ── User management ────────────────────────────────────────────────────────

    /**
     * Returns every registered user with their active website count.
     * Non-paginated — used by tests and internal callers.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserAdminView> listUsers() {
        return userRepository.findAllWithSiteCount();
    }

    /**
     * Returns a page of users ordered by {@code createdAt DESC}.
     * Used by {@code GET /api/v1/admin/users?page=&size=}.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public Page<UserAdminView> listUsersPaged(int page, int size) {
        int safeSize = Math.clamp(size, 1, 100);
        return userRepository.findAllWithSiteCountPaged(
                PageRequest.of(page, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    /**
     * Returns the websites belonging to the given user's current organization.
     * Used by the admin user-details drawer.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public List<Website> getUserWebsites(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        return websiteRepository.findAllByOrganizationId(user.getCurrentOrganizationId());
    }

    /**
     * Returns the 20 most-recent scan logs for websites owned by the given user's organization.
     * Used by the admin user-details drawer activity tab.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public List<ScanLog> getUserRecentScans(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        return scanLogRepository.findRecentByOrganizationId(user.getCurrentOrganizationId(), PageRequest.of(0, 20));
    }

    /**
     * Updates a user's account status (e.g. ACTIVE ↔ SUSPENDED).
     *
     * @throws NoSuchElementException if no user with {@code userId} exists
     * @throws IllegalArgumentException if the transition is not permitted
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void updateUserStatus(UUID userId, User.AccountStatus newStatus) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        log.info("Admin: updating user {} status {} → {}", userId, user.getStatus(), newStatus);
        user.setStatus(newStatus);
        userRepository.save(user);
    }

    // ── Organization management ───────────────────────────────────────────────

    @PreAuthorize("hasRole('ADMIN')")
    public Page<OrgAdminView> listOrganizationsPaged(String search, int page, int size) {
        int safeSize = Math.clamp(size, 1, 100);
        PageRequest pageable = PageRequest.of(page, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Organization> orgPage = (search != null && !search.isBlank())
                ? organizationRepository.searchByName(search.trim(), pageable)
                : organizationRepository.findAll(pageable);

        return orgPage.map(org -> {
            long memberCount = organizationMemberRepository.countByOrganizationId(org.getId());
            long websiteCount = websiteRepository.countByOrganizationId(org.getId());
            return new OrgAdminView(
                    org.getId(), org.getName(), org.isEnabled(),
                    memberCount, websiteCount, org.getCreatedAt());
        });
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<OrgMemberAdminView> listOrganizationMembers(UUID orgId) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new NoSuchElementException("Organization not found: " + orgId));
        return organizationMemberRepository.findAllByOrganizationIdWithUser(orgId).stream()
                .map(m -> new OrgMemberAdminView(
                        m.getUser().getId(),
                        m.getUser().getFullName(),
                        m.getUser().getEmail(),
                        m.getRole().name(),
                        m.getJoinedAt()))
                .toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void updateOrganizationEnabled(UUID orgId, boolean enabled) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new NoSuchElementException("Organization not found: " + orgId));
        log.info("Admin: updating org {} enabled {} → {}", orgId, org.isEnabled(), enabled);
        org.setEnabled(enabled);
        organizationRepository.save(org);
    }

    public record OrgAdminView(
            UUID id,
            String name,
            boolean enabled,
            long memberCount,
            long websiteCount,
            Instant createdAt
    ) {
    }

    public record OrgMemberAdminView(
            UUID userId,
            String fullName,
            String email,
            String role,
            Instant joinedAt
    ) {
    }

    // ── Mapping helpers ─────────────────────────────────────────────────────────

    public record StatusChangeView(
            UUID scanLogId, UUID monitorId, UUID websiteId, String websiteUrl,
            String monitorType, String subType, String severity, String previousSeverity,
            String statusChange, Instant scannedAt, String orgName, UUID previousScanLogId
    ) {
    }

    // ── Value objects ──────────────────────────────────────────────────────────

    public record AdminStats(
            long   totalUsers,
            long   totalWebsites,
            long   totalMonitors,
            long   activeScansLastHour,
            double systemFailureRate
    ) {}
}
