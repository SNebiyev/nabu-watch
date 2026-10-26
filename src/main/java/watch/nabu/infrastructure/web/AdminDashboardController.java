package watch.nabu.infrastructure.web;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.admin.AdminDashboardService;
import watch.nabu.application.scanlog.ScanLogReportService;
import watch.nabu.application.website.WebsiteService;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.ScanLogRepository;
import watch.nabu.infrastructure.web.dto.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-only endpoints.
 *
 * <p>HTTP-level access control is enforced by the {@code hasRole("ADMIN")}
 * rule on {@code /api/v1/admin/**} in {@code SecurityConfig}.  Each delegated
 * service method carries an additional {@code @PreAuthorize} guard as a
 * second line of defence.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/v1/admin/stats}                   — system-wide analytics snapshot</li>
 *   <li>{@code GET  /api/v1/admin/users}                   — paginated user directory</li>
 *   <li>{@code GET  /api/v1/admin/users/{id}/websites}     — websites owned by a user</li>
 *   <li>{@code GET  /api/v1/admin/users/{id}/scans}        — recent scans for a user</li>
 *   <li>{@code PATCH /api/v1/admin/users/{id}/status}      — suspend / activate a user</li>
 *   <li>{@code GET  /api/v1/admin/scans/status-changes}    — recent monitor status transitions</li>
 *   <li>{@code GET  /api/v1/admin/scans/global}            — 20 most-recent scans globally</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService adminService;
    private final ScanLogRepository scanLogRepository;
    private final WebsiteService websiteService;
    private final ScanLogReportService scanLogReportService;

    /**
     * POST /api/v1/admin/monitors/sync?dryRun=true&regionCodes=DE&regionCodes=AT&subTypes=COOKIE_BANNER
     *
     * <p>Scans every active/paused website and creates any compliance monitors that are
     * defined in the region's rule set but are absent from the website's monitor set.
     * Newly created monitors are picked up automatically by the next scheduler cycle.
     *
     * <p>Query parameters (all optional, multi-valued):
     * <ul>
     *   <li>{@code dryRun}      — when {@code true}, no DB writes; returns a preview count</li>
     *   <li>{@code regionCodes} — restrict sync to one or more regions (repeatable, e.g. {@code DE&regionCodes=AT})</li>
     *   <li>{@code subTypes}    — restrict sync to one or more sub-types (repeatable)</li>
     * </ul>
     */
    @PostMapping("/monitors/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public MonitorSyncResponse syncMonitors(
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(required = false) List<String> regionCodes,
            @RequestParam(required = false) List<String> subTypes) {

        WebsiteService.MonitorSyncResult result =
                websiteService.syncAllMissingMonitors(regionCodes, subTypes, dryRun);
        return new MonitorSyncResponse(
                result.websitesScanned(),
                result.monitorsCreated(),
                result.dryRun(),
                result.createdBySubType());
    }

    /**
     * GET /api/v1/admin/stats
     *
     * <p>Returns a JSON object suitable for the admin overview panel:
     * <pre>
     * {
     *   "totalUsers":          42,
     *   "totalWebsites":       130,
     *   "totalMonitors":       780,
     *   "activeScansLastHour": 57,
     *   "systemFailureRate":   3.14
     * }
     * </pre>
     */
    @GetMapping("/stats")
    public AdminStatsResponse getStats() {
        AdminDashboardService.AdminStats stats = adminService.getStats();
        return new AdminStatsResponse(
            stats.totalUsers(),
            stats.totalWebsites(),
            stats.totalMonitors(),
            stats.activeScansLastHour(),
            stats.systemFailureRate()
        );
    }

    /**
     * GET /api/v1/admin/scans/status-changes?limit=20
     *
     * <p>Returns the most-recent scans whose severity changed compared to the
     * immediately-preceding scan for the same monitor. Each item includes the
     * computed {@code statusChange} label (RECOVERED, REGRESSED, IMPROVED,
     * REGRESSED_TO_FAILING).
     *
     * <p>The {@code limit} parameter is capped at 50 server-side.
     */
    @GetMapping("/scans/status-changes")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminDashboardService.StatusChangeView> listRecentStatusChanges(
            @RequestParam(defaultValue = "20") int limit) {
        return adminService.listRecentStatusChanges(limit);
    }

    /**
     * GET /api/v1/admin/scans/health-report
     *
     * <p>Returns the latest scan result per active monitor for all active websites.
     * Ordered by severity (CRITICAL first) then by recency.  Used by the admin
     * "Scan Health Report" bulk-review panel.
     */
    @GetMapping("/scans/health-report")
    @PreAuthorize("hasRole('ADMIN')")
    public ScanHealthReportResponse getScanHealthReport(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = adminService.getScanHealthReport(page, size);
        List<ScanHealthReportItem> items = result.content().stream()
                .map(r -> new ScanHealthReportItem(
                        r.websiteId(), r.url(), r.region(),
                        r.monitorId(), r.subType(), r.severity(),
                        r.scannedAt(), r.scanLogId(), r.status(), r.summary()))
                .toList();
        return new ScanHealthReportResponse(items, result.totalElements(), result.totalPages(), result.page(), result.size());
    }

    record ScanHealthReportResponse(List<ScanHealthReportItem> content, long totalElements, int totalPages, int page,
                                    int size) {
    }

    /**
     * GET /api/v1/admin/scans/global
     *
     * <p>Returns the 20 most-recent scan results across all users and websites.
     * The response shape mirrors the SSE {@code scan-result} payload so the
     * admin activity feed can use the same parsing code as the user feed.
     */
    @GetMapping("/scans/global")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ScanActivityItem> globalScanFeed() {
        return scanLogRepository
                .findRecentGlobal(PageRequest.of(0, 20))
                .stream()
                .map(sl -> new ScanActivityItem(
                        sl.getId().toString(),
                        sl.getMonitor().getId().toString(),
                        sl.getMonitor().getWebsite().getId().toString(),
                        sl.getMonitor().getWebsite().getUrl(),
                        sl.getMonitor().getType().name(),
                        sl.getMonitor().getSubType().name(),
                        sl.getSeverity().name(),
                        sl.getCreatedAt().toString()))
                .toList();
    }

    /**
     * GET /api/v1/admin/scans/{scanLogId}
     *
     * <p>Returns the full scan record including the {@code resultJson} blob.
     * Used by the admin scan-detail drawer to render compliance keyword chips
     * and SSL certificate fields without a separate page navigation.
     */
    @GetMapping("/scans/{scanLogId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminScanDetailResponse> getScanDetail(@PathVariable UUID scanLogId) {
        return scanLogRepository.findByIdWithDetails(scanLogId)
                .map(sl -> ResponseEntity.ok(new AdminScanDetailResponse(
                        sl.getId().toString(),
                        sl.getMonitor().getId().toString(),
                        sl.getMonitor().getWebsite().getId().toString(),
                        sl.getMonitor().getWebsite().getUrl(),
                        sl.getMonitor().getType().name(),
                        sl.getMonitor().getSubType().name(),
                        sl.getSeverity().name(),
                        sl.getCreatedAt().toString(),
                        sl.getResultJson(),
                        sl.getTriggerType() != null ? sl.getTriggerType().name() : null,
                        sl.getTriggeredBy() != null ? sl.getTriggeredBy().getEmail() : null
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/admin/users?page=0&size=20
     *
     * <p>Returns a paginated user directory ordered by {@code createdAt DESC}.
     */
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public UserPageResponse listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<watch.nabu.application.admin.UserAdminView> p = adminService.listUsersPaged(page, size);
        List<UserSummaryItem> content = p.getContent().stream()
            .map(v -> new UserSummaryItem(
                v.id(),
                v.fullName(),
                v.email(),
                v.role().name(),
                v.status().name(),
                v.createdAt(),
                    v.siteCount(),
                    v.lastLoginAt()
            ))
                .toList();
        return new UserPageResponse(content, p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    /**
     * GET /api/v1/admin/users/{userId}/websites
     *
     * <p>Returns non-deleted websites owned by the given user.
     * Used by the admin user-details drawer.
     */
    @GetMapping("/users/{userId}/websites")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserWebsiteItem> getUserWebsites(@PathVariable UUID userId) {
        return adminService.getUserWebsites(userId).stream()
                .map(w -> new UserWebsiteItem(
                        w.getId().toString(),
                        w.getUrl(),
                        w.getRegionCode(),
                        w.getStatus().name()
                ))
                .toList();
    }

    /**
     * GET /api/v1/admin/users/{userId}/scans
     *
     * <p>Returns the 20 most-recent scan logs for websites owned by the given user.
     * Used by the admin user-details drawer activity tab.
     */
    @GetMapping("/users/{userId}/scans")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ScanActivityItem> getUserScans(@PathVariable UUID userId) {
        return adminService.getUserRecentScans(userId).stream()
                .map(sl -> new ScanActivityItem(
                        sl.getId().toString(),
                        sl.getMonitor().getId().toString(),
                        sl.getMonitor().getWebsite().getId().toString(),
                        sl.getMonitor().getWebsite().getUrl(),
                        sl.getMonitor().getType().name(),
                        sl.getMonitor().getSubType().name(),
                        sl.getSeverity().name(),
                        sl.getCreatedAt().toString()
            ))
            .toList();
    }

    /**
     * PATCH /api/v1/admin/users/{userId}/status
     *
     * <p>Updates a user's account status.  Request body: {@code {"status":"SUSPENDED"}}.
     * Returns 204 No Content on success.
     *
     * @throws org.springframework.web.server.ResponseStatusException 400 if the status value is invalid
     */
    @PatchMapping("/users/{userId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateUserStatus(
            @PathVariable UUID userId,
            @RequestBody UpdateUserStatusRequest req) {

        User.AccountStatus newStatus;
        try {
            newStatus = User.AccountStatus.valueOf(req.status());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Invalid status value: " + req.status());
        }
        adminService.updateUserStatus(userId, newStatus);
        return ResponseEntity.noContent().build();
    }

    // ── Organization admin endpoints ──────────────────────────────────────────

    /**
     * GET /api/v1/admin/organizations?page=0&size=20&search=...
     *
     * <p>Returns a paginated list of all organizations with member/website counts.
     */
    @GetMapping("/organizations")
    @PreAuthorize("hasRole('ADMIN')")
    public OrgAdminPageResponse listOrganizations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search) {

        Page<AdminDashboardService.OrgAdminView> p = adminService.listOrganizationsPaged(search, page, size);
        List<OrgAdminItem> content = p.getContent().stream()
                .map(v -> new OrgAdminItem(
                        v.id(), v.name(), v.enabled(),
                        v.memberCount(), v.websiteCount(), v.createdAt()))
                .toList();
        return new OrgAdminPageResponse(content, p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    /**
     * GET /api/v1/admin/organizations/{orgId}/members
     *
     * <p>Returns all members of the given organization.
     */
    @GetMapping("/organizations/{orgId}/members")
    @PreAuthorize("hasRole('ADMIN')")
    public List<OrgMemberAdminItem> getOrganizationMembers(@PathVariable UUID orgId) {
        return adminService.listOrganizationMembers(orgId).stream()
                .map(m -> new OrgMemberAdminItem(m.userId(), m.fullName(), m.email(), m.role(), m.joinedAt()))
                .toList();
    }

    /**
     * PATCH /api/v1/admin/organizations/{orgId}/status
     *
     * <p>Enables or disables an organization. Request body: {@code {"enabled": false}}.
     */
    @PatchMapping("/organizations/{orgId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateOrganizationStatus(
            @PathVariable UUID orgId,
            @RequestBody UpdateOrgStatusRequest req) {
        adminService.updateOrganizationEnabled(orgId, req.enabled());
        return ResponseEntity.noContent().build();
    }

    // ── Reports ────────────────────────────────────────────────────────────────

    /**
     * GET /api/v1/admin/reports/count
     * Returns the number of currently unresolved (reported=true) scan logs.
     * Used by the admin sidebar badge.
     */
    @GetMapping("/reports/count")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Long> getReportCount() {
        return Map.of("count", scanLogRepository.countByReportedTrue());
    }

    /**
     * GET /api/v1/admin/reports?page=0&amp;size=20
     * Paginated list of all reported scan logs, newest first.
     */
    @GetMapping("/reports")
    @PreAuthorize("hasRole('ADMIN')")
    public ReportedScanPageResponse listReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<ScanLog> result = scanLogReportService.listReported(page, size);
        List<ReportedScanItem> content = result.getContent().stream()
                .map(sl -> new ReportedScanItem(
                        sl.getId(),
                        sl.getMonitor().getWebsite().getUrl(),
                        sl.getMonitor().getWebsite().getOrganization().getName(),
                        sl.getMonitor().getType().name(),
                        sl.getMonitor().getSubType().name(),
                        sl.getSeverity().name(),
                        sl.getCreatedAt(),
                        sl.getReportedBy() != null ? sl.getReportedBy().getEmail() : null,
                        sl.getReportNotes(),
                        sl.getReportedAt(),
                        sl.getResultJson()
                ))
                .toList();
        return new ReportedScanPageResponse(content,
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    /**
     * POST /api/v1/admin/reports/{scanLogId}/resolve
     * Clears the reported flag — call after the underlying issue is fixed.
     */
    @PostMapping("/reports/{scanLogId}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resolveReport(@PathVariable UUID scanLogId) {
        scanLogReportService.resolve(scanLogId);
        return ResponseEntity.noContent().build();
    }
}
