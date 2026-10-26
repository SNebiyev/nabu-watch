package watch.nabu.application.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.compliance.CompliancePathService;
import watch.nabu.application.notification.NotificationService;
import watch.nabu.application.storage.FileStorageService;
import watch.nabu.domain.model.*;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.domain.model.ScanStatusChange;
import watch.nabu.infrastructure.persistence.*;
import watch.nabu.infrastructure.redis.ScanResultMessage;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ScanResultProcessor {

    // SSL severity thresholds (in days)
    private static final int CRITICAL_THRESHOLD = 7;
    private static final int HIGH_THRESHOLD     = 14;
    private static final int MEDIUM_THRESHOLD   = 30;

    private final MonitorRepository monitorRepository;
    private final ScanLogRepository scanLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final ScanBatchAggregatorPort scanBatchAggregator;
    private final ApplicationEventPublisher eventPublisher;
    private final FileStorageService fileStorageService;
    private final WebsiteRepository websiteRepository;
    private final MonitorStatusOverrideRepository overrideRepository;
    private final CompliancePathService compliancePathService;

    // ─── private helpers ──────────────────────────────────────────────────────

    private ScanLog persistScanLog(Monitor monitor, ScanResultMessage message, Severity severity,
                                   TriggerType triggerType, User triggeredBy) {
        // Enrich the stored JSON with top-level status/task_id for traceability
        Map<String, Object> resultJson = new HashMap<>(
            message.payload() != null ? message.payload() : Map.of()
        );
        resultJson.put("status",  message.status());
        resultJson.put("task_id", message.taskId());

        // keyword_evidence and english_evidence arrive as JSON-encoded strings
        // (Go map[string]string values).  Parse both into List<Map> so Hibernate
        // stores them as proper JSONB arrays rather than quoted strings.
        Object evidenceRaw = resultJson.get("keyword_evidence");
        if (evidenceRaw instanceof String evidenceStr) {
            resultJson.put("keyword_evidence", parseJsonList(evidenceStr));
        }
        Object enEvidenceRaw = resultJson.get("english_evidence");
        if (enEvidenceRaw instanceof String enEvidenceStr) {
            resultJson.put("english_evidence", parseJsonList(enEvidenceStr));
        }
        // AI content analysis fields arrive as JSON-encoded strings from Go.
        Object aiMissingRaw = resultJson.get("ai_missing_elements");
        if (aiMissingRaw instanceof String aiMissingStr) {
            resultJson.put("ai_missing_elements", parseJsonList(aiMissingStr));
        }
        Object aiIssuesRaw = resultJson.get("ai_content_issues");
        if (aiIssuesRaw instanceof String aiIssuesStr) {
            resultJson.put("ai_content_issues", parseJsonList(aiIssuesStr));
        }

        UUID batchId = null;
        if (message.batchId() != null && !message.batchId().isBlank()) {
            try {
                batchId = UUID.fromString(message.batchId());
            } catch (IllegalArgumentException ignored) {
            }
        }

        // previousSeverity = the immediately-preceding scan's severity.
        // Badges (IMPROVED / REGRESSED / RECOVERED / REGRESSED_TO_FAILING /
        // UNCHANGED) answer one question: "how does this scan differ from
        // the one right before it?" Repeated identical severities yield
        // UNCHANGED; a transition only flashes the relevant badge on the
        // single scan where it actually occurred, then drops back to
        // UNCHANGED on the next identical scan.
        //
        // NULL here means this is the monitor's first scan.
        Severity previousSeverity = scanLogRepository
                .findTopByMonitorIdOrderByCreatedAtDesc(monitor.getId())
                .map(ScanLog::getSeverity)
                .orElse(null);

        ScanLog scanLog = ScanLog.builder()
            .monitor(monitor)
            .resultJson(resultJson)
            .severity(severity)
                .previousSeverity(previousSeverity)
                .batchId(batchId)
                .triggerType(triggerType)
                .triggeredBy(triggeredBy)
            .build();

        ScanLog saved;
        try {
            saved = scanLogRepository.save(scanLog);
            log.info("ScanLog saved: id={} monitorId={} type={} subType={} status={} severity={}",
                saved.getId(), monitor.getId(), monitor.getType(), monitor.getSubType(),
                message.status(), severity);
        } catch (Exception ex) {
            log.error("JPA error persisting ScanLog: monitorId={} type={} subType={} cause={}",
                monitor.getId(), monitor.getType(), monitor.getSubType(), ex.getMessage(), ex);
            throw ex;  // re-throw so the @Transactional rolls back and ResultConsumer logs it
        }

        return saved;
    }

    private Severity resolveSeverity(ScanResultMessage message, MonitorType type, Monitor.SubType subType) {
        if (!message.isSuccess()) {
            return Severity.HIGH;
        }
        if (type == MonitorType.SSL) {
            return resolveSslSeverity(message.payload());
        }
        if (type == MonitorType.COMPLIANCE) {
            return resolveComplianceSeverity(message.payload(), subType);
        }
        return Severity.INFO;
    }

    /**
     * Determines compliance severity from the Go worker's payload.
     *
     * <p>The Go worker uses <b>OR-logic</b> for keyword matching: finding any
     * single synonym is enough to mark the page {@code COMPLIANT}.  Unfound
     * synonyms are still reported in {@code missing_keywords} for informational
     * purposes, but they do <b>not</b> indicate a compliance failure.
     *
     * <p>Severity is only elevated when the worker explicitly reports
     * {@code NON_COMPLIANT}, or when broken links / language issues are present.
     *
     * <p><b>Cookie Banner</b> is treated more strictly: a missing consent banner
     * is a direct GDPR/ePrivacy violation, so {@code NON_COMPLIANT} maps to
     * {@code HIGH} (FAILING) instead of {@code MEDIUM}.
     */
    private Severity resolveComplianceSeverity(Map<String, String> payload, Monitor.SubType subType) {
        if (payload == null) return Severity.INFO;
        boolean isNonCompliant = "NON_COMPLIANT".equalsIgnoreCase(payload.get("status"));
        boolean hasBroken = isNonEmptyJsonArray(payload.get("broken_keywords"));
        boolean langIssue = "true".equalsIgnoreCase(payload.get("language_inconsistency"));

        // AI content quality: score below 40 indicates poor/incomplete legal content.
        // The Go worker already sets status=NON_COMPLIANT for score < 40, but this
        // serves as a safety net if the status logic diverges.
        boolean lowContentQuality = false;
        String aiScore = payload.get("ai_content_score");
        if (aiScore != null) {
            try {
                lowContentQuality = Integer.parseInt(aiScore) < 40;
            } catch (NumberFormatException ignored) {
            }
        }

        if (isNonCompliant && subType == Monitor.SubType.COOKIE_BANNER) {
            return Severity.HIGH;
        }

        // Tracker detection: presence of trackers is informational only — the
        // site owner already knows which ones they ship via Tag Manager / their
        // CMP, so flagging them as MEDIUM was noise. Real risk signals
        // (malicious-domain cross-reference, supply-chain script-hash change,
        // unexpected new tracker since last scan) will elevate severity in a
        // follow-up; until then this scanner stays advisory.
        if (subType == Monitor.SubType.TRACKER_DETECTION) {
            return Severity.INFO;
        }

        return (isNonCompliant || hasBroken || langIssue || lowContentQuality) ? Severity.MEDIUM : Severity.INFO;
    }

    /**
     * Returns true when the value is a non-empty JSON array (not null, not []).
     */
    private static boolean isNonEmptyJsonArray(String jsonArray) {
        if (jsonArray == null) return false;
        String t = jsonArray.strip();
        return !t.isEmpty() && !t.equals("null") && !t.equals("[]");
    }

    private Severity resolveSslSeverity(Map<String, String> payload) {
        if (payload == null) return Severity.INFO;

        String daysStr = payload.get("days_remaining");
        if (daysStr == null) return Severity.INFO;

        try {
            double daysLeft = Double.parseDouble(daysStr);
            if (daysLeft < CRITICAL_THRESHOLD) return Severity.CRITICAL;
            if (daysLeft < HIGH_THRESHOLD)     return Severity.HIGH;
            if (daysLeft < MEDIUM_THRESHOLD)   return Severity.MEDIUM;
        } catch (NumberFormatException ignored) {
            log.warn("Could not parse days_remaining value: '{}'", daysStr);
        }

        return Severity.INFO;
    }

    private void logAlerts(Monitor monitor, ScanResultMessage message, Severity severity) {
        switch (severity) {
            case CRITICAL -> log.warn(
                // ⚠ FUTURE: trigger email/webhook alert here
                "CRITICAL ALERT — SSL certificate expiring imminently: " +
                "monitorId={} websiteId={} host={} daysRemaining={} expiry={}",
                monitor.getId(),
                monitor.getWebsite().getId(),         // safe: inside @Transactional
                payloadGet(message, "host"),
                payloadGet(message, "days_remaining"),
                payloadGet(message, "expiry")
            );
            case HIGH -> {
                if (!message.isSuccess()) {
                    log.warn(
                        "HIGH ALERT — scan FAILED: monitorId={} type={} error={}",
                        monitor.getId(), monitor.getType(),
                        payloadGet(message, "error")
                    );
                } else {
                    log.warn(
                        "HIGH ALERT — SSL certificate expiring soon: " +
                        "monitorId={} websiteId={} host={} daysRemaining={}",
                        monitor.getId(),
                        monitor.getWebsite().getId(),
                        payloadGet(message, "host"),
                        payloadGet(message, "days_remaining")
                    );
                }
            }
            default -> { /* INFO / LOW / MEDIUM — no extra alerting needed */ }
        }
    }

    /**
     * Processes one scan result message:
     * <ol>
     *   <li>Resolves the {@link Monitor} — returns empty if the monitor has been
     *       physically deleted (soft-deleted inactive monitors are still found and
     *       logged, since {@code findById} applies no {@code active} filter).</li>
     *   <li>Determines the {@link Severity} from the payload.</li>
     *   <li>Persists a new {@link ScanLog} entry.</li>
     *   <li>Updates {@code Monitor.lastRunAt}.</li>
     *   <li>Emits a warning log for critical issues (future: email/webhook alert).</li>
     * </ol>
     *
     * <p><b>No exception is thrown for a missing monitor.</b>  Instead this method
     * returns {@link Optional#empty()} and logs a WARN so that callers (e.g.
     * {@code ResultConsumer}) can decide whether to retry or discard without
     * breaking the consumer loop.
     *
     * @return a populated {@link ScanResultSummary} wrapped in an {@link Optional},
     *         or {@link Optional#empty()} if the monitor no longer exists in the DB
     */
    public Optional<ScanResultSummary> process(ScanResultMessage message) {
        // WAF/transient skips: no ScanLog, preserve previous state entirely.
        if ("SKIPPED_WAF".equalsIgnoreCase(message.status())
                || "SKIPPED_TRANSIENT".equalsIgnoreCase(message.status())) {
            log.info("Skipping {} result for monitor {} — previous state preserved",
                    message.status(), message.monitorId());
            return Optional.empty();
        }

        // Batch abort skips: write a FAILED ScanLog so the UI sees a fresh result
        // and stops showing "Processing...". The ScanLog stores the abort reason
        // so the drawer can display it. Flows through the normal process() path
        // after injecting synthetic payload fields.
        if ("SKIPPED_BATCH_ABORT".equalsIgnoreCase(message.status())) {
            log.info("Batch-abort skip for monitor {} — converting to FAILED ScanLog",
                    message.monitorId());
            // Mark batch as aborted so notification includes WAF info
            if (message.batchId() != null && !message.batchId().isBlank()) {
                scanBatchAggregator.markAborted(UUID.fromString(message.batchId()));
            }
            // Build a synthetic payload that the normal process() path can handle
            var syntheticPayload = new java.util.HashMap<>(message.payload() != null
                    ? message.payload() : java.util.Map.<String, String>of());
            syntheticPayload.put("status", "FAILED");
            syntheticPayload.put("severity", "HIGH");
            syntheticPayload.putIfAbsent("error", "Batch scan aborted: site blocked by firewall");
            syntheticPayload.putIfAbsent("error_code", "BATCH_ABORTED");
            // Replace the message with a FAILED one so normal processing writes a ScanLog
            message = new ScanResultMessage(
                    message.taskId(), message.monitorId(), "FAILED",
                    syntheticPayload, message.publishedAt(), message.batchId(), message.rawDom());
            // Fall through to normal processing below
        }

        UUID monitorId = parseMonitorId(message.monitorId());

        // JpaRepository.findById has no implicit 'WHERE active = true' filter,
        // so inactive / soft-deleted monitors are still resolved and their results
        // persisted — only a physical DELETE causes an empty Optional here.
        Monitor monitor = monitorRepository.findById(monitorId).orElse(null);
        if (monitor == null) {
            log.warn("Received result for unknown or deleted monitor: {}. Skipping.", monitorId);
            return Optional.empty();
        }

        log.info("Saving ScanLog for monitor: {}", monitorId);

        Severity severity = resolveSeverity(message, monitor.getType(), monitor.getSubType());

        // ── Language preference override ─────────────────────────────────────
        // When language_inconsistency is flagged but the website owner accepts
        // English results, downgrade MEDIUM → INFO and mark the override.
        boolean langOverrideApplied = false;
        if (severity == Severity.MEDIUM
                && monitor.getType() == MonitorType.COMPLIANCE
                && "true".equalsIgnoreCase(message.payload() != null ? message.payload().get("language_inconsistency") : null)
                && monitor.getWebsite().getAcceptedLanguages().acceptsEnglish()) {
            severity = Severity.INFO;
            langOverrideApplied = true;
            log.info("[scan] Language preference override: monitorId={} severity MEDIUM→INFO (English accepted)",
                    monitorId);
        }

        // ── Resolve trigger audit info from the batch metadata in Redis ──────
        TriggerType triggerType = TriggerType.SCHEDULED; // default for non-batched / old scans
        User triggeredBy = null;
        UUID parsedBatchId = null;
        if (message.batchId() != null && !message.batchId().isBlank()) {
            try {
                parsedBatchId = UUID.fromString(message.batchId());
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (parsedBatchId != null) {
            var triggerInfo = scanBatchAggregator.getBatchTriggerInfo(parsedBatchId);
            if (triggerInfo.isPresent()) {
                var info = triggerInfo.get();
                triggerType = "MANUAL".equals(info.triggerType())
                        ? TriggerType.MANUAL : TriggerType.SCHEDULED;
                if (info.userId() != null && triggerType == TriggerType.MANUAL) {
                    triggeredBy = userRepository.findById(info.userId()).orElse(null);
                }
            }
        }

        // Inject language override flag into the message payload so it's persisted in resultJson
        if (langOverrideApplied && message.payload() != null) {
            message.payload().put("accepted_via_language_preference", "true");
        }

        ScanLog scanLog = persistScanLog(monitor, message, severity, triggerType, triggeredBy);

        // Update the persisted compliance path store from this scan result.
        // Must run after persistScanLog so the ScanLog ID is available in the
        // transaction, but before monitor.lastRunAt is updated (ordering is not
        // strictly required — both participate in the same @Transactional).
        updateCompliancePath(monitor, message.payload());
        updateWebsiteFetchMode(monitor, message.payload());

        monitor.setLastRunAt(Instant.now());
        monitorRepository.save(monitor);

        // monitor.website.organization is safe to traverse inside the active
        // @Transactional context (LAZY joins are still live).
        UUID orgId = monitor.getWebsite().getOrganization().getId();

        // ── Recalculate compliance grade ─────────────────────────────────────
        recalculateGrade(monitor.getWebsite());

        // ── Screenshot file metadata ─────────────────────────────────────────
        // Present whenever the Go worker actually captured a screenshot
        // (indicated by screenshot_key in the payload).
        if (message.payload() != null && message.payload().containsKey("screenshot_key")) {
            // Persist file metadata so the screenshot can be served via FileController.
            // The Go worker already uploaded the JPEG to MinIO; we just record it here.
            String screenshotKey = message.payload().get("screenshot_key");
            try {
                StoredFile stored = fileStorageService.registerExternal(
                        orgId,
                        StoredFile.EntityType.SCAN_LOG,
                        scanLog.getId(),
                        monitor.getSubType().name().toLowerCase() + "_screenshot.jpg",
                        screenshotKey,
                        "image/jpeg"
                );
                // Inject the stored file ID into resultJson so the frontend can build
                // the /api/v1/files/{fileId} URL directly from the scan result.
                scanLog.getResultJson().put("screenshot_file_id", stored.getId().toString());
                scanLogRepository.save(scanLog);
            } catch (Exception e) {
                log.warn("Failed to register screenshot metadata for scanLog={}: {}",
                        scanLog.getId(), e.getMessage());
            }
        }

        // Notification routing:
        //   • Manual rescan (batchId present) — accumulate into the aggregator.
        //     When the last result for the batch arrives, emit one SCAN_SUMMARY
        //     notification instead of N individual per-monitor notifications.
        //   • Scheduled scan (no batchId) — keep the existing per-monitor notification.
        if (scanLog.getBatchId() != null) {
            final UUID websiteId = monitor.getWebsite().getId();
            final String websiteUrl = monitor.getWebsite().getUrl();
            // Compute statusChange for the notification summary using the previousSeverity
            // captured earlier (before persistScanLog) so it reflects the pre-save state.
            ScanStatusChange statusChange = ScanStatusChange.compute(
                    scanLog.getPreviousSeverity(), severity);
            scanBatchAggregator.recordResult(
                            scanLog.getBatchId(), monitor.getSubType(), severity, statusChange)
                    .ifPresent(summary -> {
                        notificationService.sendScanSummaryNotification(summary);
                        // Signal all connected SSE clients that this website's aggregate
                        // status has changed.  The @TransactionalEventListener(AFTER_COMMIT)
                        // in ScanStreamController guarantees the DB is fully consistent before
                        // the event is broadcast.
                        eventPublisher.publishEvent(
                                new WebsiteBatchCompleteEvent(this, websiteId, websiteUrl, orgId));
                    });
        } else {
            // Scheduled scan — notify all organization members.
            notificationService.sendScanCompletedToOrg(
                    orgId,
                    monitor.getWebsite().getId(), monitor.getWebsite().getUrl(),
                    severity, monitor.getSubType());
        }

        logAlerts(monitor, message, severity);

        // Unconditionally signal that this website's status may have changed.
        // This fires for every scan (scheduled + manual), ensuring SSE-connected
        // dashboards always receive a website-updated event for real-time updates.
        eventPublisher.publishEvent(
                new WebsiteStatusChangedEvent(this,
                        monitor.getWebsite().getId(), monitor.getWebsite().getUrl(),
                        orgId,
                        monitor.getWebsite().getComplianceGrade(),
                        monitor.getWebsite().getComplianceScore()));

        // Extract all data while still in-transaction (monitor.website is LAZY but live here)
        return Optional.of(new ScanResultSummary(
            scanLog.getId(),
            monitor.getId(),
            monitor.getType(),
            monitor.getSubType(),
            monitor.getWebsite().getId(),
            monitor.getWebsite().getUrl(),
                orgId,
            severity,
            scanLog.getCreatedAt()
        ));
    }

    private static String payloadGet(ScanResultMessage message, String key) {
        return message.payload() != null ? message.payload().getOrDefault(key, "n/a") : "n/a";
    }

    private static UUID parseMonitorId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid monitorId in scan result: '" + raw + "'", e);
        }
    }

    /**
     * Recalculates the website's compliance grade from the latest scan results
     * across all its monitors. Called after every ScanLog persist so the grade
     * reflects the most up-to-date state.
     */
    private void recalculateGrade(Website website) {
        try {
            List<Monitor> monitors = monitorRepository.findByWebsiteId(website.getId());
            Map<UUID, ScanLog> latestByMonitor = scanLogRepository
                    .findLatestPerMonitorByWebsiteId(website.getId())
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(
                            sl -> sl.getMonitor().getId(), sl -> sl));

            // Collect active overrides so the grade calculator can use the
            // overridden severity when the baseline matches the current scan.
            Map<UUID, MonitorStatusOverride> overridesByMonitor = overrideRepository
                    .findAllByWebsiteId(website.getId())
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(
                            o -> o.getMonitor().getId(), o -> o));

            var result = ComplianceGradeCalculator.calculate(monitors, latestByMonitor, overridesByMonitor);
            website.setComplianceGrade(result.grade());
            website.setComplianceScore(result.score());
            websiteRepository.save(website);

            log.debug("Compliance grade updated: websiteId={} score={} grade={}",
                    website.getId(), result.score(), result.grade());
        } catch (Exception e) {
            log.warn("Failed to recalculate compliance grade for websiteId={}: {}",
                    website.getId(), e.getMessage());
        }
    }

    /**
     * Updates the persisted compliance path store after a scan result is committed.
     *
     * <p>Three possible outcomes based on payload fields from the Go worker:
     * <ol>
     *   <li><b>Fast-path success</b> — {@code scanned_via} contains {@code "FAST_PATH"}:
     *       refreshes {@code last_verified_at} and resets {@code fail_count}.</li>
     *   <li><b>Fast-path invalidation</b> — {@code path_fast_path_result} equals
     *       {@code "invalidated"}: increments {@code fail_count} and auto-deletes at 3.</li>
     *   <li><b>New path discovery</b> — {@code path_anchor_href}, {@code path_anchor_text},
     *       {@code path_discovered_url}, {@code path_source}, and {@code path_confidence}
     *       are all present: upserts the row (confidence-rank gated).</li>
     * </ol>
     *
     * <p>COOKIE_BANNER is excluded — it is handled by the banner-specific AI hint pipeline.
     */
    private void updateCompliancePath(Monitor monitor, Map<String, String> payload) {
        if (monitor.getSubType() == Monitor.SubType.COOKIE_BANNER) {
            return;
        }
        if (payload == null) {
            return;
        }

        UUID websiteId = monitor.getWebsite().getId();
        String subType = monitor.getSubType().name();

        String scannedVia = payload.get("scanned_via");
        String fastPathResult = payload.get("path_fast_path_result");

        if (scannedVia != null && scannedVia.contains("FAST_PATH")) {
            compliancePathService.recordFastPathSuccess(websiteId, subType);
            return;
        }

        String anchorHref = payload.get("path_anchor_href");
        String anchorText = payload.get("path_anchor_text");
        String discoveredUrl = payload.get("path_discovered_url");
        String source = payload.get("path_source");
        String confidence = payload.get("path_confidence");

        boolean hasNewPath = anchorHref != null && anchorText != null && discoveredUrl != null
                && source != null && confidence != null;

        // Fast-path invalidation WITHOUT a new discovery → increment fail_count only.
        // If the full scan discovered a new path, prefer upserting it: the new finding
        // supersedes the stale one (confidence-rank gate in the UPSERT still protects
        // against weaker overwrites), and a successful upsert resets fail_count to 0.
        if ("invalidated".equalsIgnoreCase(fastPathResult) && !hasNewPath) {
            compliancePathService.recordFastPathFailure(websiteId, subType);
            return;
        }

        if (hasNewPath) {
            String domSelector = payload.get("path_dom_selector");
            compliancePathService.upsertFromScan(
                    websiteId, subType, discoveredUrl,
                    anchorHref, anchorText, domSelector,
                    source, confidence);
        }
    }

    /**
     * Persists the detected fetch mode on the website so subsequent scans can
     * skip the static HTTP attempt for JS-heavy sites.
     *
     * <p>Only COMPLIANCE homepage-mode scans contribute (they are the ones that
     * go through the static→headless escalation chain). SSL scans and targeted
     * compliance scans are excluded.
     */
    private void updateWebsiteFetchMode(Monitor monitor, Map<String, String> payload) {
        if (monitor.getType() != MonitorType.COMPLIANCE || payload == null) {
            return;
        }
        // Targeted-mode scans fetch a specific URL, not the homepage — their
        // scanned_via does not reflect the homepage's rendering requirements.
        if (monitor.getTargetUrl() != null && !monitor.getTargetUrl().isBlank()) {
            return;
        }

        String scannedVia = payload.get("scanned_via");
        if (scannedVia == null) {
            return;
        }

        // Fast-path scans (STATIC+FAST_PATH, HEADLESS+FAST_PATH) are verification-
        // only — they confirm a known link, not discover the fetch mode from scratch.
        // Skip them so we don't overwrite a real detection with a fast-path signal.
        if (scannedVia.contains("FAST_PATH")) {
            return;
        }

        String detectedMode = scannedVia.contains("HEADLESS") ? "HEADLESS" : null;
        Website website = monitor.getWebsite();

        if (!Objects.equals(detectedMode, website.getPreferredFetchMode())) {
            website.setPreferredFetchMode(detectedMode);
            website.setFetchModeDetectedAt(Instant.now());
            log.info("[scan] Fetch mode updated: websiteId={} mode={}",
                    website.getId(), detectedMode != null ? detectedMode : "AUTO");
        } else if (website.getFetchModeDetectedAt() == null
                || website.getFetchModeDetectedAt().isBefore(Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS))) {
            website.setFetchModeDetectedAt(Instant.now());
        }
    }

    /**
     * Parses a JSON array string into a {@link List} for proper JSONB storage.
     * Returns an empty list on null, blank, or unparseable input — never throws.
     */
    @SuppressWarnings("unchecked")
    private List<Object> parseJsonList(String json) {
        if (json == null || json.isBlank() || "null".equals(json) || "[]".equals(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("Failed to parse keyword_evidence JSON array: '{}'", json);
            return List.of();
        }
    }
}
