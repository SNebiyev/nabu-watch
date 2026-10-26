package watch.nabu.infrastructure.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.monitor.MonitorSyncService;
import watch.nabu.application.ratelimit.RateLimitPort;
import watch.nabu.application.security.MfaValidationService;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.infrastructure.config.ScanLimitsProperties;
import watch.nabu.application.website.WebsiteQueryService;
import watch.nabu.application.verification.DomainVerificationService;
import watch.nabu.application.website.WebsiteService;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.web.dto.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/websites")
@RequiredArgsConstructor
public class WebsiteController {

    private final WebsiteService websiteService;
    private final WebsiteQueryService websiteQueryService;
    private final MonitorSyncService monitorSyncService;
    private final SecurityContextService securityContext;
    private final MfaValidationService mfaValidationService;
    private final ScanLimitsProperties scanLimits;
    private final RateLimitPort rateLimitPort;
    private final DomainVerificationService domainVerificationService;

    /**
     * GET /api/v1/websites
     *
     * Returns a paginated health snapshot for every website belonging to the
     * authenticated user.  Supports URL search and status/monitor filtering.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code q}              — URL partial match (case-insensitive)</li>
     *   <li>{@code status}         — site-level effective status:
     *                                FAILING | PASSING | WARNING | PENDING</li>
     *   <li>{@code monitorSubType} — filter by specific monitor sub-type,
     *                                e.g. PRIVACY_POLICY, SSL, IMPRESSUM</li>
     *   <li>{@code monitorStatus}  — required status for that sub-type</li>
     *   <li>{@code page}           — 0-based page index (default 0)</li>
     *   <li>{@code size}           — page size 1–100 (default 20)</li>
     *   <li>{@code sort}           — one of {@code lastCheckedAt} (default),
     *                                {@code nextScanAt}, {@code region}, {@code grade},
     *                                {@code status}</li>
     *   <li>{@code direction}      — {@code asc} or {@code desc} (default {@code desc})</li>
     * </ul>
     *
     * <p>The {@code stats} block in the response reflects the full URL-matched
     * set so dashboard KPI cards are stable regardless of status filter.
     */
    @GetMapping
    public WebsitePageResponse listForCurrentUser(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(name = "q", defaultValue = "") String search,
            @RequestParam(name = "status", defaultValue = "") String status,
            @RequestParam(name = "monitorSubType", defaultValue = "") String monitorSubType,
            @RequestParam(name = "monitorStatus", defaultValue = "") String monitorStatus,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", defaultValue = "lastCheckedAt") String sort,
            @RequestParam(name = "direction", defaultValue = "desc") String direction) {
        UUID orgId = securityContext.resolveOrgId(principal.getUsername());
        return websiteQueryService.listHealthPagedByOrganization(
                orgId, search, status, monitorSubType, monitorStatus, page, size, sort, direction);
    }

    /**
     * POST /api/v1/websites
     *
     * <p>Creates a new website and its monitors. Responds {@code 201 Created}.
     * If the URL already exists within the caller's organization, responds
     * {@code 409 Conflict} without creating anything.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebsiteResponse create(@RequestBody @Valid CreateWebsiteRequest request) {
        return websiteService.create(request.toCommand());
    }

    /**
     * GET /api/v1/websites/{id}
     * <p>
     * Returns a full health snapshot for a single website.
     * Used by the frontend notification deep-link to open the details drawer
     * for a specific website without knowing which page it lives on.
     *
     * <p>Responds with {@code 404 Not Found} when the website does not exist.
     */
    @GetMapping("/{id}")
    public WebsiteHealthListItem getById(@PathVariable UUID id,
                                         @AuthenticationPrincipal UserDetails principal) {
        websiteService.requireAccess(id, securityContext.resolveOrgId(principal.getUsername()));
        return websiteQueryService.getHealthListItemById(id);
    }

    /**
     * GET /api/v1/websites/{id}/verification
     * <p>Returns the TXT challenge + current status for the Verify Domain card.
     */
    @GetMapping("/{id}/verification")
    public WebsiteVerificationResponse getVerification(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        var website = websiteService.requireAccess(
                id, securityContext.resolveOrgId(principal.getUsername()));
        var v = domainVerificationService.loadOrFail(id);
        return WebsiteVerificationResponse.from(v, DomainVerificationService.hostOf(website));
    }

    /**
     * POST /api/v1/websites/{id}/verification/check
     * <p>User-triggered DNS TXT lookup.  Returns the refreshed verification
     * snapshot so the UI can update without a second GET.
     */
    @PostMapping("/{id}/verification/check")
    public WebsiteVerificationResponse runVerificationCheck(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        var website = websiteService.requireAccess(
                id, securityContext.resolveOrgId(principal.getUsername()));
        var v = domainVerificationService.check(id);
        return WebsiteVerificationResponse.from(v, DomainVerificationService.hostOf(website));
    }

    /**
     * GET /api/v1/websites/{id}/health
     *
     * Returns the aggregated health snapshot for the website: overall
     * traffic-light status (GREEN / YELLOW / RED) plus a per-monitor
     * summary with the latest severity and a human-readable result line.
     *
     * <p>Responds with {@code 404 Not Found} when the website does not exist.
     */
    @GetMapping("/{id}/health")
    public WebsiteHealthResponse health(@PathVariable UUID id,
                                        @AuthenticationPrincipal UserDetails principal) {
        websiteService.requireAccess(id, securityContext.resolveOrgId(principal.getUsername()));
        return WebsiteHealthResponse.from(websiteQueryService.getHealthView(id));
    }

    /**
     * GET /api/v1/websites/{id}/latest-scans
     *
     * Returns the most-recent {@link LatestScanItem} for every monitor
     * belonging to the website, including the full {@code result_json} payload.
     * Used by the frontend detail drawer to show SSL certificate info,
     * compliance keywords, and error messages.
     *
     * <p>Responds with {@code 404 Not Found} when the website does not exist.
     */
    @GetMapping("/{id}/latest-scans")
    public List<LatestScanItem> latestScans(@PathVariable UUID id,
                                            @AuthenticationPrincipal UserDetails principal) {
        websiteService.requireAccess(id, securityContext.resolveOrgId(principal.getUsername()));
        return websiteQueryService.getLatestScans(id);
    }

    /**
     * PUT /api/v1/websites/{id}/monitors
     *
     * Reconciles the compliance monitor set for the website against the
     * supplied {@code enabledSubTypes} list:
     * <ul>
     *   <li>Sub-types in the list that have no monitor yet → new monitor created
     *       and immediately enqueued.</li>
     *   <li>Sub-types in the list whose monitor was inactive → monitor
     *       reactivated and immediately enqueued.</li>
     *   <li>Sub-types NOT in the list whose monitor is currently active →
     *       monitor soft-deactivated (scan history retained).</li>
     *   <li>SSL is always active and is ignored if present in the list.</li>
     * </ul>
     *
     * <p>Returns a diff summary: {@code activated}, {@code deactivated},
     * and {@code unchanged}.
     */
    @PutMapping("/{id}/monitors")
    public SyncMonitorsResult updateMonitors(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateMonitorsRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        websiteService.requireAccess(id, securityContext.resolveOrgId(principal.getUsername()));
        return monitorSyncService.sync(id, request.enabledSubTypes());
    }

    /**
     * POST /api/v1/websites/{id}/rescan
     *
     * Immediately re-enqueues all monitors for the website onto the Redis
     * scan-tasks stream, bypassing the normal interval schedule.
     * Responds {@code 202 Accepted} — results will arrive asynchronously
     * via the SSE stream.
     *
     * <p>Rate limiting: 5 rescans per minute per user; global domain cooldown.
     * Both can be bypassed by Admin users with a valid TOTP code:
     * pass {@code ?force=true} and set the {@code X-MFA-Token} header.
     */
    @PostMapping("/{id}/rescan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void rescan(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestHeader(name = "X-MFA-Token", required = false) String mfaToken,
            @AuthenticationPrincipal UserDetails principal) {
        if (force) {
            mfaValidationService.validateForce(principal.getUsername(), mfaToken);
        }
        UUID callerId = securityContext.resolveUserId(principal.getUsername());
        websiteService.rescan(id, callerId, force);
    }

    /**
     * PATCH /api/v1/websites/{id}/language-preference
     *
     * <p>Updates the accepted-languages setting for compliance scans.
     * Determines whether English fallback keywords are treated as passing.
     */
    @PatchMapping("/{id}/language-preference")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateLanguagePreference(
            @PathVariable UUID id,
            @RequestBody UpdateLanguagePreferenceRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        websiteService.requireAccess(id, securityContext.resolveOrgId(principal.getUsername()));
        websiteService.updateLanguagePreference(id, request.acceptedLanguages());
    }

    record UpdateLanguagePreferenceRequest(String acceptedLanguages) {
    }

    /**
     * PATCH /api/v1/websites/{id}/screenshots
     *
     * <p>Toggles screenshot capture for headless scans. Only the organization
     * owner can change this setting. Returns the new {@code screenshotsEnabled} value.
     */
    @PatchMapping("/{id}/screenshots")
    public Map<String, Object> updateScreenshots(
            @PathVariable UUID id,
            @RequestBody UpdateScreenshotsRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        UUID callerId = securityContext.resolveUserId(principal.getUsername());
        websiteService.updateScreenshotsEnabled(id, callerId, request.enabled());
        return Map.of("screenshotsEnabled", request.enabled());
    }

    record UpdateScreenshotsRequest(boolean enabled) {
    }

    /**
     * PATCH /api/v1/websites/{id}/status
     * <p>
     * Toggles the website between {@code ACTIVE} and {@code PAUSED}.
     * Admin-only endpoint — enforced both here and at the service layer via
     * {@code @PreAuthorize("hasRole('ADMIN')")}.
     *
     * <p>A {@code PAUSED} website is excluded from the automatic scheduling cycle;
     * its monitors will not receive new scan tasks until the status is restored to
     * {@code ACTIVE}.
     *
     * <p>Responds {@code 204 No Content} on success.
     */
    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateStatus(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateWebsiteStatusRequest request) {
        websiteService.updateStatus(id, request.status());
    }

    /**
     * GET /api/v1/websites/{id}/monitors
     * <p>
     * Returns the full monitor configuration list for the website.
     * Used by the Settings modal to inspect and manage per-monitor target URLs.
     *
     * <p>Responds with {@code 404 Not Found} when the website does not exist.
     */
    @GetMapping("/{id}/monitors")
    public List<MonitorConfigItem> monitorConfigs(@PathVariable UUID id,
                                                  @AuthenticationPrincipal UserDetails principal) {
        websiteService.requireAccess(id, securityContext.resolveOrgId(principal.getUsername()));
        return websiteQueryService.getMonitorConfigs(id);
    }

    /**
     * POST /api/v1/websites/{id}/delete-otp
     * <p>
     * Generates a 6-digit one-time code and sends it to the calling admin's
     * registered email address.  The code expires after 10 minutes.
     *
     * <p>This endpoint is only meaningful when the admin does NOT own the
     * target website.  Calling it for a self-owned website returns 400 Bad Request.
     *
     * <p>Responds {@code 202 Accepted} — delivery is asynchronous (email, SMS, etc.).
     */
    @PostMapping("/{id}/delete-otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestDeleteOtp(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        User caller = securityContext.resolveUser(principal.getUsername());
        websiteService.requestDeleteOtp(id, caller.getId(), caller.getEmail());
    }

    /**
     * DELETE /api/v1/websites/{id}
     * <p>
     * Soft-deletes the website and deactivates all its monitors.
     * Scan logs are retained as an immutable audit trail.
     *
     * <h3>Ownership scenarios</h3>
     * <ul>
     *   <li><b>Self-owned</b>: no OTP required; supply an empty body or omit it.</li>
     *   <li><b>Admin / other-owned</b>: caller must be ADMIN and must supply the
     *       {@code X-Delete-OTP} header containing the 6-digit code issued by
     *       {@code POST /{id}/delete-otp}.  The code is single-use and expires in 10 min.</li>
     * </ul>
     *
     * <p>Responds {@code 204 No Content} on success.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id,
            @RequestHeader(name = "X-Delete-OTP", required = false) String otpCode,
            @AuthenticationPrincipal UserDetails principal) {
        User caller = securityContext.resolveUser(principal.getUsername());
        websiteService.delete(id, caller.getId(), caller.getRole(), otpCode);
    }

    /**
     * GET /api/v1/websites/{id}/scan-history
     * <p>
     * Returns the scan audit trail for the website as an ordered list of
     * {@link ScanBatch} objects, newest first (at most 30 batches).
     *
     * <p>Modern scans are grouped by their shared {@code batch_id}.
     * Legacy scans (null {@code batch_id}) are grouped by a synthetic
     * 60-second time window so the history remains coherent for existing data.
     *
     * <p>Each batch includes its constituent {@link LatestScanItem} results
     * so the frontend can render a historical snapshot without further round-trips.
     *
     * <p>Responds with {@code 404 Not Found} when the website does not exist.
     */
    @GetMapping("/{id}/scan-history")
    public List<ScanBatch> scanHistory(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserDetails principal) {
        websiteService.requireAccess(id, securityContext.resolveOrgId(principal.getUsername()));
        return websiteQueryService.getScanHistory(id);
    }

    /**
     * GET /api/v1/websites/{id}/cooldown
     * <p>
     * Returns remaining cooldown times for both batch and single-monitor rescans
     * so the frontend can display countdowns proactively.
     */
    @GetMapping("/{id}/cooldown")
    public CooldownStatusResponse cooldownStatus(@PathVariable UUID id,
                                                 @AuthenticationPrincipal UserDetails principal) {
        Website website = websiteService.requireAccess(id, securityContext.resolveOrgId(principal.getUsername()));

        // Batch cooldown: nabu.scan.manual-rescan-cooldown-minutes - elapsed since lastManualScanAt
        long batchCooldownSeconds = 0;
        if (website.getLastManualScanAt() != null) {
            long cooldownSeconds = (long) scanLimits.manualRescanCooldownMinutes() * 60;
            long elapsedSeconds = Duration.between(website.getLastManualScanAt(), Instant.now()).toSeconds();
            batchCooldownSeconds = Math.max(0, cooldownSeconds - elapsedSeconds);
        }

        // Domain cooldown: Redis TTL
        long domainCooldownSeconds = rateLimitPort.getDomainCooldownRemaining(website.getUrl());

        return new CooldownStatusResponse(batchCooldownSeconds, domainCooldownSeconds);
    }

    @GetMapping("/{id}/compliance-trend")
    public List<ComplianceTrendBucket> complianceTrend(
            @PathVariable UUID id,
            @RequestParam(name = "period", defaultValue = "MONTHLY") String period,
            @RequestParam(name = "months", defaultValue = "6") int months,
            @RequestParam(name = "subType", required = false) String subType,
            @AuthenticationPrincipal UserDetails principal) {
        websiteService.requireAccess(id, securityContext.resolveOrgId(principal.getUsername()));
        // M-2: validate period against allowlist to prevent unexpected SQL CASE values
        if (!java.util.Set.of("DAILY", "WEEKLY", "MONTHLY").contains(period.toUpperCase())) {
            period = "MONTHLY";
        }
        months = Math.max(1, Math.min(months, 24));
        return websiteQueryService.getComplianceTrend(id, period.toUpperCase(), months, subType);
    }

}
