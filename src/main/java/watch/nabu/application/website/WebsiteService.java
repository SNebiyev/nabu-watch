package watch.nabu.application.website;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.compliance.ComplianceTaskBuilderService;
import watch.nabu.application.verification.DomainVerificationService;
import watch.nabu.application.verification.UserVerificationService;
import watch.nabu.application.exception.ManualRescanCooldownException;
import watch.nabu.application.exception.OtpDeliveryException;
import watch.nabu.application.exception.PendingVerificationLimitException;
import watch.nabu.application.exception.UserNotFoundException;
import watch.nabu.application.exception.WebsiteNotFoundException;
import watch.nabu.application.monitor.MonitorIntervalConfigService;
import watch.nabu.application.organization.OrganizationService;
import watch.nabu.application.ratelimit.RateLimitPort;
import watch.nabu.application.scan.ScanBatchAggregatorPort;
import watch.nabu.application.scheduler.MonitorDispatchService;
import watch.nabu.infrastructure.config.ScanLimitsProperties;
import watch.nabu.domain.model.*;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.Website.WebsiteStatus;
import watch.nabu.infrastructure.persistence.AbuseReportRepository;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.persistence.OrganizationRepository;
import watch.nabu.infrastructure.persistence.UserRepository;
import watch.nabu.infrastructure.persistence.WebsiteRepository;
import watch.nabu.infrastructure.redis.DeleteOtpService;
import watch.nabu.infrastructure.redis.TaskProducer;
import watch.nabu.infrastructure.web.dto.WebsiteResponse;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class WebsiteService {

    /**
     * SSL monitors are always checked every 24 hours for every user.
     * Certificate validity rarely changes faster than daily, so a fixed 24-hour cadence
     * is the right trade-off between freshness and resource usage.
     */
    private static final int SSL_INTERVAL_MINUTES = 1440;

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final WebsiteRepository websiteRepository;
    private final MonitorRepository monitorRepository;
    private final ComplianceTaskBuilderService complianceTaskBuilder;
    private final TaskProducer taskProducer;
    private final RateLimitPort rateLimitPort;
    private final DeleteOtpService deleteOtpService;
    private final ScanBatchAggregatorPort scanBatchAggregator;
    private final MonitorIntervalConfigService intervalConfigService;
    private final ScanLimitsProperties scanLimits;
    private final OrganizationService organizationService;
    private final MonitorDispatchService monitorDispatchService;
    private final DomainVerificationService domainVerificationService;
    private final UserVerificationService userVerificationService;
    private final AbuseReportRepository abuseReportRepository;

    /**
     * Verifies the caller's organization owns the given website.
     *
     * @return the website entity (callers that need it can skip a second lookup)
     * @throws WebsiteNotFoundException if the website does not exist <b>or</b>
     *                                  belongs to a different organization (prevents UUID enumeration)
     */
    @Transactional(readOnly = true)
    public Website requireAccess(UUID websiteId, UUID orgId) {
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));
        if (!website.getOrganization().getId().equals(orgId)) {
            throw new WebsiteNotFoundException(websiteId);
        }
        return website;
    }

    /**
     * Creates a new website (+ monitors) or syncs the monitor set on an existing one.
     *
     * <h3>Logic</h3>
     * <ol>
     *   <li>Resolve the calling user from the JWT.</li>
     *   <li>Look up {@code (userId, normalised-url)} in the database.</li>
     *   <li>If <b>not found</b> → create website + all monitors → {@code 201 Created}.</li>
     *   <li>If <b>found</b> (ACTIVE or PAUSED) → sync compliance monitors against the
     *       requested list, keep SSL permanent → {@code 200 OK}.</li>
     * </ol>
     *
     * <p>The URL has already been normalised by {@code UrlUtils.normalize()} inside
     * {@code CreateWebsiteRequest.toCommand()}.
     */
    public WebsiteResponse create(CreateWebsiteCommand cmd) {
        String email = watch.nabu.application.security.SecurityContextService.currentEmail();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UserNotFoundException(email));

        UUID orgId = user.getCurrentOrganizationId();
        Organization org = organizationRepository.getReferenceById(orgId);

        // ── Duplicate check ───────────────────────────────────────────────────
        Optional<Website> existing = websiteRepository
                .findByOrganizationIdAndUrl(orgId, cmd.url());

        if (existing.isPresent()) {
            Website site = existing.get();
            log.info("[website] Duplicate rejected: websiteId={} url={} orgId={}",
                    site.getId(), site.getUrl(), orgId);
            throw new WebsiteDuplicateException(cmd.url());
        }

        // ── Anti-abuse gate (new website only) ────────────────────────────────────
        checkPendingVerificationLimit(user);

        // ── New website ───────────────────────────────────────────────────────
        Website website = websiteRepository.save(
            Website.builder()
                    .organization(org)
                    .addedBy(user)
                .url(cmd.url())
                .regionCode(cmd.regionCode())
                .build()
        );

        // Provision a PENDING DNS verification row so the UI can immediately
        // show the user the TXT challenge to add.  Background scheduler +
        // "Check now" endpoint drive transitions to VERIFIED / EXPIRED.
        domainVerificationService.provision(website);
        log.info("[website] Created: websiteId={} url={} region={}",
                website.getId(), website.getUrl(), website.getRegionCode());

        // ── Collect monitors (save only, no enqueue yet) ──────────────────────────
        List<Monitor> createdMonitors = new ArrayList<>();

        // SSL monitor (always fixed at 24 h)
        String sslTarget = cmd.sslTargetUrl();
        createdMonitors.add(buildMonitor(website, MonitorType.SSL, SubType.SSL, SSL_INTERVAL_MINUTES, sslTarget, user));
        log.info("[monitor] SSL created: websiteId={} targetUrl={} intervalMinutes={}",
                website.getId(), sslTarget != null ? sslTarget : website.getUrl(), SSL_INTERVAL_MINUTES);

        // Compliance monitors — interval resolved per subtype from monitor_type_interval_config
        if (cmd.selectedSubTypes() != null && !cmd.selectedSubTypes().isEmpty()) {
            String customTarget = resolveComplianceTarget(cmd, website);
            log.info("[monitor] CUSTOM mode: websiteId={} subTypes={} targetUrl={}",
                website.getId(), cmd.selectedSubTypes(), customTarget);
            for (SubType subType : cmd.selectedSubTypes()) {
                int interval = intervalConfigService.resolveInterval(subType);
                createdMonitors.add(buildMonitor(website, MonitorType.COMPLIANCE, subType, interval, customTarget, user));
                log.info("[monitor] CUSTOM compliance created: websiteId={} subType={} targetUrl={} intervalMinutes={}",
                        website.getId(), subType, customTarget, interval);
            }
        } else {
            Set<SubType> subTypes = complianceTaskBuilder.getSubTypesForRegion(cmd.regionCode());
            if (subTypes.isEmpty()) {
                log.info("[monitor] region='{}' has no active compliance rules — no compliance monitors created",
                    cmd.regionCode());
            } else {
                log.info("[monitor] REGIONAL mode: websiteId={} region={} subTypes={}",
                        website.getId(), cmd.regionCode(), subTypes);
                for (SubType subType : subTypes) {
                    int interval = intervalConfigService.resolveInterval(subType);
                    createdMonitors.add(buildMonitor(website, MonitorType.COMPLIANCE, subType, interval, cmd.complianceTargetUrl(), user));
                    log.info("[monitor] REGIONAL compliance created: websiteId={} subType={} intervalMinutes={}", website.getId(), subType, interval);
                }
            }
        }

        log.info("[monitor] total monitors created: websiteId={} count={}",
            website.getId(), createdMonitors.size());

        // ── Enqueue all monitors as a single batch ────────────────────────────────
        // Registering a batch causes ScanResultProcessor to accumulate per-monitor
        // results and emit exactly one SCAN_SUMMARY notification when the last result
        // arrives, instead of N individual "Scan Completed" bell entries.
        UUID initialBatchId = UUID.randomUUID();
        scanBatchAggregator.registerBatch(
                initialBatchId, createdMonitors.size(),
                website.getId(), website.getUrl(), orgId, "SCHEDULED");
        monitorDispatchService.dispatchBatch(createdMonitors, initialBatchId);
        log.info("[monitor] initial batch enqueued: websiteId={} batchId={} monitorCount={}",
                website.getId(), initialBatchId, createdMonitors.size());

        // First website added → complete the onboarding flag
        if (user.isFirstLogin()) {
            user.setFirstLogin(false);
            userRepository.save(user);
            log.info("[onboarding] isFirstLogin cleared: userId={}", user.getId());
        }

        // A freshly added (PENDING) site moves a previously-VERIFIED user into
        // VERIFIED_PENDING; the listener handles all status transitions.
        userVerificationService.requestRecompute(user);

        return WebsiteResponse.from(website, createdMonitors, false);
    }

    /**
     * Blocks users from queueing more than {@code nabu.websites.pending-verification-limit}
     * unverified websites inside a rolling {@code nabu.websites.pending-verification-window-days}
     * window. A limit of 0 disables the check.
     */
    private void checkPendingVerificationLimit(User user) {
        int limit = scanLimits.pendingVerificationLimit();
        if (limit <= 0) return;
        int windowDays = scanLimits.pendingVerificationWindowDays();
        Instant cutoff = Instant.now()
                .minus(windowDays, java.time.temporal.ChronoUnit.DAYS);
        long pending = websiteRepository.countPendingVerificationSince(user.getId(), cutoff);
        if (pending >= limit) {
            log.warn("[quota] PENDING_VERIFICATION_LIMIT breached: userId={} pending={} max={}",
                    user.getId(), pending, limit);
            throw new PendingVerificationLimitException(limit, windowDays);
        }
    }

    /**
     * Returns the effective scan target for compliance monitors.
     * Explicit caller-supplied URL wins; otherwise the website base URL is used
     * so the worker always has a concrete (non-null) target.
     */
    private static String resolveComplianceTarget(CreateWebsiteCommand cmd, Website website) {
        return (cmd.complianceTargetUrl() != null && !cmd.complianceTargetUrl().isBlank())
            ? cmd.complianceTargetUrl()
            : website.getUrl();
    }

    /**
     * Toggles the website status between {@code ACTIVE} and {@code PAUSED}.
     * Admin-only: callers must hold {@code ROLE_ADMIN} (enforced via {@code @PreAuthorize}).
     *
     * <p>Setting the status to {@code PAUSED} stops the scheduler from dispatching
     * new scan tasks for this website.  Setting it back to {@code ACTIVE} re-enables
     * automatic scheduling.  {@code DELETED} is not an accepted value here; use the
     * delete endpoint instead.
     *
     * @throws WebsiteNotFoundException if no website with {@code websiteId} exists
     * @throws IllegalArgumentException if the caller attempts to set status to {@code DELETED}
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public void updateStatus(UUID websiteId, WebsiteStatus newStatus) {
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));
        website.setStatus(newStatus);
        log.info("Website status updated: websiteId={} newStatus={}", websiteId, newStatus);
    }

    /**
     * Throws {@link ManualRescanCooldownException} when the configured manual-rescan
     * cooldown has not yet elapsed since {@code lastManualScanAt}.
     */
    private void checkManualRescanCooldown(Instant lastManualScanAt) {
        if (lastManualScanAt == null) return;
        int cooldownMinutes = scanLimits.manualRescanCooldownMinutes();
        long elapsedMinutes = java.time.Duration.between(lastManualScanAt, Instant.now()).toMinutes();
        if (elapsedMinutes < cooldownMinutes) {
            long remaining = cooldownMinutes - elapsedMinutes;
            log.warn("[quota] MANUAL_RESCAN_COOLDOWN: elapsedMin={} cooldownMin={} remainingMin={}",
                    elapsedMinutes, cooldownMinutes, remaining);
            throw new ManualRescanCooldownException(remaining);
        }
    }

    /**
     * Immediately re-enqueues all monitors for the given website.
     * Used by the "Re-scan" button in the UI.
     *
     * @param websiteId the website whose monitors will be re-enqueued
     * @param callerId  the UUID of the requesting user (for per-user rate limiting)
     * @param force     when {@code true}, bypasses rate-limit and domain-cooldown checks
     *                  (the caller must have validated Admin TOTP step-up before this)
     */
    @Transactional
    public void rescan(UUID websiteId, UUID callerId, boolean force) {
        if (!force) {
            rateLimitPort.checkUserRateLimit(callerId);
        }
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));

        if (!force) {
            // Global cooldown between manual rescans (nabu.scan.manual-rescan-cooldown-minutes).
            // Scoped per-website via Website.lastManualScanAt so each site has an
            // independent cooldown — rescanning site A does not block site B.
            checkManualRescanCooldown(website.getLastManualScanAt());
        }

        // Only re-enqueue ACTIVE monitors; inactive ones must not receive tasks.
        // All monitors in this batch share one batchId so the scan history can group them.
        List<Monitor> monitors = monitorRepository.findByWebsiteIdAndActiveTrue(websiteId);
        if (!monitors.isEmpty()) {
            UUID batchId = UUID.randomUUID();
            // Register the batch so ScanResultProcessor can aggregate results into a
            // single SCAN_SUMMARY notification instead of N individual ones.
            scanBatchAggregator.registerBatch(
                    batchId, monitors.size(), websiteId, website.getUrl(), callerId, "MANUAL");
            monitorDispatchService.dispatchBatch(monitors, batchId);
            log.info("Manual rescan triggered: websiteId={} activeMonitors={} batchId={} force={}",
                    websiteId, monitors.size(), batchId, force);
        } else {
            log.info("Manual rescan triggered but no active monitors: websiteId={}", websiteId);
        }

        if (!force) {
            // Stamp the website with the current time so the cooldown check has a
            // reference point for the next manual rescan.  Only updated for non-force
            // scans — the automated scheduler never calls rescan() so this field
            // exclusively reflects user-initiated activity.
            website.setLastManualScanAt(Instant.now());
            websiteRepository.save(website);
        }
    }

    /**
     * Generates a one-time delete-confirmation code for the given website and emails it
     * to the requesting admin.
     *
     * <p>Called when an admin attempts to delete a website they do not own.
     * The OTP is stored in Redis with a 10-minute TTL and delivered by
     * {@link DeleteOtpService#generate}.
     *
     * @param websiteId  the website the admin intends to delete
     * @param adminId    UUID of the requesting admin
     * @param adminEmail email address the OTP should be sent to
     * @throws WebsiteNotFoundException if no website with {@code websiteId} exists
     * @throws IllegalArgumentException if the admin owns the website (OTP not applicable)
     * @throws OtpDeliveryException     if the code could not be delivered by email
     */
    public void requestDeleteOtp(UUID websiteId, UUID adminId, String adminEmail) {
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));
        if (website.getAddedBy() != null && website.getAddedBy().getId().equals(adminId)) {
            throw new IllegalArgumentException(
                    "Delete OTP is not applicable for self-owned websites");
        }
        deleteOtpService.generate(websiteId, adminId, adminEmail);
        log.info("[website] Delete OTP requested: websiteId={} adminId={}", websiteId, adminId);
    }

    /**
     * Updates the accepted-languages preference for a website.
     *
     * @param websiteId          the website to update
     * @param acceptedLanguages  the language preference enum value (e.g. {@code "ENGLISH_ACCEPTED"})
     * @throws WebsiteNotFoundException if no website with {@code websiteId} exists
     */
    @Transactional
    public void updateLanguagePreference(UUID websiteId, String acceptedLanguages) {
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));
        Website.AcceptedLanguages pref = Website.AcceptedLanguages.valueOf(acceptedLanguages);
        website.setAcceptedLanguages(pref);
        websiteRepository.save(website);
        log.info("[website] Language preference updated: websiteId={} acceptedLanguages={}",
                websiteId, pref);
    }

    /**
     * Toggles screenshot capture for headless scans on this website.
     * Only the organization owner is allowed to change this setting.
     *
     * @param websiteId the website to update
     * @param callerId  UUID of the requesting user (must be org OWNER)
     * @param enabled   whether to enable screenshot capture
     */
    public void updateScreenshotsEnabled(UUID websiteId, UUID callerId, boolean enabled) {
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));

        organizationService.requireOwner(website.getOrganization().getId(), callerId);

        website.setScreenshotsEnabled(enabled);
        websiteRepository.save(website);

        log.info("[website] Screenshots {}: websiteId={} by={}",
                enabled ? "enabled" : "disabled", websiteId, callerId);
    }

    public void delete(UUID websiteId, UUID callerId, User.Role callerRole, String otpCode) {
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));

        boolean isSelfOwned = website.getAddedBy() != null
                && website.getAddedBy().getId().equals(callerId);

        if (!isSelfOwned) {
            if (callerRole != User.Role.ADMIN) {
                throw new AccessDeniedException(
                        "You do not have permission to delete this website");
            }
            // Admin deleting another user's site — OTP step-up required
            deleteOtpService.verify(websiteId, callerId, otpCode != null ? otpCode : "");
        } else if (abuseReportRepository.existsOpenByWebsiteId(websiteId)) {
            // Owner self-delete would let the ON DELETE CASCADE on
            // abuse_reports.website_id wipe active triage state — admin OTP
            // step-up bypasses this gate because the admin is explicitly
            // accepting the cascade.
            log.warn("[website] Self-delete blocked — open abuse report: websiteId={} callerId={}",
                    websiteId, callerId);
            throw new WebsiteHasOpenAbuseReportException();
        }

        // Dependent rows are removed by ON DELETE CASCADE foreign keys (see the Liquibase changelog).
        User owner = website.getAddedBy();
        websiteRepository.delete(website);

        log.info("[website] Hard-deleted: websiteId={} by callerId={} selfOwned={}",
                websiteId, callerId, isSelfOwned);

        userVerificationService.requestRecompute(owner);
    }

    private Map<UUID, Set<SubType>> loadExistingComplianceSubTypes(List<Website> websites) {
        List<UUID> ids = websites.stream().map(Website::getId).toList();
        Map<UUID, Set<SubType>> out = new java.util.HashMap<>();
        for (Monitor m : monitorRepository.findByWebsiteIdIn(ids)) {
            if (m.getType() != MonitorType.COMPLIANCE) continue;
            out.computeIfAbsent(m.getWebsite().getId(), k -> new java.util.HashSet<>())
                    .add(m.getSubType());
        }
        return out;
    }

    /**
     * Scans every active/paused website and creates any compliance monitors that are defined
     * in the region's rule set but are absent from the website's monitor set.
     *
     * <p>This is an admin-only bulk operation.  It is the correct way to propagate a new
     * compliance type (e.g. {@code COOKIE_BANNER} added via a DB migration) to all existing
     * websites without requiring each owner to delete and re-add their site.
     *
     * <p>Filtering options:
     * <ul>
     *   <li>{@code regionCode} — when non-null, only websites in that region are examined.</li>
     *   <li>{@code subTypeFilter} — when non-null, only this specific sub-type is created.</li>
     *   <li>{@code dryRun} — when {@code true}, no changes are persisted; the method just
     *       counts what <em>would</em> be created.</li>
     * </ul>
     *
     * <p>Monitors that already exist but are inactive (explicitly deactivated via Manage)
     * are intentionally skipped — this method only inserts rows that are completely absent.
     *
     * <p>New monitors are created with a 1440-minute (24 h) interval — the safe default
     * for every user.  The scheduler will pick them up on the next cycle.
     *
     * @return a summary of the operation
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public MonitorSyncResult syncAllMissingMonitors(
            List<String> regionCodes, List<String> subTypeFilters, boolean dryRun) {

        List<Website> websites = websiteRepository.findAll();

        if (regionCodes != null && !regionCodes.isEmpty()) {
            Set<String> upper = regionCodes.stream()
                    .map(String::toUpperCase)
                    .collect(Collectors.toSet());
            websites = websites.stream()
                    .filter(w -> upper.contains(w.getRegionCode().toUpperCase()))
                    .toList();
        }

        Set<SubType> filterSubTypes = null;
        if (subTypeFilters != null && !subTypeFilters.isEmpty()) {
            filterSubTypes = subTypeFilters.stream()
                    .map(s -> SubType.valueOf(s.toUpperCase()))
                    .collect(Collectors.toSet());
        }

        int created = 0;
        Map<String, Integer> bySubType = new java.util.LinkedHashMap<>();
        final Set<SubType> finalFilterSubTypes = filterSubTypes;

        Map<UUID, Set<SubType>> existingByWebsite = loadExistingComplianceSubTypes(websites);

        for (Website website : websites) {
            Set<SubType> existing = existingByWebsite.getOrDefault(website.getId(), Set.of());

            Set<SubType> expected = complianceTaskBuilder.getSubTypesForRegion(website.getRegionCode());

            for (SubType st : expected) {
                if (finalFilterSubTypes != null && !finalFilterSubTypes.contains(st)) continue;
                if (existing.contains(st)) continue;

                if (!dryRun) {
                    buildMonitor(website, MonitorType.COMPLIANCE, st, SSL_INTERVAL_MINUTES, null);
                    log.info("[sync] Created missing monitor: websiteId={} url={} subType={}",
                            website.getId(), website.getUrl(), st);
                }
                created++;
                bySubType.merge(st.name(), 1, Integer::sum);
            }
        }

        log.info("[sync] Complete: websitesScanned={} monitorsCreated={} dryRun={} regionFilter={} subTypeFilter={}",
                websites.size(), created, dryRun, regionCodes, subTypeFilters);
        return new MonitorSyncResult(websites.size(), created, dryRun, bySubType);
    }

    /**
     * Intermediate value object returned by {@link #syncAllMissingMonitors}.
     */
    public record MonitorSyncResult(
            int websitesScanned,
            int monitorsCreated,
            boolean dryRun,
            Map<String, Integer> createdBySubType
    ) {
    }

    /**
     * Saves the monitor but does NOT enqueue it. Callers batch-enqueue after collecting all monitors.
     */
    private Monitor buildMonitor(
            Website website, MonitorType type, SubType subType,
            int intervalMinutes, String targetUrl) {
        return buildMonitor(website, type, subType, intervalMinutes, targetUrl, null);
    }

    private Monitor buildMonitor(
            Website website, MonitorType type, SubType subType,
            int intervalMinutes, String targetUrl, User createdBy) {

        String normalized = (targetUrl != null && !targetUrl.isBlank()) ? targetUrl.strip() : null;
        int effectiveInterval = (type == MonitorType.SSL) ? SSL_INTERVAL_MINUTES : intervalMinutes;

        return monitorRepository.save(
            Monitor.builder()
                .website(website)
                .type(type)
                .subType(subType)
                    .intervalMinutes(effectiveInterval)
                .targetUrl(normalized)
                    .createdBy(createdBy)
                .build()
        );
    }

}
