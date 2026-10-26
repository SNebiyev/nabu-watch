package watch.nabu.application.website;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.exception.WebsiteNotFoundException;
import watch.nabu.domain.model.*;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.domain.model.WebsiteHealth.HealthColor;
import watch.nabu.infrastructure.config.ScanLimitsProperties;
import watch.nabu.infrastructure.persistence.*;
import watch.nabu.infrastructure.web.dto.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-only application service for website-level queries.
 *
 * <p>Kept separate from {@link WebsiteService} (the write side) to maintain a
 * clean command/query separation and allow independent caching later.
 *
 * <p><b>Query strategy — no N+1:</b>
 * <ol>
 *   <li>{@code findById}               — 1 query (website + 404 guard)</li>
 *   <li>{@code findByWebsiteId}         — 1 query (all monitors)</li>
 *   <li>{@code findLatestPerMonitor…}   — 1 query (DISTINCT ON, all latest logs)</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebsiteQueryService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final WebsiteRepository websiteRepository;
    private final MonitorRepository monitorRepository;
    private final ScanLogRepository scanLogRepository;
    private final MonitorStatusOverrideRepository overrideRepository;
    private final ScanLimitsProperties scanLimits;
    private final ObjectMapper objectMapper;

    // ── override resolution helper ─────────────────────────────────────────────

    static WebsiteHealthListItem buildHealthListItem(
            Website website, List<Monitor> monitors, Map<UUID, ScanLog> latestByMonitor) {
        return buildHealthListItem(website, monitors, latestByMonitor, Map.of());
    }

    // ── paginated list (dashboard main view) ─────────────────────────────────

    static WebsiteHealthListItem buildHealthListItem(
            Website website, List<Monitor> monitors, Map<UUID, ScanLog> latestByMonitor,
            Map<UUID, MonitorStatusOverride> overridesByMonitor) {

        List<WebsiteHealthListItem.MonitorHealthItem> items = monitors.stream()
                .map(m -> {
                    ScanLog latest = latestByMonitor.get(m.getId());
                    // Inactive monitors get status "INACTIVE" regardless of any scan data
                    String status = m.isActive() ? monitorScanStatus(latest) : "INACTIVE";
                    boolean langIssue = latest != null
                            && latest.getResultJson() != null
                            && "true".equals(latest.getResultJson().get("language_inconsistency"))
                            && !"true".equals(latest.getResultJson().get("accepted_via_language_preference"));
                    Map<String, Object> resultJson = latest != null ? latest.getResultJson() : null;
                    int missingCount = countJsonArrayField(resultJson, "missing_keywords");
                    // Prefer found_mandatory_native (tiered matrix v2 scans) for the green/amber
                    // threshold decision — optional keywords in found_keywords should not make a
                    // monitor green on their own.  Fall back to found_keywords for old scan rows.
                    int nativeFoundCount = (resultJson != null && resultJson.containsKey("found_mandatory_native"))
                            ? countJsonArrayField(resultJson, "found_mandatory_native")
                            : countJsonArrayField(resultJson, "found_keywords");
                    // When language preference accepts English, include English found keywords in count
                    boolean langPrefAccepted = resultJson != null
                            && "true".equals(resultJson.get("accepted_via_language_preference"));
                    int englishFoundCount = langPrefAccepted
                            ? countJsonArrayField(resultJson, "english_found_keywords")
                              + countJsonArrayField(resultJson, "found_english_fallback")
                            : 0;
                    int foundCount = nativeFoundCount + englishFoundCount;
                    // hasKeywordData = true when ANY of the three keyword arrays is non-empty.
                    // false means the scan ran but retrieved no content (INCOMPLETE / WAF-blocked).
                    boolean hasKeywordData = resultJson != null
                            && (countJsonArrayField(resultJson, "found_keywords") > 0
                            || missingCount > 0
                            || countJsonArrayField(resultJson, "broken_keywords") > 0);
                    Instant nextScanAt = m.getLastRunAt() != null
                            ? m.getLastRunAt().plus(m.getIntervalMinutes(), java.time.temporal.ChronoUnit.MINUTES)
                            : null;
                    var ovr = ResolvedOverride.resolve(
                            m, latest, overridesByMonitor.get(m.getId()));
                    String effectiveStatus = resolveEffectiveStatus(
                            ovr.status(), m.getType().name(), m.getSubType().name(),
                            ovr.severity(), hasKeywordData, langIssue, foundCount);
                    String aiVerifiedTerm = null;
                    String aiVerificationReasoning = null;
                    if (latest != null && latest.getResultJson() != null) {
                        aiVerifiedTerm = (String) latest.getResultJson().get("ai_verified_term");
                        aiVerificationReasoning = (String) latest.getResultJson().get("ai_verification_reasoning");
                    }
                    boolean fastPathVerified = fastPathVerifiedFrom(latest);
                    boolean wafBlocked = isWafBlocked(latest);
                    return new WebsiteHealthListItem.MonitorHealthItem(
                            m.getId(),
                            m.getType().name(),
                            m.getSubType().name(),
                            ovr.status(),
                            effectiveStatus,
                            latest != null ? latest.getCreatedAt() : null,
                            ovr.severity(),
                            m.isActive(),
                            langIssue,
                            missingCount,
                            foundCount,
                            hasKeywordData,
                            m.getCreatedAt(),
                            m.getCreatedBy() != null ? m.getCreatedBy().getEmail() : null,
                            latest != null && latest.getTriggerType() != null
                                    ? latest.getTriggerType().name() : null,
                            nextScanAt,
                            ovr.hasOverride(),
                            ovr.overrideMismatch(),
                            ovr.overrideInfo(),
                            latest != null && latest.getPreviousSeverity() != null
                                    ? latest.getPreviousSeverity().name() : null,
                            latest != null
                                    ? ScanStatusChange.compute(
                                    latest.getPreviousSeverity(), latest.getSeverity()).name()
                                    : null,
                            aiVerifiedTerm,
                            aiVerificationReasoning,
                            fastPathVerified,
                            wafBlocked);
                })
                .toList();

        // Site-level status is derived only from ACTIVE monitors
        boolean anyFailing = items.stream()
                .filter(WebsiteHealthListItem.MonitorHealthItem::active)
                .anyMatch(m -> "FAILING".equals(m.status()));
        boolean anyPassing = items.stream()
                .filter(WebsiteHealthListItem.MonitorHealthItem::active)
                .anyMatch(m -> "PASSING".equals(m.status()));
        String siteStatus = anyFailing ? "FAILING" : anyPassing ? "PASSING" : "PENDING";

        Instant lastCheckedAt = items.stream()
                .filter(WebsiteHealthListItem.MonitorHealthItem::active)
                .map(WebsiteHealthListItem.MonitorHealthItem::lastCheckedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        return new WebsiteHealthListItem(
                website.getId(),
                website.getUrl(),
                website.getRegionCode(),
                siteStatus,
                website.getStatus().name(),
                lastCheckedAt,
                items,
                website.getAddedBy() != null ? website.getAddedBy().getEmail() : null,
                website.getAcceptedLanguages().name(),
                website.isScreenshotsEnabled(),
                website.getComplianceGrade(),
                website.getComplianceScore(),
                website.getVerificationStatus().name());
    }

    /**
     * Maps a scan's severity to a monitor status string.
     *
     * <p>MEDIUM severity means "technically successful but content gaps exist"
     * (e.g. missing compliance keywords, or SSL expiring in 14–30 days).
     * We intentionally map MEDIUM → "PASSING" so the frontend can distinguish
     * it as an amber <em>warning</em> via the separate {@code severity} field,
     * rather than collapsing it into a red FAILING state.
     *
     * <p>Only CRITICAL and HIGH represent true failures that require immediate action.
     */
    private static String monitorScanStatus(ScanLog latest) {
        if (latest == null || latest.getSeverity() == null) return "PENDING";
        return monitorScanStatusFromSeverity(latest.getSeverity());
    }

    // ── filter helpers ────────────────────────────────────────────────────────

    /**
     * Single source of truth for monitor display status — resolves the threshold-based
     * warning rule that distinguishes amber warnings from green passes.
     *
     * <p>Both {@code MonitorHealthItem} and {@code LatestScanItem} call this method
     * so all status rendering in the frontend uses the same logic.
     *
     * @return one of {@code "PASSING"}, {@code "FAILING"}, {@code "WARNING"},
     * {@code "PENDING"}, or {@code "INACTIVE"}
     */
    static String resolveEffectiveStatus(
            String status, String type, String subType,
            String severity, boolean hasKeywordData,
            boolean languageInconsistency, int foundKeywordCount) {
        if (!"PASSING".equals(status)) return status;
        boolean isWarning;
        if (!"COMPLIANCE".equals(type)) {
            isWarning = "MEDIUM".equals(severity); // SSL expiring soon
        } else if ("COOKIE_BANNER".equals(subType)) {
            // DOM-based detection — backend status is authoritative; keyword checks don't apply.
            isWarning = false;
        } else if ("TRACKER_DETECTION".equals(subType)) {
            isWarning = "MEDIUM".equals(severity); // trackers found
        } else if (!hasKeywordData) {
            isWarning = true;  // WAF block / no content retrieved
        } else if (languageInconsistency) {
            isWarning = true;  // docs found but in wrong language
        } else {
            isWarning = foundKeywordCount == 0; // page reached, nothing found at all
        }
        return isWarning ? "WARNING" : "PASSING";
    }

    private static boolean matchesMonitorFilter(
            WebsiteHealthListItem item, String subType, String monitorStatus) {
        boolean hasSubType = subType != null && !subType.isBlank();
        boolean hasStatus = monitorStatus != null && !monitorStatus.isBlank();
        if (!hasSubType && !hasStatus) return true;
        boolean issuesMode = "ISSUES".equalsIgnoreCase(monitorStatus);
        return item.monitors().stream()
                .filter(m -> !hasSubType || subType.equalsIgnoreCase(m.subType()))
                .anyMatch(m -> {
                    if (!hasStatus) return true;
                    String effective = m.effectiveStatus();
                    if (issuesMode) {
                        return "FAILING".equalsIgnoreCase(effective) || "WARNING".equalsIgnoreCase(effective);
                    }
                    return monitorStatus.equalsIgnoreCase(effective);
                });
    }

    
    private static String monitorScanStatusFromSeverity(Severity severity) {
        if (severity == null) return "PENDING";
        return switch (severity) {
            case CRITICAL, HIGH -> "FAILING";
            default -> "PASSING";
        };
    }

    /**
     * Returns a paginated health snapshot for websites belonging to the organization.
     *
     * <p>Sorting, filtering, and pagination are handled at the database level via
     * {@link watch.nabu.infrastructure.persistence.WebsiteRepository#findDashboardPage}.
     * Only the page's website entities are loaded, keeping query count constant
     * regardless of total website count.
     *
     * <p>KPI stats are computed by a separate aggregate query and are independent
     * of sort/page/filter parameters.
     */
    @Transactional(readOnly = true)
    public WebsitePageResponse listHealthPagedByOrganization(
            UUID orgId,
            String search,
            String statusFilter,
            String monitorSubType,
            String monitorStatus,
            int page,
            int size) {
        return listHealthPagedByOrganization(orgId, search, statusFilter, monitorSubType,
                monitorStatus, page, size, "lastCheckedAt", "desc");
    }

    @Transactional(readOnly = true)
    public WebsitePageResponse listHealthPagedByOrganization(
            UUID orgId,
            String search,
            String statusFilter,
            String monitorSubType,
            String monitorStatus,
            int page,
            int size,
            String sortKey,
            String direction) {

        size = Math.clamp(size, 1, 100);
        page = Math.max(0, page);
        String searchNorm = (search != null && !search.isBlank()) ? search.strip() : null;
        String statusNorm = (statusFilter != null && !statusFilter.isBlank()) ? statusFilter : null;
        String sortNorm = (sortKey != null && !sortKey.isBlank()) ? sortKey.trim() : "lastCheckedAt";
        String dirNorm = "asc".equalsIgnoreCase(direction) ? "asc" : "desc";

        // 1. DB-level sort + paginate → page of website IDs + total count
        List<Object[]> pageRows = websiteRepository.findDashboardPage(
                orgId, searchNorm, statusNorm, sortNorm, dirNorm, size, page * size);

        long total = pageRows.isEmpty() ? 0 : ((Number) pageRows.get(0)[1]).longValue();
        List<UUID> pageWebsiteIds = pageRows.stream()
                .map(r -> (UUID) r[0])
                .toList();

        // 2. Stats — lightweight aggregate, no entity loading
        Object[] statsRow = (Object[]) websiteRepository.findDashboardStats(orgId)[0];
        Object[] changeRow = (Object[]) scanLogRepository.countStatusChanges(orgId,
                Instant.now().minus(24, ChronoUnit.HOURS))[0];
        WebsitePageResponse.Stats stats = new WebsitePageResponse.Stats(
                ((Number) statsRow[0]).intValue(),
                ((Number) statsRow[1]).intValue(),
                ((Number) statsRow[2]).intValue(),
                ((Number) statsRow[3]).intValue(),
                ((Number) statsRow[4]).intValue(),
                ((Number) statsRow[5]).intValue(),
                ((Number) changeRow[0]).intValue(),
                ((Number) changeRow[1]).intValue());

        if (pageWebsiteIds.isEmpty()) {
            return WebsitePageResponse.of(List.of(), total, page, size, stats);
        }

        // 3. Bulk-load entities only for the page
        Map<UUID, Website> websiteMap = websiteRepository.findAllById(pageWebsiteIds)
                .stream().collect(Collectors.toMap(Website::getId, w -> w));

        Map<UUID, List<Monitor>> monitorsByWebsite = monitorRepository
                .findAllByWebsiteIdIn(pageWebsiteIds)
                .stream()
                .collect(Collectors.groupingBy(m -> m.getWebsite().getId()));

        Map<UUID, ScanLog> latestByMonitor = scanLogRepository
                .findLatestPerMonitorByWebsiteIds(pageWebsiteIds)
                .stream()
                .collect(Collectors.toMap(sl -> sl.getMonitor().getId(), sl -> sl));

        Map<UUID, MonitorStatusOverride> overridesByMonitor = overrideRepository
                .findAllByWebsiteIds(pageWebsiteIds)
                .stream()
                .collect(Collectors.toMap(o -> o.getMonitor().getId(), o -> o));

        // 4. Build health items — preserve DB sort order
        List<WebsiteHealthListItem> pageContent = pageWebsiteIds.stream()
                .map(websiteMap::get)
                .filter(Objects::nonNull)
                .map(w -> buildHealthListItem(
                        w,
                        monitorsByWebsite.getOrDefault(w.getId(), List.of()),
                        latestByMonitor,
                        overridesByMonitor))
                .filter(item -> matchesMonitorFilter(item, monitorSubType, monitorStatus))
                .toList();

        return WebsitePageResponse.of(pageContent, total, page, size, stats);
    }

    /**
     * True when the scan's {@code scanned_via} tag indicates a fast-path
     * verification against {@code website_compliance_paths}.
     */
    private static boolean fastPathVerifiedFrom(ScanLog scanLog) {
        if (scanLog == null || scanLog.getResultJson() == null) return false;
        Object via = scanLog.getResultJson().get("scanned_via");
        return via instanceof String s && s.contains("FAST_PATH");
    }

    private static boolean isWafBlocked(ScanLog scanLog) {
        if (scanLog == null || scanLog.getResultJson() == null) return false;
        Map<String, Object> rj = scanLog.getResultJson();
        // Check error_code first (batch abort), then error text (direct WAF fail)
        Object code = rj.get("error_code");
        if (code instanceof String s && ("BATCH_ABORTED".equals(s) || "WAF_BLOCK".equals(s))) return true;
        Object err = rj.get("error");
        return err instanceof String s && s.toUpperCase().contains("WAF");
    }

    private static int countJsonArrayField(Map<String, Object> resultJson, String field) {
        if (resultJson == null) return 0;
        Object raw = resultJson.get(field);
        if (raw instanceof java.util.Collection<?> col) return col.size();
        if (!(raw instanceof String s)) return 0;
        s = s.strip();
        if (s.isEmpty() || "null".equals(s) || "[]".equals(s)) return 0;
        // Quick count: number of comma-separated items in a flat JSON string array.
        // Counts open-quotes to handle keywords that may contain commas.
        // e.g. "[\"Impressum\",\"Datenschutz\"]" → 2 open-quotes → 2 items.
        int count = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                if (!inString) count++;   // entering a string = one more item
                inString = !inString;
            }
        }
        return count;
    }

    /**
     * Returns a single health snapshot for the given website ID.
     * Used by the deep-link drawer endpoint ({@code GET /api/v1/websites/{id}}).
     *
     * <p>Performs exactly 3 queries — no N+1.
     *
     * @throws WebsiteNotFoundException if no website with this ID exists
     */
    @Transactional(readOnly = true)
    public WebsiteHealthListItem getHealthListItemById(UUID websiteId) {
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));

        List<Monitor> monitors = monitorRepository.findByWebsiteId(websiteId);

        Map<UUID, ScanLog> latestByMonitor = scanLogRepository
                .findLatestPerMonitorByWebsiteIds(List.of(websiteId))
                .stream()
                .collect(Collectors.toMap(sl -> sl.getMonitor().getId(), sl -> sl));

        Map<UUID, MonitorStatusOverride> overridesByMonitor = overrideRepository
                .findAllByWebsiteId(websiteId)
                .stream()
                .collect(Collectors.toMap(o -> o.getMonitor().getId(), o -> o));

        return buildHealthListItem(website, monitors, latestByMonitor, overridesByMonitor);
    }

    /**
     * Returns the most-recent {@link ScanLog} for each monitor belonging to
     * the given website, including the full {@code result_json} payload.
     *
     * <p>Uses the same single-round-trip {@code DISTINCT ON} query as
     * {@link #getHealthView} — no N+1.
     *
     * @throws WebsiteNotFoundException if no website with the given ID exists
     */
    @Transactional(readOnly = true)
    public List<LatestScanItem> getLatestScans(UUID websiteId) {
        websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));

        List<Monitor> monitors = monitorRepository.findByWebsiteId(websiteId);

        Map<UUID, ScanLog> latestByMonitor = scanLogRepository
                .findLatestPerMonitorByWebsiteId(websiteId)
                .stream()
                .collect(Collectors.toMap(
                        sl -> sl.getMonitor().getId(),
                        sl -> sl));

        Map<UUID, MonitorStatusOverride> overridesByMonitor = overrideRepository
                .findAllByWebsiteId(websiteId)
                .stream()
                .collect(Collectors.toMap(o -> o.getMonitor().getId(), o -> o));

        return monitors.stream()
                .map(m -> {
                    ScanLog latest = latestByMonitor.get(m.getId());
                    Instant nextScan = m.getLastRunAt() != null
                            ? m.getLastRunAt().plus(m.getIntervalMinutes(), java.time.temporal.ChronoUnit.MINUTES)
                            : null;

                    var ovr = ResolvedOverride.resolve(
                            m, latest, overridesByMonitor.get(m.getId()));
                    String effectiveStatus = resolveEffectiveStatusFromScanLog(
                            ovr.status(), m.getType().name(), m.getSubType().name(), ovr.severity(), latest);
                    String aiVerifiedTerm = null;
                    String aiVerificationReasoning = null;
                    if (latest != null && latest.getResultJson() != null) {
                        aiVerifiedTerm = (String) latest.getResultJson().get("ai_verified_term");
                        aiVerificationReasoning = (String) latest.getResultJson().get("ai_verification_reasoning");
                    }
                    boolean fastPathVerified = fastPathVerifiedFrom(latest);
                    boolean wafBlocked = isWafBlocked(latest);
                    return new LatestScanItem(
                            latest != null ? latest.getId() : null,
                            m.getId(),
                            m.getType().name(),
                            m.getSubType().name(),
                            ovr.status(),
                            effectiveStatus,
                            ovr.severity(),
                            latest != null ? latest.getCreatedAt() : null,
                            latest != null ? latest.getResultJson() : null,
                            m.getTargetUrl(),
                            latest != null && latest.getTriggerType() != null
                                    ? latest.getTriggerType().name() : null,
                            latest != null && latest.getTriggeredBy() != null
                                    ? latest.getTriggeredBy().getEmail() : null,
                            nextScan,
                            ovr.hasOverride(),
                            ovr.overrideMismatch(),
                            ovr.overrideInfo(),
                            latest != null && latest.getPreviousSeverity() != null
                                    ? latest.getPreviousSeverity().name() : null,
                            latest != null
                                    ? ScanStatusChange.compute(
                                    latest.getPreviousSeverity(), latest.getSeverity()).name()
                                    : null,
                            aiVerifiedTerm,
                            aiVerificationReasoning,
                            fastPathVerified,
                            wafBlocked
                    );
                })
                .toList();
    }

    /**
     * Convenience wrapper that extracts keyword-related fields from a {@link ScanLog}'s
     * result JSON and delegates to {@link #resolveEffectiveStatus}.
     */
    private static String resolveEffectiveStatusFromScanLog(
            String status, String type, String subType, String severity, ScanLog scanLog) {
        Map<String, Object> rj = scanLog != null ? scanLog.getResultJson() : null;
        boolean hasKeywordData = rj != null
                && (countJsonArrayField(rj, "found_keywords") > 0
                || countJsonArrayField(rj, "missing_keywords") > 0
                || countJsonArrayField(rj, "broken_keywords") > 0);
        boolean langIssue = rj != null
                && "true".equals(rj.get("language_inconsistency"))
                && !"true".equals(rj.get("accepted_via_language_preference"));
        int nativeFound = (rj != null && rj.containsKey("found_mandatory_native"))
                ? countJsonArrayField(rj, "found_mandatory_native")
                : countJsonArrayField(rj, "found_keywords");
        boolean langPrefAccepted = rj != null
                && "true".equals(rj.get("accepted_via_language_preference"));
        int englishFound = langPrefAccepted
                ? countJsonArrayField(rj, "english_found_keywords")
                  + countJsonArrayField(rj, "found_english_fallback")
                : 0;
        int foundCount = nativeFound + englishFound;
        return resolveEffectiveStatus(status, type, subType, severity, hasKeywordData, langIssue, foundCount);
    }

    /**
     * Returns the scan history for a website as an ordered list of {@link ScanBatch} objects.
     *
     * <p>Each batch groups scan logs that were triggered together:
     * <ul>
     *   <li>Modern scans (post batch_id migration): grouped by their shared {@code batch_id} UUID.</li>
     *   <li>Legacy scans (null {@code batch_id}): grouped by a synthetic 60-second time window
     *       ({@code floor(epoch_seconds / 60)}).</li>
     * </ul>
     *
     * <p>Reads recent scan logs for the website (ordered by {@code created_at DESC}),
     * groups them in Java, sorts by earliest timestamp DESC, and caps the result
     * to {@code nabu.scan-history.limit} (default 90).
     *
     * @param websiteId the target website
     * @return batches sorted newest-first, capped by the configured history limit
     * @throws WebsiteNotFoundException if no website with the given ID exists
     */
    @Transactional(readOnly = true)
    public List<ScanBatch> getScanHistory(UUID websiteId) {
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));

        int historyLimit = scanLimits.scanHistoryLimit();

        List<Monitor> monitors = monitorRepository.findByWebsiteId(websiteId);
        Map<UUID, Monitor> monitorMap = monitors.stream()
                .collect(Collectors.toMap(Monitor::getId, m -> m));
        long activeCount = monitors.stream().filter(Monitor::isActive).count();

        // Load overrides for override badge rendering in history items
        Map<UUID, MonitorStatusOverride> overridesByMonitor = overrideRepository
                .findAllByWebsiteId(websiteId)
                .stream()
                .collect(Collectors.toMap(o -> o.getMonitor().getId(), o -> o));

        int fetchLimit = Math.min(historyLimit * 10, 1000);
        List<ScanLog> recent = scanLogRepository.findRecentByWebsiteId(websiteId, fetchLimit);

        // Group logs by effective batch key, preserving insertion (newest-first) order.
        Map<String, List<ScanLog>> grouped = new LinkedHashMap<>();
        for (ScanLog sl : recent) {
            String key = sl.getBatchId() != null
                    ? sl.getBatchId().toString()
                    : "legacy-" + (sl.getCreatedAt().getEpochSecond() / 60);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(sl);
        }

        return grouped.entrySet().stream()
                .map(e -> {
                    List<ScanLog> logs = e.getValue();
                    Instant ts = logs.stream()
                            .map(ScanLog::getCreatedAt)
                            .min(Comparator.naturalOrder())
                            .orElseThrow();
                    String scanType = logs.size() >= activeCount && activeCount > 0 ? "FULL" : "PARTIAL";
                    List<LatestScanItem> items = logs.stream()
                            .map(sl -> {
                                Monitor m = monitorMap.get(sl.getMonitor().getId());
                                if (m == null) return null;
                                Instant nextScan = m.getLastRunAt() != null
                                        ? m.getLastRunAt().plus(m.getIntervalMinutes(), java.time.temporal.ChronoUnit.MINUTES)
                                        : null;
                                var resolved = ResolvedOverride.resolve(
                                        m, sl, overridesByMonitor.get(m.getId()));
                                String effective = resolveEffectiveStatusFromScanLog(
                                        resolved.status(), m.getType().name(), m.getSubType().name(),
                                        resolved.severity(), sl);
                                String slAiVerifiedTerm = null;
                                String slAiVerificationReasoning = null;
                                if (sl.getResultJson() != null) {
                                    slAiVerifiedTerm = (String) sl.getResultJson().get("ai_verified_term");
                                    slAiVerificationReasoning = (String) sl.getResultJson().get("ai_verification_reasoning");
                                }
                                boolean slFastPathVerified = fastPathVerifiedFrom(sl);
                                boolean slWafBlocked = isWafBlocked(sl);
                                return new LatestScanItem(
                                        sl.getId(),
                                        m.getId(),
                                        m.getType().name(),
                                        m.getSubType().name(),
                                        resolved.status(),
                                        effective,
                                        resolved.severity(),
                                        sl.getCreatedAt(),
                                        sl.getResultJson(),
                                        m.getTargetUrl(),
                                        sl.getTriggerType() != null
                                                ? sl.getTriggerType().name() : null,
                                        sl.getTriggeredBy() != null
                                                ? sl.getTriggeredBy().getEmail() : null,
                                        nextScan,
                                        resolved.hasOverride(),
                                        resolved.overrideMismatch(),
                                        resolved.overrideInfo(),
                                        sl.getPreviousSeverity() != null
                                                ? sl.getPreviousSeverity().name() : null,
                                        ScanStatusChange.compute(
                                                sl.getPreviousSeverity(), sl.getSeverity()).name(),
                                        slAiVerifiedTerm,
                                        slAiVerificationReasoning,
                                        slFastPathVerified,
                                        slWafBlocked
                                );
                            })
                            .filter(Objects::nonNull)
                            .toList();
                    // Derive batch-level trigger info from the first scan log in the group
                    String batchTriggerType = logs.stream()
                            .filter(sl -> sl.getTriggerType() != null)
                            .findFirst()
                            .map(sl -> sl.getTriggerType().name())
                            .orElse(null);
                    String batchTriggeredByEmail = logs.stream()
                            .filter(sl -> sl.getTriggeredBy() != null)
                            .findFirst()
                            .map(sl -> sl.getTriggeredBy().getEmail())
                            .orElse(null);
                    return new ScanBatch(e.getKey(), ts, scanType, items,
                            batchTriggerType, batchTriggeredByEmail);
                })
                .sorted(Comparator.comparing(ScanBatch::timestamp).reversed())
                .limit(historyLimit)
                .toList();
    }

    /**
     * Resolved state of a monitor's severity override. Captures whether an
     * override is active, whether it matches the current scan, and the
     * post-override severity/status values.
     */
    record ResolvedOverride(
            boolean hasOverride,
            boolean overrideMismatch,
            WebsiteHealthListItem.OverrideInfo overrideInfo,
            String severity,
            String status
    ) {
        static ResolvedOverride resolve(
                Monitor m, ScanLog latest, MonitorStatusOverride override) {
            String severity = latest != null && latest.getSeverity() != null
                    ? latest.getSeverity().name() : null;
            String status = m.isActive() ? monitorScanStatus(latest) : "INACTIVE";

            if (override == null) {
                return new ResolvedOverride(false, false, null, severity, status);
            }

            Severity currentSev = latest != null ? latest.getSeverity() : null;
            boolean mismatch = !override.matches(currentSev);
            var info = new WebsiteHealthListItem.OverrideInfo(
                    override.getBaselineSeverity().name(),
                    override.getOverrideSeverity().name(),
                    override.getOverriddenBy().getEmail(),
                    override.getOverriddenAt(),
                    override.getNote());

            if (!mismatch) {
                severity = override.getOverrideSeverity().name();
                status = m.isActive()
                        ? monitorScanStatusFromSeverity(override.getOverrideSeverity())
                        : "INACTIVE";
            }
            return new ResolvedOverride(true, mismatch, info, severity, status);
        }
    }

    /**
     * Builds a {@link WebsiteHealthView} for the given website.
     *
     * <p>Each monitor gets a pre-formatted human-readable {@code summary} string:
     * <ul>
     *   <li>SSL: "SSL certificate expires in 15 days" / "SSL certificate has EXPIRED"</li>
     *   <li>COMPLIANCE: "Compliant — all required links found" /
     *       "Non-compliant — missing: Impressum, Datenschutz"</li>
     *   <li>Any type: "Check failed: &lt;error&gt;" when the scan itself failed</li>
     *   <li>No data yet: "No scan data yet"</li>
     * </ul>
     *
     * @param websiteId the UUID of the website to evaluate
     * @return a fully populated {@link WebsiteHealthView}
     * @throws WebsiteNotFoundException if no website with the given ID exists
     */
    @Transactional(readOnly = true)
    public WebsiteHealthView getHealthView(UUID websiteId) {
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));

        List<Monitor> monitors = monitorRepository.findByWebsiteId(websiteId);

        // One round-trip — DISTINCT ON returns the latest ScanLog per monitor
        Map<UUID, ScanLog> latestByMonitor = scanLogRepository
                .findLatestPerMonitorByWebsiteId(websiteId)
                .stream()
                .collect(Collectors.toMap(
                        // Proxy ID access — Hibernate resolves this without an extra query
                        sl -> sl.getMonitor().getId(),
                        sl -> sl
                ));

        List<WebsiteHealthView.MonitorSummary> summaries = monitors.stream()
                .map(m -> {
                    ScanLog latest = latestByMonitor.get(m.getId());
                    return new WebsiteHealthView.MonitorSummary(
                            m.getId(),
                            m.getType(),
                            m.getSubType(),
                            latest != null ? latest.getSeverity()  : null,
                            latest != null ? latest.getCreatedAt() : null,
                            buildSummary(m.getType(), latest != null ? latest.getResultJson() : null)
                    );
                })
                .toList();

        HealthColor color = aggregateColor(summaries);

        log.debug("Health view evaluated: websiteId={} color={} monitors={}",
                websiteId, color, summaries.size());

        return new WebsiteHealthView(
                websiteId,
                website.getUrl(),
                website.getRegionCode(),
                color,
                summaries,
                Instant.now(),
                website.getComplianceGrade(),
                website.getComplianceScore()
        );
    }

    // ── color aggregation ────────────────────────────────────────────────────

    private static HealthColor aggregateColor(List<WebsiteHealthView.MonitorSummary> monitors) {
        boolean hasCritical = monitors.stream()
                .map(WebsiteHealthView.MonitorSummary::severity)
                .anyMatch(s -> s == Severity.CRITICAL);

        if (hasCritical) return HealthColor.RED;

        boolean hasElevated = monitors.stream()
                .map(WebsiteHealthView.MonitorSummary::severity)
                .anyMatch(s -> s == Severity.HIGH || s == Severity.MEDIUM);

        return hasElevated ? HealthColor.YELLOW : HealthColor.GREEN;
    }

    // ── human-readable summary builders ─────────────────────────────────────

    private String buildSummary(Monitor.MonitorType type, Map<String, Object> resultJson) {
        if (resultJson == null) {
            return "No scan data yet";
        }
        // "status" is overwritten to SUCCESS/FAILED by ScanResultProcessor
        boolean failed = "FAILED".equalsIgnoreCase((String) resultJson.get("status"));

        return switch (type) {
            case SSL        -> buildSslSummary(resultJson, failed);
            case COMPLIANCE -> buildComplianceSummary(resultJson, failed);
            default         -> failed ? "Check failed" : "Scan completed";
        };
    }

    private static String buildSslSummary(Map<String, Object> resultJson, boolean failed) {
        if (failed) {
            return "SSL check failed: " + resultJson.getOrDefault("error", "unknown error");
        }
        Object daysRaw = resultJson.get("days_remaining");
        if (daysRaw == null) return "SSL scan completed";
        try {
            long days = Math.round(Double.parseDouble(daysRaw.toString()));
            if (days <= 0) return "SSL certificate has EXPIRED";
            if (days == 1) return "SSL certificate expires in 1 day";
            return "SSL certificate expires in " + days + " days";
        } catch (NumberFormatException e) {
            return "SSL scan completed";
        }
    }

    private String buildComplianceSummary(Map<String, Object> resultJson, boolean failed) {
        if (failed) {
            return "Compliance check failed: " + resultJson.getOrDefault("error", "unknown error");
        }
        // missing_keywords and broken_keywords are JSON-encoded string arrays
        List<String> missing = parseStringArray(resultJson.get("missing_keywords"));
        List<String> broken  = parseStringArray(resultJson.get("broken_keywords"));

        if (missing.isEmpty() && broken.isEmpty()) {
            return "Compliant — all required links found";
        }

        StringBuilder sb = new StringBuilder("Non-compliant");
        if (!missing.isEmpty()) {
            sb.append(" — missing: ").append(String.join(", ", missing));
        }
        if (!broken.isEmpty()) {
            sb.append(missing.isEmpty() ? " — broken: " : "; broken: ")
              .append(String.join(", ", broken));
        }
        return sb.toString();
    }

    /**
     * Deserializes a value that was stored as a JSON-encoded string array,
     * e.g. {@code "[\"Impressum\",\"Datenschutz\"]"}.
     * Returns an empty list on null input, blank input, or parse failure.
     */
    private List<String> parseStringArray(Object raw) {
        if (raw == null) return Collections.emptyList();
        String s = raw.toString().strip();
        if (s.isEmpty() || s.equals("null") || s.equals("[]")) return Collections.emptyList();
        try {
            return objectMapper.readValue(s, STRING_LIST);
        } catch (Exception e) {
            log.warn("Failed to parse string array from resultJson value: '{}'", s);
            return Collections.emptyList();
        }
    }

    /**
     * Returns the full monitor configuration list for the given website.
     * Used by the Settings modal to let Admins inspect and edit per-monitor target URLs.
     *
     * @throws WebsiteNotFoundException if the website does not exist
     */
    // ── compliance trend ──────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<ComplianceTrendBucket> getComplianceTrend(
            UUID websiteId, String period, int months, String subType) {

        websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));

        Instant since = Instant.now().minus((long) months * 30, ChronoUnit.DAYS);

        List<Object[]> rows = scanLogRepository.findComplianceTrendRaw(
                websiteId, period, since, subType);

        return rows.stream().map(r -> {
            String bucket = (String) r[0];
            long total = ((Number) r[1]).longValue();
            long passing = ((Number) r[2]).longValue();
            long failing = ((Number) r[3]).longValue();
            BigDecimal passRate = total == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(passing * 100.0 / total)
                      .setScale(2, RoundingMode.HALF_UP);
            return new ComplianceTrendBucket(bucket, total, passing, failing, passRate);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<MonitorConfigItem> getMonitorConfigs(UUID websiteId) {
        if (!websiteRepository.existsById(websiteId)) {
            throw new WebsiteNotFoundException(websiteId);
        }
        return monitorRepository.findByWebsiteId(websiteId).stream()
                .map(MonitorConfigItem::from)
                .toList();
    }
}
