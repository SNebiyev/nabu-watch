package watch.nabu.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import watch.nabu.application.compliance.BannerKeywordPayload;
import watch.nabu.application.compliance.BannerKeywordService;
import watch.nabu.application.compliance.CompliancePathService;
import watch.nabu.application.compliance.ComplianceTaskBuilderService;
import watch.nabu.application.compliance.ComplianceTaskPayload;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.Website;
import watch.nabu.domain.model.WebsiteCompliancePath;
import watch.nabu.infrastructure.persistence.ScanLogRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes scan tasks onto the {@code nabu:scan-tasks} Redis Stream.
 *
 * <p>Each message represents exactly one monitor check — one specific legal
 * page (e.g. the Impressum page) or the SSL certificate.  For COMPLIANCE
 * monitors the message carries only the keywords relevant to that monitor's
 * {@link Monitor.SubType}, not the full keyword list for the region.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskProducer {

    /**
     * Standard-tier scan stream. Kept on the original key so historical
     * messages and existing consumer groups keep flowing after the
     * priority-queue split.
     */
    static final String STREAM_KEY = "nabu:scan-tasks";

    /**
     * Priority-tier scan stream. Tasks for verified users with a verified
     * website land here; the Go worker drains this stream before reading from
     * {@link #STREAM_KEY}.
     */
    static final String STREAM_KEY_PRIORITY = "nabu:scan-tasks:priority";

    private static final long FETCH_MODE_HINT_EXPIRY_DAYS = 7;

    /**
     * Number of recent scan samples fed into the baseline-median computation
     * (see {@link #appendBaselineLinksOnPage}).  Large enough to dilute a
     * single anomalous scan but small enough to reflect recent homepage
     * redesigns.
     */
    private static final int BASELINE_SAMPLE_SIZE = 20;

    /**
     * Minimum number of samples required before a baseline is trusted enough
     * to send to the worker.  Below this threshold the hint is omitted and
     * the worker falls back to its other WAF-shell heuristics.
     */
    private static final int BASELINE_MIN_SAMPLES = 3;

    /**
     * Minimum median link count below which no baseline hint is sent.  Tiny
     * homepages (&lt;10 links total) are too noisy to use as a reference —
     * a 70% drop from 8 to 2 is not a reliable block signal.
     */
    private static final int BASELINE_MIN_MEDIAN = 10;

    private final StringRedisTemplate redisTemplate;
    private final ComplianceTaskBuilderService complianceTaskBuilder;
    private final BannerKeywordService bannerKeywordService;
    private final ObjectMapper objectMapper;
    private final ScanLogRepository scanLogRepository;
    private final CompliancePathService compliancePathService;

    /**
     * Enqueues a scan task derived from a {@link Monitor}.
     *
     * @param monitor the monitor whose schedule has triggered a scan
     * @return the Redis Stream {@link RecordId} assigned to the message
     */
    public RecordId enqueue(Monitor monitor) {
        return enqueue(monitor, null, false);
    }

    public RecordId enqueue(Monitor monitor, UUID batchId) {
        return enqueue(monitor, batchId, false);
    }

    /**
     * Enqueues a scan task derived from a {@link Monitor} with a shared batch identifier.
     *
     * @param monitor the monitor whose schedule has triggered a scan
     * @param batchId shared batch UUID; {@code null} for scheduler-driven single-monitor tasks
     * @param isScout if true, marks this as the scout task (first homepage fetch in batch)
     * @return the Redis Stream {@link RecordId} assigned to the message
     */
    public RecordId enqueue(Monitor monitor, UUID batchId, boolean isScout) {
        Website website = monitor.getWebsite();

        Map<String, String> fields = new HashMap<>();
        fields.put("monitorId",   monitor.getId().toString());
        fields.put("websiteId",   website.getId().toString());
        fields.put("websiteUrl",  website.getUrl());
        fields.put("regionCode",  website.getRegionCode());
        fields.put("monitorType", monitor.getType().name());
        fields.put("subType",     monitor.getSubType().name());

        if (batchId != null) {
            fields.put("batchId", batchId.toString());
        }
        if (isScout) {
            fields.put("isScout", "true");
        }

        // Optional per-monitor scan target — worker uses this URL instead of websiteUrl when present.
        if (monitor.getTargetUrl() != null && !monitor.getTargetUrl().isBlank()) {
            fields.put("targetUrl", monitor.getTargetUrl());
        }

        // Per-website screenshot opt-in — Go worker captures a JPEG screenshot when true.
        fields.put("screenshotsEnabled", String.valueOf(website.isScreenshotsEnabled()));

        // COMPLIANCE tasks carry three keyword tiers built dynamically from the DB:
        //   requiredKeywords — mandatory native terms; finding ≥1 = PASSING (green)
        //   optionalKeywords — bonus-tier native terms; absence is not a warning
        //   fallbackKeywords — English equivalents; finding these triggers language_inconsistency
        if (monitor.getType() == Monitor.MonitorType.COMPLIANCE) {
            String region = website.getRegionCode();
            ComplianceTaskPayload payload = complianceTaskBuilder.buildPayload(region, monitor.getSubType());

            // Language preference adjusts which keyword tiers are sent to the scanner:
            //   ENGLISH_ONLY  → fallback becomes required, native tiers cleared
            //   BOTH_ACCEPTED → native + fallback merged into required (scanner finds either = pass)
            //   NATIVE_ONLY   → no change (default behaviour)
            Website.AcceptedLanguages langPref = website.getAcceptedLanguages();
            List<String> effectiveRequired;
            List<String> effectiveOptional;
            List<String> effectiveFallback;

            if (langPref == Website.AcceptedLanguages.ENGLISH_ONLY && !payload.fallbackKeywords().isEmpty()) {
                effectiveRequired = payload.fallbackKeywords();
                effectiveOptional = List.of();
                effectiveFallback = List.of();
            } else if (langPref == Website.AcceptedLanguages.BOTH_ACCEPTED && !payload.fallbackKeywords().isEmpty()) {
                // Merge native + English into required so scanner treats both as valid
                var merged = new java.util.ArrayList<>(payload.requiredKeywords());
                merged.addAll(payload.fallbackKeywords());
                effectiveRequired = List.copyOf(merged);
                effectiveOptional = payload.optionalKeywords();
                effectiveFallback = List.of(); // no separate fallback tier — all in required
            } else {
                effectiveRequired = payload.requiredKeywords();
                effectiveOptional = payload.optionalKeywords();
                effectiveFallback = payload.fallbackKeywords();
            }

            if (effectiveRequired.isEmpty()) {
                log.warn("No required keywords for regionCode={} subType={} — COMPLIANCE task enqueued without keywords",
                        region, monitor.getSubType());
            }
            putJsonArray(fields, "requiredKeywords", effectiveRequired, monitor.getId(), "requiredKeywords");
            putJsonArray(fields, "optionalKeywords", effectiveOptional, monitor.getId(), "optionalKeywords");
            putJsonArray(fields, "fallbackKeywords", effectiveFallback, monitor.getId(), "fallbackKeywords");

            if (langPref != Website.AcceptedLanguages.NATIVE_ONLY) {
                log.info("[task] {} mode: monitorId={} — required={} optional={} fallback={}",
                        langPref, monitor.getId(),
                        effectiveRequired.size(), effectiveOptional.size(), effectiveFallback.size());
            }
        }

        // COOKIE_BANNER: inject region-scoped banner keywords and AI hints.
        //   - Keywords come from the banner_keywords table and let the Go worker
        //     recognize non-English decline buttons (e.g. Portuguese "recusar")
        //     without a redeploy when new languages are added.
        //   - AI hints from the last AI-assisted scan let the Go worker verify
        //     them in the DOM before calling AI again.
        if (monitor.getSubType() == Monitor.SubType.COOKIE_BANNER) {
            appendBannerKeywords(website.getRegionCode(), monitor.getId(), fields);
            appendBannerAIHints(monitor.getId(), fields);
        }

        // COMPLIANCE (non-banner, non-targeted): inject fast-path + AI hints so
        // the Go worker can reach the previously-discovered compliance page
        // before falling back to a full homepage crawl. Path hints run first —
        // when a high/medium path is present, the scan never reaches the AI
        // block, so the AI-hint query is skipped to avoid a wasted round-trip.
        if (monitor.getType() == Monitor.MonitorType.COMPLIANCE
                && monitor.getSubType() != Monitor.SubType.COOKIE_BANNER
                && monitor.getTargetUrl() == null) {
            boolean pathInjected = appendCompliancePathHints(monitor, fields);
            if (!pathInjected) {
                appendComplianceAIHints(monitor.getId(), fields);
            }
            appendBaselineLinksOnPage(website.getId(), monitor.getId(), fields);
        }

        // Fetch-mode hint: when the website is known to require headless rendering,
        // tell the Go worker to skip the static HTTP attempt.  The hint expires
        // after 7 days to force periodic re-detection.
        if ("HEADLESS".equals(website.getPreferredFetchMode())
                && website.getFetchModeDetectedAt() != null
                && website.getFetchModeDetectedAt().isAfter(Instant.now().minus(FETCH_MODE_HINT_EXPIRY_DAYS, ChronoUnit.DAYS))) {
            fields.put("fetchModeHint", "HEADLESS");
        }

        String streamKey = chooseStream(website);
        RecordId recordId = redisTemplate.opsForStream()
            .add(StreamRecords.mapBacked(fields).withStreamKey(streamKey));

        log.info("Enqueued scan task: stream={} recordId={} monitorId={} type={} subType={} region={} targetUrl={} batchId={}",
            streamKey, recordId, monitor.getId(), monitor.getType(), monitor.getSubType(),
                website.getRegionCode(), monitor.getTargetUrl(), batchId);

        return recordId;
    }

    /**
     * Picks the priority stream when both the website and its owner are
     * verified; otherwise the standard stream.  Lazy-load failures on
     * {@code addedBy} (orphaned-website edge case) silently fall through to
     * the standard stream so verification routing never blocks a scan.
     */
    private static String chooseStream(Website website) {
        if (website.getVerificationStatus() != Website.VerificationStatus.VERIFIED) {
            return STREAM_KEY;
        }
        try {
            User owner = website.getAddedBy();
            if (owner != null && owner.getVerificationStatus() == User.VerificationStatus.VERIFIED) {
                return STREAM_KEY_PRIORITY;
            }
        } catch (RuntimeException ignored) {
            // LazyInitializationException or similar — keep scans flowing on standard.
        }
        return STREAM_KEY;
    }

    /**
     * Reads the most-recent scan result for a COMPLIANCE monitor and, if AI
     * previously discovered a compliance page URL, injects that URL and its
     * content hash as hints for the Go worker.  This lets the worker verify
     * the AI-found URL first (fast path) before falling back to a full
     * homepage crawl + AI discovery cycle.
     */
    private void appendComplianceAIHints(UUID monitorId, Map<String, String> fields) {
        scanLogRepository.findTopByMonitorIdOrderByCreatedAtDesc(monitorId)
                .map(ScanLog::getResultJson)
                .ifPresent(json -> {
                    Object viaObj = json.get("scanned_via");
                    if (!(viaObj instanceof String scanVia)) {
                        return;
                    }
                    boolean hasDiscovery = scanVia.contains("AI_DISCOVERY")
                            || scanVia.equals("AI_HINT_VERIFIED");
                    boolean hasSemantic = scanVia.contains("AI_SEMANTIC_VERIFIED")
                            || scanVia.contains("AI_SEMANTIC_CACHED");
                    if (!hasDiscovery && !hasSemantic) {
                        return;
                    }
                    if (hasDiscovery) {
                        Object url = json.get("ai_discovered_url");
                        Object hash = json.get("ai_discovered_content_hash");
                        if (url instanceof String s && !s.isBlank()) {
                            fields.put("aiHintDiscoveredURL", s);
                        }
                        if (hash instanceof String s && !s.isBlank()) {
                            fields.put("aiHintContentHash", s);
                        }
                    }
                    if (hasSemantic) {
                        Object verifiedHref = json.get("ai_verified_link_href");
                        Object verifiedHash = json.get("ai_verified_homepage_hash");
                        if (verifiedHref instanceof String href && !href.isBlank()
                                && verifiedHash instanceof String h && !h.isBlank()) {
                            fields.put("aiHintVerifiedLinkHref", href);
                            fields.put("aiHintVerifiedHomepageHash", h);
                        }
                    }
                    log.debug("Injected compliance AI hints for monitorId={}: scannedVia={}",
                            monitorId, scanVia);
                });
    }

    /**
     * Injects persisted compliance document path hints into the task fields so the
     * Go worker can verify the known URL first (fast path) before falling back to a
     * full homepage crawl + AI discovery cycle.
     *
     * <p>Only {@code high} or {@code medium} confidence paths are injected — a
     * {@code low} confidence path is not reliable enough to be worth the fast-path
     * attempt and would risk masking a fresh discovery.
     *
     * <p>Skips COOKIE_BANNER (handled by banner AI hints) and targeted-mode monitors
     * (where {@code targetUrl} is set and the worker is already pointing at the page).
     */
    private boolean appendCompliancePathHints(Monitor monitor, Map<String, String> fields) {
        WebsiteCompliancePath path = compliancePathService.findPath(
                monitor.getWebsite().getId(), monitor.getSubType().name());
        if (path == null || "low".equalsIgnoreCase(path.getConfidence())) {
            return false;
        }
        fields.put("pathAnchorHref", path.getAnchorHref());
        fields.put("pathDiscoveredURL", path.getDiscoveredUrl());
        log.debug("[task] Injected compliance path hints for monitorId={} subType={}: discoveredUrl={}",
                monitor.getId(), monitor.getSubType(), path.getDiscoveredUrl());
        return true;
    }

    /**
     * Queries the banner_keywords table for the website's region and injects the
     * four keyword lists into the task payload as JSON-encoded string arrays.
     * An empty payload (no rows for the region and no global fallbacks) is
     * silently skipped — the Go worker falls back to its hardcoded defaults.
     */
    private void appendBannerKeywords(String regionCode, UUID monitorId, Map<String, String> fields) {
        BannerKeywordPayload payload = bannerKeywordService.buildPayload(regionCode);
        if (payload.isEmpty()) {
            return;
        }
        putJsonArray(fields, "bannerPhrases", payload.bannerPhrases(), monitorId, "bannerPhrases");
        putJsonArray(fields, "bannerAcceptTerms", payload.acceptTerms(), monitorId, "bannerAcceptTerms");
        putJsonArray(fields, "bannerRejectTerms", payload.rejectTerms(), monitorId, "bannerRejectTerms");
        putJsonArray(fields, "bannerPrefTerms", payload.prefTerms(), monitorId, "bannerPrefTerms");
        log.debug("[task] injected banner keywords for monitorId={} region={}: phrases={} accept={} reject={} pref={}",
                monitorId, regionCode,
                payload.bannerPhrases().size(), payload.acceptTerms().size(),
                payload.rejectTerms().size(), payload.prefTerms().size());
    }

    /**
     * Injects a per-website homepage-link-count baseline into the task payload
     * so the Go worker can flag drastic drops as probable WAF blocks (and
     * return {@code ErrWAFStillBlocking} instead of a false NON_COMPLIANT
     * verdict).
     *
     * <p>Uses the <b>median</b> of the last {@link #BASELINE_SAMPLE_SIZE}
     * homepage-mode compliance scans for the website.  Median is robust to
     * one-off WAF-shell outliers that would otherwise skew the mean downward
     * and permanently lower the bar for WAF detection.
     *
     * <p>Silently omits the hint when the sample is too sparse or the median
     * too small to be a reliable reference — the worker's existing gates
     * (isUsableFetchResult, cache-skip guard) still apply.
     */
    private void appendBaselineLinksOnPage(UUID websiteId, UUID monitorId, Map<String, String> fields) {
        List<Integer> samples = scanLogRepository.findRecentLinksOnPageForWebsite(
                websiteId, BASELINE_SAMPLE_SIZE);
        if (samples.size() < BASELINE_MIN_SAMPLES) {
            return;
        }
        int median = medianOf(samples);
        if (median < BASELINE_MIN_MEDIAN) {
            return;
        }
        fields.put("baselineLinksOnPage", String.valueOf(median));
        log.debug("[task] Injected baseline links_on_page={} (samples={}) for monitorId={}",
                median, samples.size(), monitorId);
    }

    /**
     * Returns the median of a non-empty, unsorted list of integers.
     * For even-sized lists the lower of the two middle values is returned —
     * the hint is a conservative reference, not a precise average.
     */
    static int medianOf(List<Integer> values) {
        List<Integer> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private void putJsonArray(Map<String, String> fields, String fieldName, List<String> values,
                              UUID monitorId, String label) {
        if (values.isEmpty()) {
            return;
        }
        try {
            fields.put(fieldName, objectMapper.writeValueAsString(values));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialise {} for monitorId={} — task enqueued without this field",
                    label, monitorId, ex);
        }
    }

    /**
     * Reads the most-recent scan result for the given COOKIE_BANNER monitor and,
     * if it was compliant, copies the banner selector and button labels into the
     * task payload as hints for the Go worker.
     *
     * <p>Hints are injected from any compliant scan — not just AI-assisted ones.
     * A plain HEADLESS scan that found {@code [class*=privacy-banner]} is equally
     * valid as a fast-path seed for the next scan cycle.
     */
    private void appendBannerAIHints(UUID monitorId, Map<String, String> fields) {
        scanLogRepository.findTopByMonitorIdOrderByCreatedAtDesc(monitorId)
                .map(ScanLog::getResultJson)
                .filter(json -> {
                    String via = json.get("scanned_via") instanceof String s ? s : "";
                    String compliant = json.get("compliant") instanceof String s ? s : "";
                    return "AI_ASSISTED".equals(via)
                            || "AI_HINT_VERIFIED".equals(via)
                            || ("HEADLESS".equals(via) && "true".equals(compliant));
                })
                .ifPresent(json -> {
                    Object selector = json.get("banner_selector");
                    Object accept = json.get("accept_button_label");
                    Object reject = json.get("reject_button_label");
                    if (selector instanceof String s && !s.isBlank()) {
                        fields.put("aiHintSelector", s);
                    }
                    if (accept instanceof String s && !s.isBlank()) {
                        fields.put("aiHintAcceptLabel", s);
                    }
                    if (reject instanceof String s && !s.isBlank()) {
                        fields.put("aiHintRejectLabel", s);
                    }
                    log.debug("Injected AI hints for monitorId={}: selector={}, accept={}, reject={}",
                            monitorId, selector, accept, reject);
                });
    }
}
