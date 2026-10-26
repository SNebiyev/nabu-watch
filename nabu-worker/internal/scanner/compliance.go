package scanner

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// FoundLink records a compliance link discovered on the page.
type FoundLink struct {
	Keyword string `json:"keyword"`
	Text    string `json:"text"`
	Href    string `json:"href"`
	Status  int    `json:"status"` // HTTP status of verification; 0 = network failure
	Broken  bool   `json:"broken"`
}

// KeywordEvidence records how and where a required keyword was detected.
// Stored as a JSON array in result_json["keyword_evidence"] for frontend display.
//
// Score weights (additive):
//   - text node (targeted mode)          = 1
//   - <a> link on homepage               = 5
//   - <a> inside <footer>                = 7  (footer_link)
//   - found via Level-1 deep crawl       = 3  (deep_link)
type KeywordEvidence struct {
	Keyword         string `json:"keyword"`
	Score           int    `json:"score"`
	Location        string `json:"location"`           // "link" | "footer_link" | "text" | "deep_link"
	EvidenceContext string `json:"evidence_context"`   // ~100-char window around the match
	FoundAt         string `json:"found_at,omitempty"` // sub-page URL when found via deep crawl
}

// ComplianceScanner checks whether a website contains the mandatory legal
// disclosure links required by its regional jurisdiction.
//
// Two scan modes are supported, selected by the presence of Task.TargetURL:
//
// Homepage mode (Task.TargetURL == ""):
//  1. Fetch the website homepage.
//  2. Parse all <a href> tags with goquery.
//  3. For each required keyword, find candidate links whose text or href
//     contains the keyword (case-insensitive).
//  4. Verify each candidate with a HEAD request (falls back to GET on 405).
//  5. Classify keywords as found / broken / missing.
//
// Targeted mode (Task.TargetURL != ""):
//  1. Fetch the explicit target page directly — no link discovery.
//  2. Search the full page text for each keyword (case-insensitive).
//  3. Classify keywords as found / missing (broken N/A — no link checks).
//  4. Compute a SHA-256 hash of the normalised page text.
//  5. Compare against Task.PreviousContentHash to detect content changes.
type ComplianceScanner struct {
	headless   *HeadlessAllocator
	cache      *HomepageCrawlCache
	contentAI  *complianceContentAnalyzer
	testClient *http.Client // non-nil only in tests; bypasses buildCrawlClient + SSRF dial hook
}

func NewComplianceScanner(headless *HeadlessAllocator) *ComplianceScanner {
	return &ComplianceScanner{
		headless:  headless,
		cache:     newHomepageCrawlCache(5 * time.Minute),
		contentAI: newComplianceContentAnalyzer(),
	}
}

// newComplianceScannerWithClient creates a ComplianceScanner that uses the
// provided HTTP client for all outbound requests instead of building one with
// buildCrawlClient.  Intended for unit tests that need to point the scanner at
// an httptest.Server (which listens on 127.0.0.1, a private address that the
// production ssrfDialContext transport would block).
func newComplianceScannerWithClient(headless *HeadlessAllocator, client *http.Client) *ComplianceScanner {
	return &ComplianceScanner{
		headless:   headless,
		cache:      newHomepageCrawlCache(5 * time.Minute),
		testClient: client,
	}
}

func (c *ComplianceScanner) Type() string { return "COMPLIANCE" }

func (c *ComplianceScanner) Scan(ctx context.Context, task Task) (*Result, error) {

	if len(task.RequiredKeywords) == 0 {
		slog.Warn("Compliance scan skipped — no required keywords in task",
			"monitor_id", task.MonitorID,
			"region", task.RegionCode,
		)
		return &Result{
			MonitorID: task.MonitorID,
			Type:      c.Type(),
			Success:   true,
			ScannedAt: time.Now().UTC(),
			BatchID:   task.BatchID,
			Details: map[string]string{
				"warning": "no required_keywords provided for region " + task.RegionCode,
			},
		}, nil
	}

	// ── robots.txt compliance check ──────────────────────────────────────
	// Respect Disallow rules before scanning.  The check targets the URL we
	// are about to scan (homepage or targetURL).  Fail-open: if robots.txt
	// cannot be fetched, the scan proceeds normally.
	checkURL := task.WebsiteURL
	if task.TargetURL != "" {
		checkURL = task.TargetURL
	}
	if !CheckRobotsTxt(ctx, checkURL) {
		slog.Info("Scan skipped — disallowed by robots.txt",
			"monitor_id", task.MonitorID,
			"url", checkURL,
		)
		return &Result{
			MonitorID: task.MonitorID,
			Type:      c.Type(),
			Success:   true,
			ScannedAt: time.Now().UTC(),
			BatchID:   task.BatchID,
			Details: map[string]string{
				"skipped":     "true",
				"skip_reason": "robots_txt_disallowed",
				"url":         checkURL,
			},
		}, nil
	}

	var client *http.Client
	if c.testClient != nil {
		client = c.testClient
	} else {
		var buildErr error
		client, buildErr = buildCrawlClient(task.ProxyURL, task.RegionCode)
		if buildErr != nil {
			return nil, fmt.Errorf("build http client: %w", buildErr)
		}
	}

	// Session warm-up: perform a brief GET to capture Set-Cookie headers
	// (lang, locale, GDPR consent) so subsequent requests are served the
	// correct regional version of the site instead of a generic redirect.
	warmUpSession(ctx, client, task.WebsiteURL)

	if task.TargetURL != "" {
		return c.scanTargetedPage(ctx, client, task)
	}
	return c.scanHomepage(ctx, client, task)
}

// ─── homepage mode ────────────────────────────────────────────────────────────

func (c *ComplianceScanner) scanHomepage(ctx context.Context, client *http.Client, task Task) (*Result, error) {
	// Fast-path: if a previously discovered compliance link is known, verify it
	// directly on the homepage and return COMPLIANT without a full scan.
	var fastPathMissed bool
	var reusableFR *FetchResult // headless doc from fast-path miss, avoids duplicate Chrome spawn
	if task.PathAnchorHref != "" && task.SubType != "COOKIE_BANNER" {
		loc := GetLocalization(task.RegionCode)
		loc.HTTPProxy = "" // fast-path uses direct egress only — proxy reserved for WAF fallback
		result, fallbackFR, ok := c.fastPathScan(ctx, client, task, loc)
		if ok {
			return result, nil
		}
		reusableFR = fallbackFR
		fastPathMissed = true
	}

	scanStart := time.Now()
	const hardResetAiBudget = 20 * time.Second // reserve this much for AI paths after escalations

	loc := GetLocalization(task.RegionCode)

	// ── AI hint verification (fast path) ─────────────────────────────────────
	// If a previous AI-assisted scan discovered a compliance page URL, try it
	// first before the full homepage crawl.  This mirrors the Cookie Banner hint
	// pattern: AI finds the URL once → Go worker verifies on subsequent scans.
	if task.AIHintDiscoveredURL != "" && task.SubType != "COOKIE_BANNER" {
		if hintResult, ok := c.verifyComplianceHint(ctx, client, task, loc); ok {
			return hintResult, nil
		}
		// Hint verification failed — fall through to normal homepage scan.
		slog.Info("AI hint verification failed — falling back to normal homepage scan",
			"monitor_id", task.MonitorID,
			"hint_url", task.AIHintDiscoveredURL,
		)
	}

	// ── HomepageCrawlCache lookup ─────────────────────────────────────────────
	// Within a manual-rescan batch every monitor for the same domain calls
	// scanHomepage independently.  The domainSerializer ensures they run
	// sequentially, so an in-memory cache is safe without distributed locking.
	// On a CACHE HIT we re-parse the cached raw HTML (~2 ms) instead of
	// spawning a new headless tab (~45 s), saving the majority of scan time
	// for batches with ≥2 compliance monitors on the same domain.
	domain := apexDomain(task.WebsiteURL)
	key := cacheKey(task.BatchID, domain)

	var fr FetchResult

	// Reuse the headless document from a fast-path miss to avoid a duplicate
	// Chrome spawn — the fast-path already fetched the fully rendered DOM.
	// Skips the cache lookup intentionally: the reusable doc is fresher than
	// any cached entry and avoids a misleading +CACHED tag.
	if reusableFR != nil {
		fr = *reusableFR
		slog.Info("Reusing headless DOM from fast-path miss",
			"monitor_id", task.MonitorID,
			"url", task.WebsiteURL,
			"scanned_via", fr.ScannedVia,
		)
	} else if cached, ok := c.cache.get(key); ok {
		doc, err := cached.reparse()
		if err == nil {
			fr = FetchResult{
				StatusCode: cached.statusCode,
				Doc:        doc,
				ScannedVia: cached.scannedVia + "+CACHED",
			}
			slog.Info("HomepageCrawlCache HIT — skipping live fetch",
				"monitor_id", task.MonitorID,
				"url", task.WebsiteURL,
				"key", key,
				"scanned_via", fr.ScannedVia,
			)
		}
	}

	if fr.Doc == nil {
		// Cache miss (or reparse error) — perform a live fetch.
		fetchCtx, fetchCancel := context.WithTimeout(ctx, fetchTimeout)
		defer fetchCancel()

		var err error
		fr, err = hybridFetch(fetchCtx, client, c.headless, task.WebsiteURL, loc, task.FetchModeHint)
		if err != nil {
			errMsg := err.Error()
			if isTransientHeadlessError(errMsg) {
				slog.Warn("ComplianceScanner: transient fetch error — preserving previous state",
					"monitor_id", task.MonitorID,
					"url", task.WebsiteURL,
					"error", errMsg,
				)
				return nil, fmt.Errorf("compliance fetch failed (transient): %w", ErrTransientHeadless)
			}
			return complianceFailResult(task, fr.StatusCode, fmt.Errorf("fetch homepage: %w", err)), nil
		}

		// WAF validation: if all bypass tiers (static → headless mobile → hard-reset
		// desktop) still produced a block page, abort now.  Reporting NON_COMPLIANT
		// based on an Akamai "Access Denied" page is a false negative — the real
		// site content was never reached.
		//
		// We check the page title separately (often the clearest signal) plus the
		// full body text via the shared isBlockPage helper.
		if pageTitle := strings.ToLower(fr.Doc.Find("title").Text()); isBlockTitle(pageTitle) || isBlockPage(extractBodyText(fr.Doc)) {
			// Last resort: retry with residential proxy via headless directly.
			// We intentionally bypass hybridFetch here — all direct-egress tiers
			// (static, headless, FlareSolverr) have already failed above.  Calling
			// hybridFetch would re-run those tiers and waste ~30s before reaching
			// its own attemptProxyRetry.  Instead, call attemptProxyRetry directly
			// to go straight to headless+proxy.
			proxyURL := GetLocalization(task.RegionCode).HTTPProxy
			proxyBypassed := false
			if proxyFr, ok := attemptProxyRetry(fetchCtx, c.headless, task.WebsiteURL, loc, proxyURL, "compliance waf block"); ok {
				slog.Info("Proxy retry succeeded — WAF bypassed",
					"monitor_id", task.MonitorID,
					"url", task.WebsiteURL,
				)
				fr = proxyFr
				proxyBypassed = true
			} else if proxyURL != "" {
			}
			if !proxyBypassed {
				slog.Warn("Homepage still returns WAF block page after all bypass attempts — aborting scan",
					"monitor_id", task.MonitorID,
					"url", task.WebsiteURL,
					"scanned_via", fr.ScannedVia,
					"title", fr.Doc.Find("title").Text(),
				)
				return nil, ErrWAFStillBlocking
			}
		}
		// NOTE: cache population is intentionally deferred to the END of the
		// pipeline (after all escalation passes) so the cache always stores the
		// richest available DOM.  Populating here — before the headless escalation
		// block below — would poison the cache with a zero-link STATIC page and
		// cause every subsequent monitor in the batch to fail on a cache hit.
	}

	links := extractAllLinks(fr.Doc, task.WebsiteURL)
	slog.Info("Links extracted from homepage",
		"monitor_id", task.MonitorID,
		"url", task.WebsiteURL,
		"count", len(links),
	)

	// Historical baseline sanity check: when the producer supplied a median
	// link count from recent scans, treat a drastic drop (current < 30% of
	// baseline) as a probable WAF interstitial that slipped past the primary
	// shell-detection gates.  Returning ErrWAFStillBlocking preserves the
	// previous monitor state rather than emitting a false NON_COMPLIANT.
	// The threshold uses integer math (10*current < 3*baseline ⇔ current/baseline < 0.3).
	if task.BaselineLinksOnPage > 0 && len(links) > 0 && 10*len(links) < 3*task.BaselineLinksOnPage {
		slog.Warn("Homepage link count far below baseline — treating as WAF block",
			"monitor_id", task.MonitorID,
			"url", task.WebsiteURL,
			"baseline", task.BaselineLinksOnPage,
			"current", len(links),
			"scanned_via", fr.ScannedVia,
		)
		return nil, ErrWAFStillBlocking
	}

	if len(links) == 0 {
		slog.Warn("Homepage has no navigable links — all keywords marked missing",
			"monitor_id", task.MonitorID,
			"url", task.WebsiteURL,
		)
	}

	// ── Build effective keyword list ─────────────────────────────────────────
	// Priority: mandatory native → optional native → English fallback.
	// This ensures native terms are checked first, with optional variants and
	// English synonyms acting as additional candidates in a single scan pass.
	//
	// Example FR PRIVACY_POLICY effective list:
	//   ["Politique de Confidentialité",           ← mandatory native
	//    "Données personnelles", "RGPD",            ← optional native
	//    "Privacy Policy", "Privacy Notice"]        ← English fallback
	effectiveKeywords := mergeUnique(task.RequiredKeywords, mergeUnique(task.OptionalKeywords, task.FallbackKeywords))

	// Discovery v2: scored compliance check with per-keyword evidence
	cr := checkComplianceV2(ctx, client, links, effectiveKeywords, task.RequiredKeywords)

	// Smart escalation: static HTML missed mandatory native keywords (e.g. large news sites
	// returns valid article HTML so hybridFetch never triggers headless on its own).
	// Gate on the mandatory native tier only — finding a fallback English synonym
	// on the static page does NOT prove the native-language term is absent; the
	// site may simply use JS-rendered navigation that the static parser missed.
	if fr.ScannedVia == "STATIC" &&
		(!mandatoryTierSatisfied(task.RequiredKeywords, cr.FoundKeywords) || len(links) == 0) &&
		c.headless != nil && c.headless.Available() {
		slog.Info("Static fetch missed keywords — escalating to headless multi-stage",
			"monitor_id", task.MonitorID,
			"url", task.WebsiteURL,
			"missing", cr.MissingKeywords,
		)
		hCtx, hCancel := context.WithTimeout(ctx, fetchTimeout)
		defer hCancel()
		hStatus, hDoc, hErr := c.headless.FetchPage(hCtx, task.WebsiteURL, loc, "")
		if hErr == nil {
			fr = FetchResult{StatusCode: hStatus, Doc: hDoc, ScannedVia: "HEADLESS"}
			links = extractAllLinks(fr.Doc, task.WebsiteURL)
			cr = checkComplianceV2(ctx, client, links, effectiveKeywords, task.RequiredKeywords)
			slog.Info("Headless escalation complete",
				"monitor_id", task.MonitorID,
				"url", task.WebsiteURL,
				"links", len(links),
				"missing_after", cr.MissingKeywords,
			)
		} else {
			// Headless blocked by WAF (e.g. Akamai hard-reset still blocked).
			// Escalate to FlareSolverr + residential proxy before giving up —
			// otherwise the scout sees WAF-masked static HTML and aborts the
			// whole batch (affecting every monitor type for the domain).
			proxyURL := GetLocalization(task.RegionCode).HTTPProxy
			if fbFr, ok := attemptAllFallbacks(hCtx, c.headless, task.WebsiteURL, loc, proxyURL, "compliance headless escalation fail"); ok {
				fr = fbFr
				links = extractAllLinks(fr.Doc, task.WebsiteURL)
				cr = checkComplianceV2(ctx, client, links, effectiveKeywords, task.RequiredKeywords)
				slog.Info("Headless escalation recovered via fallback",
					"monitor_id", task.MonitorID,
					"url", task.WebsiteURL,
					"scanned_via", fr.ScannedVia,
					"links", len(links),
				)
			} else {
				slog.Warn("Headless escalation failed — keeping static result",
					"monitor_id", task.MonitorID,
					"url", task.WebsiteURL,
					"error", hErr,
				)
			}
		}
	}

	// ── Scout page assessment ────────────────────────────────────────────────
	// Runs AFTER headless escalation so that SPA sites (which return empty
	// content via static fetch) get a chance to render before the scout
	// decides whether the page is scannable.
	var scoutDiscoveredLinks []discoveredLink
	if task.IsScout && len(links) == 0 && c.contentAI.Available() &&
		time.Since(scanStart) < 50*time.Second {
		bodyText := extractBodyText(fr.Doc)
		assessment, aiErr := c.contentAI.assessPage(ctx, bodyText, task.WebsiteURL, task.RegionCode)
		if aiErr != nil {
			slog.Warn("Scout AI assessment failed — continuing normal scan",
				"monitor_id", task.MonitorID, "error", aiErr)
		} else if !assessment.Scannable {
			abortDetails := map[string]string{
				"status":                "FAILED",
				"error":                 fmt.Sprintf("Scout: page not scannable (%s)", assessment.Reason),
				"batch_abort_reason":    assessment.Reason,
				"batch_abort_reasoning": assessment.Reasoning,
				"scanned_via":           fr.ScannedVia,
			}
			r := &Result{
				MonitorID: task.MonitorID,
				Type:      "COMPLIANCE",
				Success:   false,
				Details:   abortDetails,
				ScannedAt: time.Now().UTC(),
				BatchID:   task.BatchID,
			}
			return r, ErrBatchAborted
		} else {
			scoutDiscoveredLinks = assessment.DiscoveredLinks
		}
	}

	// Level-1 deep crawl: for each keyword still missing, try linked sub-pages
	// whose hrefs match known legal-content patterns.
	if len(cr.MissingKeywords) > 0 {
		promoted := make(map[string]struct{})
		deepCrawlFailed := make(map[string]struct{})
		for _, missingKW := range cr.MissingKeywords {
			if ev := deepCrawlMissing(ctx, client, links, missingKW, deepCrawlFailed); ev != nil {
				cr.FoundKeywords = append(cr.FoundKeywords, missingKW)
				cr.Evidence = append(cr.Evidence, *ev)
				promoted[missingKW] = struct{}{}
			}
		}
		if len(promoted) > 0 {
			var stillMissing []string
			for _, kw := range cr.MissingKeywords {
				if _, ok := promoted[kw]; !ok {
					stillMissing = append(stillMissing, kw)
				}
			}
			cr.MissingKeywords = stillMissing
		}
	}

	// Level-2 text-node fallback: keywords still missing after deep crawl may
	// exist as plain text on the page (e.g. inside <button> labels, headings,
	// or visible paragraph text) without an <a href> link.  A score-1 evidence
	// entry is created so the frontend can surface the partial match.
	if len(cr.MissingKeywords) > 0 {
		pageText := extractBodyText(fr.Doc)
		textPromoted := 0
		var stillMissing []string
		for _, kw := range cr.MissingKeywords {
			if containsWholeWord(pageText, kw) {
				textPromoted++
				idx := strings.Index(strings.ToLower(pageText), strings.ToLower(kw))
				snippet := extractSnippet(pageText, kw, idx)
				cr.FoundKeywords = append(cr.FoundKeywords, kw)
				cr.Evidence = append(cr.Evidence, KeywordEvidence{
					Keyword:         kw,
					Score:           1,
					Location:        "text_fallback",
					EvidenceContext: snippet,
					FoundAt:         task.WebsiteURL,
				})
				slog.Info("Text-node fallback promoted keyword",
					"monitor_id", task.MonitorID,
					"keyword", kw,
					"url", task.WebsiteURL,
				)
			} else {
				stillMissing = append(stillMissing, kw)
			}
		}
		cr.MissingKeywords = stillMissing
	}

	// Level-3 hard-reset desktop escalation: if the mobile headless pass left
	// mandatory native keywords still missing, retry with a fresh browser context
	// and a random desktop UA.  Some WAFs (Akamai EdgeAuth) apply stricter rate
	// limits to mobile/Safari traffic on first contact but loosen after a gap —
	// the 5-second pre-nav delay inside FetchPageHardReset exploits that window.
	// Only triggered when:
	//   • there ARE mandatory native keywords to satisfy (no-op for regions/subTypes
	//     where RequiredKeywords is empty — the compliance check would pass anyway)
	//   • the mobile headless pass ran (ScannedVia == "HEADLESS") — not for cache
	//     hits, not for the initial STATIC pass (that uses the first escalation above)
	if len(task.RequiredKeywords) > 0 &&
		(!mandatoryTierSatisfied(task.RequiredKeywords, cr.FoundKeywords) || len(links) == 0) &&
		fr.ScannedVia == "HEADLESS" &&
		c.headless != nil && c.headless.Available() {

		if time.Since(scanStart) > (60*time.Second - hardResetAiBudget) {
			slog.Info("Skipping hard_reset_escalation to reserve budget for AI",
				"monitor_id", task.MonitorID,
				"elapsed_ms", time.Since(scanStart).Milliseconds(),
			)
		} else {
			slog.Info("Headless mobile missed mandatory keywords — escalating to hard-reset desktop",
				"monitor_id", task.MonitorID,
				"url", task.WebsiteURL,
				"missing_mandatory", cr.MissingKeywords,
			)
			hrCtx, hrCancel := context.WithTimeout(ctx, fetchTimeout)
			defer hrCancel()
			hrStatus, hrDoc, hrErr := c.headless.FetchPageHardReset(hrCtx, task.WebsiteURL, loc)
			if hrErr == nil {
				fr = FetchResult{StatusCode: hrStatus, Doc: hrDoc, ScannedVia: "HEADLESS_DESKTOP"}
				links = extractAllLinks(fr.Doc, task.WebsiteURL)
				cr = checkComplianceV2(ctx, client, links, effectiveKeywords, task.RequiredKeywords)
				slog.Info("Hard-reset desktop escalation complete",
					"monitor_id", task.MonitorID,
					"url", task.WebsiteURL,
					"links", len(links),
					"missing_after", cr.MissingKeywords,
				)
			} else {
				slog.Warn("Hard-reset desktop escalation failed — keeping headless result",
					"monitor_id", task.MonitorID,
					"url", task.WebsiteURL,
					"error", hrErr,
				)
			}
		}
	}

	// ── Tiered keyword classification ─────────────────────────────────────────
	// Build membership sets for each tier. After all scan passes (static,
	// headless, deep crawl, text-node), every keyword in cr.FoundKeywords
	// belongs to exactly one tier:
	//   mandatory native  → sets the GREEN threshold (≥1 found = PASSING)
	//   optional native   → boosts confidence score; absence is NOT a warning
	//   English fallback  → PASSING but sets language_inconsistency = true
	nativeSet := make(map[string]bool, len(task.RequiredKeywords))
	for _, kw := range task.RequiredKeywords {
		nativeSet[kw] = true
	}
	optionalSet := make(map[string]bool, len(task.OptionalKeywords))
	for _, kw := range task.OptionalKeywords {
		optionalSet[kw] = true
	}
	fallbackSet := make(map[string]bool, len(task.FallbackKeywords))
	for _, kw := range task.FallbackKeywords {
		fallbackSet[kw] = true
	}

	var foundMandatoryNative, foundOptionalNative, foundFallback []string
	for _, kw := range cr.FoundKeywords {
		switch {
		case nativeSet[kw]:
			foundMandatoryNative = append(foundMandatoryNative, kw)
		case optionalSet[kw]:
			foundOptionalNative = append(foundOptionalNative, kw)
		case fallbackSet[kw]:
			foundFallback = append(foundFallback, kw)
		}
	}

	// missingMandatory: only mandatory native keywords that were not found.
	// Optional keywords that are missing are silently ignored — their absence
	// does not constitute a compliance gap.
	var missingMandatory []string
	for _, kw := range cr.MissingKeywords {
		if nativeSet[kw] {
			missingMandatory = append(missingMandatory, kw)
		}
	}

	// Collect fallback keywords not found (for the language-inconsistency panel).
	var missingFallback []string
	foundFallbackSet := make(map[string]bool, len(foundFallback))
	for _, kw := range foundFallback {
		foundFallbackSet[kw] = true
	}
	for _, kw := range task.FallbackKeywords {
		if !foundFallbackSet[kw] {
			missingFallback = append(missingFallback, kw)
		}
	}

	// OR-based compliance: finding ≥1 mandatory native keyword OR ≥1 English
	// fallback synonym satisfies the threshold.  Optional keywords alone are
	// never sufficient — they are bonus evidence, not compliance proof.
	compliant := len(foundMandatoryNative) > 0 || len(foundFallback) > 0
	status := "NON_COMPLIANT"
	if compliant {
		status = "COMPLIANT"
	}

	// If mandatory native terms were found, English fallbacks were not required.
	// Suppress them from "missing" so the UI does not confusingly display
	// "Missing: Privacy Policy" alongside "Found: Datenschutzerklärung".
	if len(foundMandatoryNative) > 0 {
		missingFallback = nil
	}

	// ── AI link discovery fallback ───────────────────────────────────────────
	// When the Go scanner could not find any compliance page (neither mandatory
	// native nor English fallback keywords matched), ask AI to analyze the
	// homepage links and identify the correct URL.  If AI finds a page, fetch
	// it, verify keywords, and upgrade the result — same as Cookie Banner's
	// "AI never downgrades" rule.
	var aiDiscoveredURL string
	var aiDiscoveredHash string
	var aiScannedVia string
	var aiContentAnalysis *complianceContentAnalysis
	// aiVerifiedTerm and friends are declared here (rather than just before the
	// semantic-verification block) because the high-confidence AI discovery path
	// also writes to them so both paths share the same details-population code.
	var aiVerifiedTerm string
	var aiVerifiedLinkHref string
	var aiVerifiedHomepageHash string
	var aiVerifiedReasoning string
	if !compliant && task.SubType != "COOKIE_BANNER" &&
		c.contentAI != nil && c.contentAI.Available() {

		slog.Info("ComplianceAI: all Go tiers failed — attempting AI link discovery",
			"monitor_id", task.MonitorID,
			"sub_type", task.SubType,
			"region", task.RegionCode,
		)
		linkSummaries := make([]linkSummary, 0, len(links))
		for _, l := range links {
			linkSummaries = append(linkSummaries, linkSummary{Text: l.Text, Href: l.Href})
		}
		// Use a fresh context for AI calls — the parent ctx may be near expiry
		// after multiple escalation passes (static + headless + hard-reset).
		aiCtx, aiCtxCancel := context.WithTimeout(context.Background(), 30*time.Second)
		discovery, aiErr := c.contentAI.discoverComplianceLink(aiCtx, linkSummaries, task.SubType, task.RegionCode, task.WebsiteURL)
		aiCtxCancel()
		if aiErr != nil {
			slog.Warn("ComplianceAI: link discovery failed",
				"monitor_id", task.MonitorID,
				"error", aiErr,
			)
		} else if !discovery.Found || discovery.URL == "" || discovery.Confidence == "low" {
			slog.Info("ComplianceAI: link discovery found no suitable candidate",
				"monitor_id", task.MonitorID,
				"found", discovery.Found,
				"confidence", discovery.Confidence,
				"reasoning", discovery.Reasoning,
			)
		} else if discovery.Found && discovery.URL != "" && discovery.Confidence != "low" {
			// AI found a candidate URL.
			slog.Info("ComplianceAI: discovered candidate URL",
				"monitor_id", task.MonitorID,
				"url", discovery.URL,
				"confidence", discovery.Confidence,
			)

			if discovery.Confidence == "high" {
				// High-confidence path: trust AI's verdict WITHOUT requiring literal
				// keyword match in the body.  The bank case shows that the ToS page
				// body may not contain literal native keywords (e.g. "Termos de Uso")
				// even though the URL is correct.  Fetching + re-matching is a
				// dead-end and wastes the remaining time budget.
				//
				// Instead, find the link in the homepage link slice by href match and
				// use its anchor text as the synthetic "equivalent term" — same
				// approach as verifyLinkSemantics (AI semantic verification path).
				var linkText string
				for _, l := range links {
					if l.Href == discovery.URL || strings.Contains(l.Href, discovery.URL) || strings.Contains(discovery.URL, l.Href) {
						linkText = l.Text
						break
					}
				}
				if linkText == "" {
					// URL not in extracted links (possible redirect / normalisation
					// difference) — use the last path segment as a minimal label.
					parts := strings.Split(strings.TrimRight(discovery.URL, "/"), "/")
					linkText = parts[len(parts)-1]
					if linkText == "" {
						linkText = discovery.URL
					}
				}

				// Mark the synthetic term as a mandatory-native match so the
				// compliance logic sees a found mandatory keyword.
				foundMandatoryNative = append(foundMandatoryNative, linkText)
				missingMandatory = nil
				aiDiscoveredURL = discovery.URL
				aiScannedVia = fr.ScannedVia + "+AI_DISCOVERY"
				// Store as verified term so the existing "AI semantic verification
				// fields" block in details population picks them up (same fields,
				// same Java consumer contract). aiVerifiedLinkHref is required for
				// selectComplianceLinkPath() to recognise this as a high-confidence
				// AI_DISCOVERY path — without it the selector falls through to the
				// medium-confidence branch and stores the path as medium.
				aiVerifiedTerm = linkText
				aiVerifiedLinkHref = discovery.URL
				aiVerifiedReasoning = discovery.Reasoning

				compliant = true
				status = "COMPLIANT"

				slog.Info("ComplianceAI: high-confidence discovery trusted without body re-match",
					"monitor_id", task.MonitorID,
					"discovered_url", discovery.URL,
					"link_text", linkText,
				)
			} else {
				// Medium-confidence path: fetch discovered URL and require literal
				// keyword match in the body before upgrading (existing behaviour).
				aiPageCtx, aiPageCancel := context.WithTimeout(context.Background(), fetchTimeout)
				aiFr, aiFetchErr := hybridFetch(aiPageCtx, client, c.headless, discovery.URL, loc)
				aiPageCancel()

				if aiFetchErr == nil {
					aiPageText := extractBodyText(aiFr.Doc)
					aiFoundKWs, _ := matchKeywordsInText(aiPageText, task.RequiredKeywords)

					if len(aiFoundKWs) > 0 {
						// AI discovery confirmed — upgrade result.
						aiDiscoveredURL = discovery.URL
						aiDiscoveredHash = hashContent(aiPageText)
						aiScannedVia = fr.ScannedVia + "+AI_DISCOVERY"

						// Add the AI-discovered keywords to the found lists.
						for _, kw := range aiFoundKWs {
							if nativeSet[kw] {
								foundMandatoryNative = append(foundMandatoryNative, kw)
							} else if optionalSet[kw] {
								foundOptionalNative = append(foundOptionalNative, kw)
							} else if fallbackSet[kw] {
								foundFallback = append(foundFallback, kw)
							}
							cr.FoundKeywords = append(cr.FoundKeywords, kw)
							cr.Evidence = append(cr.Evidence, KeywordEvidence{
								Keyword:         kw,
								Score:           4,
								Location:        "ai_discovery",
								EvidenceContext: extractSnippet(aiPageText, kw, strings.Index(strings.ToLower(aiPageText), strings.ToLower(kw))),
								FoundAt:         discovery.URL,
							})
						}

						// Remove AI-found keywords from missing.
						aiFoundSet := make(map[string]bool, len(aiFoundKWs))
						for _, kw := range aiFoundKWs {
							aiFoundSet[kw] = true
						}
						var newMissingMandatory []string
						for _, kw := range missingMandatory {
							if !aiFoundSet[kw] {
								newMissingMandatory = append(newMissingMandatory, kw)
							}
						}
						missingMandatory = newMissingMandatory

						// Re-evaluate compliance.
						compliant = len(foundMandatoryNative) > 0 || len(foundFallback) > 0
						if compliant {
							status = "COMPLIANT"
						}

						// ── AI content analysis on discovered page ──────────────
						// The page was fetched and keywords confirmed — now evaluate
						// whether the legal content is actually complete (same logic
						// as targeted mode).
						if compliant && c.contentAI.Available() && task.SubType != "COOKIE_BANNER" {
							aiAnalysisCtx, aiAnalysisCancel := context.WithTimeout(context.Background(), 30*time.Second)
							aiResult, aiAnalysisErr := c.contentAI.analyze(aiAnalysisCtx, aiPageText, task.SubType, task.RegionCode)
							aiAnalysisCancel()
							if aiAnalysisErr != nil {
								slog.Warn("ComplianceAI: content analysis failed on discovered page",
									"monitor_id", task.MonitorID,
									"error", aiAnalysisErr,
								)
							} else {
								aiContentAnalysis = aiResult
								if aiResult.Score < 40 {
									status = "NON_COMPLIANT"
									compliant = false
								}
							}
						}

						slog.Info("ComplianceAI: link discovery upgraded result",
							"monitor_id", task.MonitorID,
							"discovered_url", discovery.URL,
							"found_keywords", aiFoundKWs,
							"status", status,
						)
					} else {
						slog.Info("ComplianceAI: discovered URL did not contain expected keywords",
							"monitor_id", task.MonitorID,
							"url", discovery.URL,
						)
					}
				} else {
					slog.Warn("ComplianceAI: failed to fetch discovered URL",
						"monitor_id", task.MonitorID,
						"url", discovery.URL,
						"error", aiFetchErr,
					)
				}
			}
		}
	}

	// ── AI semantic link verification ────────────────────────────────────────
	// When Go rule-based scanning left us NON_COMPLIANT but we have candidate
	// links on the page, ask AI whether any link text is a semantically
	// equivalent alternative name for the required document type.
	// Example: "Diretiva de Privacidade" (BR) ≈ "Política de Privacidade".
	//
	// Caching: if a previous scan recorded a verified link href + homepage hash,
	// and the homepage links blob hasn't changed, trust the prior verdict and
	// skip the AI call entirely (AI_SEMANTIC_CACHED).
	// (aiVerifiedTerm / aiVerifiedLinkHref / aiVerifiedHomepageHash / aiVerifiedReasoning
	// are declared above, before the AI link discovery block.)

	if !compliant && task.SubType != "COOKIE_BANNER" &&
		c.contentAI != nil && c.contentAI.Available() &&
		len(cr.FoundLinks) > 0 && len(missingMandatory) > 0 {

		var currentLinksHash string

		// Cache-hit path: only build the links blob + hash when a hint is
		// present. On a first scan or a cache miss this saves the full-links
		// string allocation + SHA-256 pass.
		if task.AIHintVerifiedLinkHref != "" && task.AIHintVerifiedHomepageHash != "" {
			currentLinksHash = hashContent(homepageLinksBlob(links))
			if task.AIHintVerifiedHomepageHash == currentLinksHash {
				// Blob is byte-identical, so the verified href is guaranteed
				// to still be present. Look it up to recover its display text.
				for _, l := range cr.FoundLinks {
					if l.Href == task.AIHintVerifiedLinkHref {
						aiVerifiedTerm = l.Text
						aiVerifiedLinkHref = l.Href
						aiVerifiedHomepageHash = currentLinksHash
						aiVerifiedReasoning = "cached verdict from previous scan"

						foundMandatoryNative = append(foundMandatoryNative, aiVerifiedTerm)
						missingMandatory = nil
						compliant = true
						status = "COMPLIANT"
						aiScannedVia = appendScanTag(aiScannedVia, fr.ScannedVia, ScannedViaAISemanticCached)
						slog.Info("ComplianceAI: semantic verification cache hit",
							"monitor_id", task.MonitorID,
							"verified_link", aiVerifiedLinkHref,
							"term", aiVerifiedTerm,
						)
						break
					}
				}
			}
		}

		// Live AI call path — hint absent, stale, or keyword set changed so
		// the hinted link is no longer in FoundLinks.
		if !compliant {
			// Pick the first FoundLink as the most relevant candidate (FoundLinks
			// is already sorted by scoring in the compliance result builder).
			candidate := cr.FoundLinks[0]
			aiCtx, aiCtxCancel := context.WithTimeout(context.Background(), 30*time.Second)
			verdict, semanticErr := c.contentAI.verifyLinkSemantics(
				aiCtx,
				candidate.Text,
				candidate.Href,
				task.SubType,
				task.RegionCode,
				task.RequiredKeywords,
			)
			aiCtxCancel()

			if semanticErr != nil {
				slog.Warn("ComplianceAI: semantic link verification failed",
					"monitor_id", task.MonitorID,
					"error", semanticErr,
				)
			} else if verdict.Verified && verdict.Confidence != confidenceLow {
				if currentLinksHash == "" {
					currentLinksHash = hashContent(homepageLinksBlob(links))
				}
				aiVerifiedTerm = verdict.EquivalentTerm
				aiVerifiedLinkHref = candidate.Href
				aiVerifiedHomepageHash = currentLinksHash
				aiVerifiedReasoning = verdict.Reasoning

				foundMandatoryNative = append(foundMandatoryNative, aiVerifiedTerm)
				missingMandatory = nil
				compliant = true
				status = "COMPLIANT"
				aiScannedVia = appendScanTag(aiScannedVia, fr.ScannedVia, ScannedViaAISemanticVerified)
				slog.Info("ComplianceAI: semantic verification upgraded result",
					"monitor_id", task.MonitorID,
					"term", verdict.EquivalentTerm,
					"confidence", verdict.Confidence,
					"link_href", candidate.Href,
				)
			} else {
				reason := "verified=false"
				if verdict != nil {
					reason = fmt.Sprintf("verified=%v confidence=%s", verdict.Verified, verdict.Confidence)
				}
				slog.Info("ComplianceAI: semantic verification did not upgrade result",
					"monitor_id", task.MonitorID,
					"link_text", candidate.Text,
					"reason", reason,
				)
			}
		}
	}

	languageInconsistency := len(foundMandatoryNative) == 0 && len(foundFallback) > 0 && len(task.FallbackKeywords) > 0
	if languageInconsistency {
		slog.Info("Language inconsistency detected (inline — no English URL fetch needed)",
			"monitor_id", task.MonitorID,
			"region", task.RegionCode,
			"native_missing", task.RequiredKeywords,
			"english_found", foundFallback,
		)
	}

	// ── Discovered URL (Auto-Discovery enrichment) ───────────────────────────
	// Surface the URL of the first compliance page we actually confirmed as
	// reachable from the homepage. Useful for the frontend to show "checked
	// https://example.com/datenschutz" instead of just a generic pass/fail.
	//
	// Selection criteria (in priority order):
	//   1. "link" or "footer_link" evidence — direct <a href> on homepage (best)
	//   2. "deep_link" evidence             — found on a Level-1 sub-page
	//   3. "text_fallback" evidence is excluded — FoundAt = homepage URL (not useful)
	discoveredURL := ""
	for _, ev := range cr.Evidence {
		if ev.FoundAt == "" {
			continue
		}
		if ev.Location == "text_fallback" {
			continue
		}
		// Skip on-page modal triggers; they are not navigable URLs.
		if strings.HasPrefix(ev.FoundAt, "#") ||
			strings.HasPrefix(strings.ToLower(ev.FoundAt), "javascript:") {
			continue
		}
		discoveredURL = ev.FoundAt
		break
	}

	// ── Build result payload ──────────────────────────────────────────────────
	// found_keywords   = mandatory + optional native found (NOT English fallback).
	// missing_keywords = mandatory native only (optional absent is not actionable).
	// Tiered fields are added alongside the existing ones for backwards
	// compatibility with older result rows already stored in the database.
	combinedFound := append(append([]string{}, foundMandatoryNative...), foundOptionalNative...)

	foundKWJSON, _ := json.Marshal(combinedFound)
	brokenKWJSON, _ := json.Marshal(cr.BrokenKeywords)
	missingKWJSON, _ := json.Marshal(missingMandatory)
	foundLinksJSON, _ := json.Marshal(cr.FoundLinks)
	evidenceJSON, _ := json.Marshal(cr.Evidence)
	foundMandatoryJSON, _ := json.Marshal(foundMandatoryNative)
	foundOptionalJSON, _ := json.Marshal(foundOptionalNative)
	foundFallbackJSON, _ := json.Marshal(foundFallback)
	missingMandatoryJSON, _ := json.Marshal(missingMandatory)

	details := map[string]string{
		"mode":             "homepage",
		"discovered_url":   discoveredURL,
		"status":           status,
		"found_keywords":   string(foundKWJSON),
		"broken_keywords":  string(brokenKWJSON),
		"missing_keywords": string(missingKWJSON),
		"found_links":      string(foundLinksJSON),
		"keyword_evidence": string(evidenceJSON),
		// total_required = mandatory + optional (not fallback; they're checked separately)
		"total_required":         strconv.Itoa(len(task.RequiredKeywords) + len(task.OptionalKeywords)),
		"total_found":            strconv.Itoa(len(combinedFound)),
		"total_broken":           strconv.Itoa(len(cr.BrokenKeywords)),
		"total_missing":          strconv.Itoa(len(missingMandatory)),
		"links_on_page":          strconv.Itoa(len(links)),
		"homepage_status":        strconv.Itoa(fr.StatusCode),
		"scanned_via":            scannedViaForResult(fr.ScannedVia, aiScannedVia),
		"language_inconsistency": strconv.FormatBool(languageInconsistency),
		// ── Tiered fields (regional compliance matrix v2) ────────────────────
		"region_applied":         task.RegionCode,
		"found_mandatory_native": string(foundMandatoryJSON),
		"found_optional_native":  string(foundOptionalJSON),
		"found_english_fallback": string(foundFallbackJSON),
		"missing_mandatory":      string(missingMandatoryJSON),
	}

	// Fast-path invalidation signal: the previously stored compliance path was
	// not found on the homepage during this full scan. Java ScanResultProcessor
	// uses this to increment the path's fail_count.
	if fastPathMissed {
		details["path_fast_path_result"] = "invalidated"
	}

	// Populate English-keyword fields inline (no second HTTP round-trip).
	// The drawer uses these to render the "Language Inconsistency" panel.
	if len(task.FallbackKeywords) > 0 {
		enFoundJSON, _ := json.Marshal(foundFallback)
		enMissingJSON, _ := json.Marshal(missingFallback)
		details["english_found_keywords"] = string(enFoundJSON)
		details["english_missing_keywords"] = string(enMissingJSON)
		details["english_url"] = ""
		details["english_evidence"] = "[]"
	}

	// ── AI discovery fields ──────────────────────────────────────────────────
	// When AI link discovery found a compliance page that Go missed, record the
	// URL and content hash so Java TaskProducer can inject them as hints for the
	// next scan cycle.  This is the compliance equivalent of banner_selector +
	// accept_button_label hints for COOKIE_BANNER.
	if aiDiscoveredURL != "" {
		details["ai_discovered_url"] = aiDiscoveredURL
		details["ai_discovered_content_hash"] = aiDiscoveredHash
		// Prefer AI-discovered URL over Go-discovered URL for the frontend.
		details["discovered_url"] = aiDiscoveredURL
		details["ai_used"] = "true"
	}

	// ── AI semantic verification fields ──────────────────────────────────────
	// When AI recognised a non-standard link text as semantically equivalent to
	// the mandatory term, record the verdict so Java TaskProducer can inject
	// AIHintVerifiedLinkHref + AIHintVerifiedHomepageHash for the next scan
	// cycle (cache hit path).
	if aiVerifiedTerm != "" {
		details["ai_verified_term"] = aiVerifiedTerm
		details["ai_verified_link_href"] = aiVerifiedLinkHref
		details["ai_verified_homepage_hash"] = aiVerifiedHomepageHash
		details["ai_verification_reasoning"] = aiVerifiedReasoning
		details["ai_used"] = "true"
	}

	// ── Persistent compliance path fields ─────────────────────────────────────
	// When compliance was established by a specific homepage LINK (not text-
	// fallback), emit its address so Java ScanResultProcessor can UPSERT the
	// path into website_compliance_paths. Next scan cycle uses fast-path
	// verification against this stored path and skips the whole escalation
	// chain.
	//
	// Source precedence: AI semantic/discovery overrides rule-based only if it
	// actually ran (i.e. aiVerifiedTerm was set); rule-based is the default when
	// the scan was upgraded purely by keyword matching.
	if pathHref, pathText, pathURL, pathSource, pathConfidence := selectComplianceLinkPath(
		compliant, aiVerifiedTerm, aiVerifiedLinkHref, aiScannedVia,
		aiDiscoveredURL, links, cr.FoundLinks, foundMandatoryNative, foundFallback,
	); pathHref != "" {
		details["path_anchor_href"] = pathHref
		details["path_anchor_text"] = pathText
		details["path_discovered_url"] = pathURL
		details["path_source"] = pathSource
		details["path_confidence"] = pathConfidence
		// Minimal functional selector targeting the link by its exact href.
		// goquery accepts this directly; gives the UI a grep-able pointer and
		// leaves room for a structural selector (footer > nav > a.privacy) if
		// we later extend pageLink to carry DOM-tree context.
		details["path_dom_selector"] = buildLinkSelector(pathHref)
	}

	// ── AI content analysis results (homepage AI-discovery path) ─────────────
	if aiContentAnalysis != nil {
		details["ai_used"] = "true"
		details["ai_content_score"] = strconv.Itoa(aiContentAnalysis.Score)
		details["ai_content_reasoning"] = aiContentAnalysis.Reasoning
		if len(aiContentAnalysis.MissingElements) > 0 {
			if raw, err := json.Marshal(aiContentAnalysis.MissingElements); err == nil {
				details["ai_missing_elements"] = string(raw)
			}
		}
		if len(aiContentAnalysis.Issues) > 0 {
			if raw, err := json.Marshal(aiContentAnalysis.Issues); err == nil {
				details["ai_content_issues"] = string(raw)
			}
		}
	}

	// Human-readable one-liner summary for the frontend scan-summary banner.
	// Examples: "Privacy Policy ✓" | "Terms of Use ⚠ (WAF blocked)" | "Impressum ✗"
	details["scan_item_summary"] = buildItemSummary(
		status, task.SubType, foundMandatoryNative, cr.BrokenKeywords, cr.FoundLinks,
	)

	// Inject scout-discovered links into result for fast-path writing by Java side
	for _, link := range scoutDiscoveredLinks {
		if link.URL != "" && link.SubType != "" {
			details["ai_scout_link_"+strings.ToLower(link.SubType)] = link.URL
			details["ai_scout_text_"+strings.ToLower(link.SubType)] = link.Text
		}
	}

	// Deferred cache population — store the richest DOM we have AFTER all
	// escalation passes complete.  Require BOTH links>0 AND !WAFShell: large
	// HTML with zero extracted links is almost always a WAF interstitial
	// that slipped past isBlockPage; caching it would poison every other
	// monitor in the batch with 0 links.
	if !strings.HasSuffix(fr.ScannedVia, "+CACHED") {
		if rawHTML, htmlErr := fr.Doc.Html(); htmlErr == nil {
			if len(links) > 0 && !isWAFShellPage(rawHTML, extractBodyText(fr.Doc)) {
				c.cache.put(key, &crawlEntry{
					rawHTML:    rawHTML,
					statusCode: fr.StatusCode,
					scannedVia: fr.ScannedVia,
					cachedAt:   time.Now(),
				})
				slog.Debug("HomepageCrawlCache populated (post-escalation)",
					"monitor_id", task.MonitorID,
					"key", key,
					"scanned_via", fr.ScannedVia,
				)
			} else {
				slog.Warn("HomepageCrawlCache skip — suspected WAF shell (0 links, small HTML)",
					"monitor_id", task.MonitorID,
					"key", key,
					"html_bytes", len(rawHTML),
					"scanned_via", fr.ScannedVia,
				)
			}
		}
	}

	slog.Info("Compliance scan complete (homepage mode, tiered matrix v2)",
		"monitor_id", task.MonitorID,
		"url", task.WebsiteURL,
		"region", task.RegionCode,
		"status", status,
		"mandatory_required", len(task.RequiredKeywords),
		"optional_configured", len(task.OptionalKeywords),
		"mandatory_found", len(foundMandatoryNative),
		"optional_found", len(foundOptionalNative),
		"english_fallback_found", len(foundFallback),
		"missing_mandatory", missingMandatory,
		"broken", cr.BrokenKeywords,
		"language_inconsistency", languageInconsistency,
	)

	return &Result{
		MonitorID: task.MonitorID,
		Type:      c.Type(),
		Success:   true,
		ScannedAt: time.Now().UTC(),
		BatchID:   task.BatchID,
		Details:   details,
		RawDOM:    rawHomepageHTML(fr.Doc),
	}, nil
}

// rawHomepageHTML extracts the full outer HTML of the fetched homepage document
// for persistence in monitor_dom_snapshots (audit trail). Errors are swallowed —
// the scan result itself is still valid without the snapshot.
func rawHomepageHTML(doc *goquery.Document) string {
	if doc == nil {
		return ""
	}
	html, err := doc.Html()
	if err != nil {
		return ""
	}
	return html
}

// buildLinkSelector returns a CSS selector that targets an <a> element by its
// exact href. The href is double-quoted and the few characters that break CSS
// attribute-selector quoting (`"` and `\`) are backslash-escaped. goquery can
// execute this selector directly via doc.Find(...).
func buildLinkSelector(href string) string {
	escaped := strings.ReplaceAll(href, `\`, `\\`)
	escaped = strings.ReplaceAll(escaped, `"`, `\"`)
	return `a[href="` + escaped + `"]`
}

// selectComplianceLinkPath picks the primary homepage link that justified this
// scan's compliance verdict, if any, and returns its identifying fields
// (href, text, discovered_url, source tag, confidence tier). Returns all empty
// strings when no link-based evidence exists (text-fallback, no match at all).
//
// Priority:
//  1. AI semantic/discovery match — aiVerifiedTerm is set; source depends on
//     which AI path populated it (AI_SEMANTIC_VERIFIED vs AI_DISCOVERY).
//  2. AI discovery with body-match re-validation — aiDiscoveredURL is set but
//     aiVerifiedTerm is empty; find the anchor text from the homepage links.
//  3. Rule-based mandatory-native keyword match in cr.FoundLinks.
//  4. Rule-based fallback-keyword match in cr.FoundLinks.
func selectComplianceLinkPath(
	compliant bool,
	aiVerifiedTerm, aiVerifiedLinkHref, aiScannedVia, aiDiscoveredURL string,
	pageLinks []pageLink,
	foundLinks []FoundLink,
	foundMandatoryNative, foundFallback []string,
) (href, text, url, source, confidence string) {
	if !compliant {
		return "", "", "", "", ""
	}

	// AI semantic verify / AI discovery high-confidence trust path.
	if aiVerifiedTerm != "" && aiVerifiedLinkHref != "" {
		src := "AI_DISCOVERY"
		if strings.Contains(aiScannedVia, "AI_SEMANTIC") {
			src = "AI_SEMANTIC_VERIFIED"
		}
		return aiVerifiedLinkHref, aiVerifiedTerm, aiVerifiedLinkHref, src, "high"
	}

	// AI discovery (medium-confidence body-match path).
	if aiDiscoveredURL != "" {
		anchorText := aiDiscoveredURL
		for _, l := range pageLinks {
			if l.Href == aiDiscoveredURL {
				anchorText = l.Text
				break
			}
		}
		return aiDiscoveredURL, anchorText, aiDiscoveredURL, "AI_DISCOVERY", "medium"
	}

	// Rule-based: prefer a link whose keyword is mandatory-native.
	mandatory := make(map[string]bool, len(foundMandatoryNative))
	for _, kw := range foundMandatoryNative {
		mandatory[kw] = true
	}
	for _, l := range foundLinks {
		if mandatory[l.Keyword] {
			return l.Href, l.Text, l.Href, "RULE_BASED", "high"
		}
	}
	// Rule-based: fallback-keyword match (English equivalent, PASSING but with
	// language_inconsistency flagged — still a persistent link worth remembering).
	fallback := make(map[string]bool, len(foundFallback))
	for _, kw := range foundFallback {
		fallback[kw] = true
	}
	for _, l := range foundLinks {
		if fallback[l.Keyword] {
			return l.Href, l.Text, l.Href, "RULE_BASED", "high"
		}
	}
	return "", "", "", "", ""
}

// scannedViaForResult returns the AI-upgraded scanned_via tag when AI discovery
// was used, otherwise the original tag.
func scannedViaForResult(original, aiTag string) string {
	if aiTag != "" {
		return aiTag
	}
	return original
}

// verifyComplianceHint attempts to validate an AI-discovered compliance page URL.
// It fetches the hint URL, checks for required keywords, and if successful returns
// a complete Result ready to be sent.  Returns (nil, false) if verification fails.
func (c *ComplianceScanner) verifyComplianceHint(ctx context.Context, client *http.Client, task Task, loc LocalizationConfig) (*Result, bool) {
	slog.Info("ComplianceAI: verifying AI hint URL",
		"monitor_id", task.MonitorID,
		"hint_url", task.AIHintDiscoveredURL,
		"hint_hash", task.AIHintContentHash,
	)

	fetchCtx, fetchCancel := context.WithTimeout(ctx, fetchTimeout)
	defer fetchCancel()

	fr, err := hybridFetch(fetchCtx, client, c.headless, task.AIHintDiscoveredURL, loc)
	if err != nil {
		slog.Warn("ComplianceAI: hint URL fetch failed",
			"monitor_id", task.MonitorID,
			"url", task.AIHintDiscoveredURL,
			"error", err,
		)
		return nil, false
	}

	pageText := extractBodyText(fr.Doc)
	foundKWs, missingKWs := matchKeywordsInText(pageText, task.RequiredKeywords)

	if len(foundKWs) == 0 {
		slog.Warn("ComplianceAI: hint URL has no matching keywords — hint invalidated",
			"monitor_id", task.MonitorID,
			"url", task.AIHintDiscoveredURL,
		)
		return nil, false
	}

	// Hint verified — build a targeted-style result with hint metadata.
	currentHash := hashContent(pageText)
	contentChanged := "unknown"
	if task.AIHintContentHash != "" {
		if currentHash == task.AIHintContentHash {
			contentChanged = "false"
		} else {
			contentChanged = "true"
		}
	}

	// Build evidence for found keywords.
	var evidence []KeywordEvidence
	lowerPageText := strings.ToLower(pageText)
	for _, kw := range foundKWs {
		idx := strings.Index(lowerPageText, strings.ToLower(kw))
		snippet := ""
		if idx >= 0 {
			snippet = extractSnippet(pageText, kw, idx)
		}
		evidence = append(evidence, KeywordEvidence{
			Keyword:         kw,
			Score:           5,
			Location:        "ai_hint_verified",
			EvidenceContext: snippet,
			FoundAt:         task.AIHintDiscoveredURL,
		})
	}

	foundKWJSON, _ := json.Marshal(foundKWs)
	missingKWJSON, _ := json.Marshal(missingKWs)
	emptyJSON, _ := json.Marshal([]string{})
	evidenceJSON, _ := json.Marshal(evidence)

	compliant := len(foundKWs) > 0
	status := "NON_COMPLIANT"
	if compliant {
		status = "COMPLIANT"
	}

	details := map[string]string{
		"mode":                       "homepage",
		"discovered_url":             task.AIHintDiscoveredURL,
		"ai_discovered_url":          task.AIHintDiscoveredURL,
		"ai_discovered_content_hash": currentHash,
		"status":                     status,
		"found_keywords":             string(foundKWJSON),
		"broken_keywords":            string(emptyJSON),
		"missing_keywords":           string(missingKWJSON),
		"found_links":                string(emptyJSON),
		"keyword_evidence":           string(evidenceJSON),
		"total_required":             strconv.Itoa(len(task.RequiredKeywords)),
		"total_found":                strconv.Itoa(len(foundKWs)),
		"total_broken":               "0",
		"total_missing":              strconv.Itoa(len(missingKWs)),
		"content_hash":               currentHash,
		"content_changed":            contentChanged,
		"scanned_via":                "AI_HINT_VERIFIED",
		"region_applied":             task.RegionCode,
		"found_mandatory_native":     string(foundKWJSON),
		"found_optional_native":      "[]",
		"found_english_fallback":     "[]",
		"missing_mandatory":          string(missingKWJSON),
	}

	details["scan_item_summary"] = buildItemSummary(
		status, task.SubType, foundKWs, nil, nil,
	)

	// AI content analysis on the verified page.
	if compliant && c.contentAI != nil && c.contentAI.Available() {
		if aiResult, aiErr := c.contentAI.analyze(ctx, pageText, task.SubType, task.RegionCode); aiErr == nil {
			details["ai_content_score"] = strconv.Itoa(aiResult.Score)
			details["ai_content_reasoning"] = aiResult.Reasoning
			details["ai_used"] = "true"
			if len(aiResult.MissingElements) > 0 {
				if raw, err := json.Marshal(aiResult.MissingElements); err == nil {
					details["ai_missing_elements"] = string(raw)
				}
			}
			if len(aiResult.Issues) > 0 {
				if raw, err := json.Marshal(aiResult.Issues); err == nil {
					details["ai_content_issues"] = string(raw)
				}
			}
			if aiResult.Score < 40 {
				status = "NON_COMPLIANT"
				details["status"] = status
			}
		}
	}

	slog.Info("ComplianceAI: hint verification successful",
		"monitor_id", task.MonitorID,
		"url", task.AIHintDiscoveredURL,
		"status", status,
		"found", len(foundKWs),
		"content_changed", contentChanged,
	)

	return &Result{
		MonitorID: task.MonitorID,
		Type:      "COMPLIANCE",
		Success:   true,
		ScannedAt: time.Now().UTC(),
		BatchID:   task.BatchID,
		Details:   details,
	}, true
}

// fastPathScan verifies that the previously-stored compliance link (from
// website_compliance_paths) is still present on the homepage. On success it
// returns a COMPLIANT result immediately (~1-3s). On miss it returns (nil, false)
// and the caller falls through to the full scan pipeline.
//
// The fail signal is emitted via details["path_fast_path_result"]="invalidated"
// so the Java ScanResultProcessor increments the path's fail_count.
// fastPathStaticTimeout bounds the static-only fetch in fast-path verification.
const fastPathStaticTimeout = 5 * time.Second

// fastPathHeadlessTimeout bounds the headless escalation in fast-path verification.
// Must be ≥ the slowest JS-heavy site (some retail sites take ≈ 17s).
const fastPathHeadlessTimeout = 25 * time.Second

// findLinkByHref extracts all links from doc and returns the first one whose
// absolute href matches target.  Returns nil when no match is found.
func findLinkByHref(doc *goquery.Document, baseURL, target string) *pageLink {
	for _, l := range extractAllLinks(doc, baseURL) {
		if l.Href == target {
			return &l
		}
	}
	return nil
}

// fastPathScan returns (result, nil, true) on success.
// On miss it returns (nil, headlessFR, false) where headlessFR is non-nil when
// a headless fetch was performed — the caller can reuse the document to avoid a
// duplicate Chrome spawn in the full scan pipeline.
func (c *ComplianceScanner) fastPathScan(ctx context.Context, client *http.Client, task Task, loc LocalizationConfig) (*Result, *FetchResult, bool) {
	start := time.Now()

	slog.Debug("Fast-path: attempting verification",
		"monitor_id", task.MonitorID,
		"url", task.WebsiteURL,
		"path_href", task.PathAnchorHref,
		"fetch_mode_hint", task.FetchModeHint,
	)

	scannedVia := "STATIC+FAST_PATH"
	foundLink := (*pageLink)(nil)

	// ── Tier 1: static fetch (skipped when fetch-mode hint says HEADLESS) ──
	if task.FetchModeHint != "HEADLESS" {
		fastCtx, fastCancel := context.WithTimeout(ctx, fastPathStaticTimeout)
		_, doc, err := fetchAndParse(fastCtx, client, task.WebsiteURL)
		fastCancel()

		if err != nil {
		} else {
			foundLink = findLinkByHref(doc, task.WebsiteURL, task.PathAnchorHref)
		}
	} else {
	}

	// ── Tier 2: cache or headless when static missed the link ───────────────
	domain := apexDomain(task.WebsiteURL)
	cKey := cacheKey(task.BatchID, domain)

	if foundLink == nil {
		// Check HomepageCrawlCache before spawning a new Chrome tab — a prior
		// monitor's fast-path or full scan for the same domain may have already
		// populated it.
		if cached, ok := c.cache.get(cKey); ok {
			if cDoc, cErr := cached.reparse(); cErr == nil {
				foundLink = findLinkByHref(cDoc, task.WebsiteURL, task.PathAnchorHref)
				if foundLink != nil {
					scannedVia = cached.scannedVia + "+FAST_PATH+CACHED"
					slog.Info("Fast-path: cache hit",
						"monitor_id", task.MonitorID,
						"url", task.WebsiteURL,
						"cache_key", cKey,
					)
				}
			}
		}
	}

	// Skip fast-path headless when fetchModeHint=HEADLESS: the main scan will
	// also go headless directly, so running two separate Chrome navigations of the
	// same URL wastes ~25s of the 60s scan budget and risks a deadline exceeded.
	if foundLink == nil && task.FetchModeHint != "HEADLESS" && c.headless != nil && c.headless.Available() {
		slog.Info("Fast-path: static miss, escalating to headless",
			"monitor_id", task.MonitorID,
			"url", task.WebsiteURL,
			"static_ms", time.Since(start).Milliseconds(),
		)

		hCtx, hCancel := context.WithTimeout(ctx, fastPathHeadlessTimeout)
		defer hCancel()

		hStatus, hDoc, hErr := c.headless.FetchPage(hCtx, task.WebsiteURL, loc, "")
		if hErr != nil {
			slog.Warn("Fast-path: headless fetch failed — falling back to full scan",
				"monitor_id", task.MonitorID,
				"url", task.WebsiteURL,
				"error", hErr,
			)
			return nil, nil, false
		}

		// Populate cache so subsequent monitors for the same domain reuse
		// this headless DOM instead of spawning another Chrome tab.
		if rawHTML, htmlErr := hDoc.Html(); htmlErr == nil {
			c.cache.put(cKey, &crawlEntry{
				rawHTML:    rawHTML,
				statusCode: hStatus,
				scannedVia: "HEADLESS",
				cachedAt:   time.Now(),
			})
		}

		foundLink = findLinkByHref(hDoc, task.WebsiteURL, task.PathAnchorHref)
		if foundLink != nil {
			scannedVia = "HEADLESS+FAST_PATH"
		} else {
			// Link genuinely gone — return the headless doc so scanHomepage can
			// reuse it instead of spawning a second Chrome tab.
			slog.Warn("Fast-path: link not in headless DOM — falling back to full scan",
				"monitor_id", task.MonitorID,
				"url", task.WebsiteURL,
				"path_href", task.PathAnchorHref,
			)
			reusable := &FetchResult{StatusCode: hStatus, Doc: hDoc, ScannedVia: "HEADLESS"}
			return nil, reusable, false
		}
	} else if foundLink == nil {
		// No headless available — static-only miss.
		return nil, nil, false
	}

	slog.Info("Fast-path: verified",
		"monitor_id", task.MonitorID,
		"url", task.WebsiteURL,
		"scanned_via", scannedVia,
		"elapsed_ms", time.Since(start).Milliseconds(),
	)

	discoveredURL := task.PathDiscoveredURL
	if discoveredURL == "" {
		discoveredURL = foundLink.Href
	}

	foundMandatory := []string{foundLink.Text}
	foundMandatoryJSON, _ := json.Marshal(foundMandatory)
	emptyJSON, _ := json.Marshal([]string{})

	// Build proof data so the UI can display the eye-icon and link details.
	fpFoundLinks := []FoundLink{{
		Keyword: foundLink.Text,
		Text:    foundLink.Text,
		Href:    foundLink.Href,
		Status:  200,
		Broken:  false,
	}}
	fpFoundLinksJSON, _ := json.Marshal(fpFoundLinks)

	evidenceCtx := foundLink.Text
	if evidenceCtx == "" {
		evidenceCtx = foundLink.Href
	}
	fpEvidence := []KeywordEvidence{{
		Keyword:         foundLink.Text,
		Score:           5,
		Location:        "link",
		EvidenceContext: evidenceCtx,
		FoundAt:         foundLink.Href,
	}}
	fpEvidenceJSON, _ := json.Marshal(fpEvidence)

	details := map[string]string{
		"mode":                   "homepage",
		"discovered_url":         discoveredURL,
		"status":                 "COMPLIANT",
		"found_keywords":         string(foundMandatoryJSON),
		"broken_keywords":        string(emptyJSON),
		"missing_keywords":       string(emptyJSON),
		"found_links":            string(fpFoundLinksJSON),
		"keyword_evidence":       string(fpEvidenceJSON),
		"total_required":         strconv.Itoa(len(task.RequiredKeywords) + len(task.OptionalKeywords)),
		"total_found":            "1",
		"total_broken":           "0",
		"total_missing":          "0",
		"homepage_status":        "200",
		"scanned_via":            scannedVia,
		"language_inconsistency": "false",
		"region_applied":         task.RegionCode,
		"found_mandatory_native": string(foundMandatoryJSON),
		"found_optional_native":  string(emptyJSON),
		"found_english_fallback": string(emptyJSON),
		"missing_mandatory":      string(emptyJSON),
		"path_fast_path_result":  "verified",
	}

	details["scan_item_summary"] = buildItemSummary("COMPLIANT", task.SubType, foundMandatory, nil, nil)

	return &Result{
		MonitorID: task.MonitorID,
		Type:      "COMPLIANCE",
		Success:   true,
		ScannedAt: time.Now().UTC(),
		BatchID:   task.BatchID,
		Details:   details,
		// RawDOM deliberately empty: fast-path only needed to confirm one link
		// still exists, so serialising ~300KB of HTML back for monitor_dom_snapshots
		// would waste memory and Redis bandwidth. The previous full-scan already
		// captured a fresh snapshot on its way to discovering the path.
	}, nil, true
}

// ─── targeted mode ────────────────────────────────────────────────────────────

func (c *ComplianceScanner) scanTargetedPage(ctx context.Context, client *http.Client, task Task) (*Result, error) {
	// ── Accessibility pre-check ───────────────────────────────────────────────
	// Determine whether task.TargetURL is directly linked from the homepage
	// BEFORE fetching the target page content.  The check is bounded by
	// accessibilityTimeout (20 s) so it never consumes the full scan budget.
	//
	// The result is informational: even when IsAccessible=false, the deep
	// content scan proceeds unconditionally — accessibility and content
	// compliance are orthogonal axes.
	accCtx, accCancel := context.WithTimeout(ctx, accessibilityTimeout)
	acc := c.checkHomepageAccessibility(accCtx, client, task)
	accCancel()

	accessibleStr := "unknown" // homepage could not be fetched
	if acc.FetchErr == nil {
		if acc.IsAccessible {
			accessibleStr = "true"
		} else {
			accessibleStr = "false"
		}
	} else {
		slog.Warn("Accessibility pre-check failed — recording as unknown",
			"monitor_id", task.MonitorID,
			"target_url", task.TargetURL,
			"error", acc.FetchErr,
		)
		// If the homepage fetch hit a WAF block, pause briefly before fetching
		// the target page.  Akamai's per-IP rate-limit window is typically 3-10 s;
		// an immediate follow-up request from the same IP is very likely to be
		// blocked as well.  A short cooldown lets the window expire.
		select {
		case <-time.After(5 * time.Second):
		case <-ctx.Done():
			return complianceFailResult(task, 0, ctx.Err()), nil
		}
	}

	fetchCtx, fetchCancel := context.WithTimeout(ctx, fetchTimeout)
	defer fetchCancel()

	loc := GetLocalization(task.RegionCode)
	fr, err := hybridFetch(fetchCtx, client, c.headless, task.TargetURL, loc)
	if err != nil {
		return complianceFailResult(task, fr.StatusCode, fmt.Errorf("fetch target page: %w", err)), nil
	}

	// ── keyword presence in full page text ───────────────────────────────────
	pageText := extractBodyText(fr.Doc)
	foundKWs, missingKWs := matchKeywordsInText(pageText, task.RequiredKeywords)

	// Smart escalation: if the static fetch missed keywords, deploy the
	// headless multi-stage clicker before giving up (consent walls on targeted
	// pages — e.g. a /datenschutz page behind a paywall prompt — block static
	// text extraction just as they do on homepages).
	if fr.ScannedVia == "STATIC" && len(missingKWs) > 0 &&
		c.headless != nil && c.headless.Available() {
		slog.Info("Static fetch missed keywords — escalating to headless multi-stage (targeted)",
			"monitor_id", task.MonitorID,
			"target_url", task.TargetURL,
			"missing", missingKWs,
		)
		hCtx, hCancel := context.WithTimeout(ctx, fetchTimeout)
		defer hCancel()
		hStatus, hDoc, hErr := c.headless.FetchPage(hCtx, task.TargetURL, loc, "")
		if hErr == nil {
			fr = FetchResult{StatusCode: hStatus, Doc: hDoc, ScannedVia: "HEADLESS"}
			pageText = extractBodyText(fr.Doc)
			foundKWs, missingKWs = matchKeywordsInText(pageText, task.RequiredKeywords)
			slog.Info("Headless escalation complete (targeted)",
				"monitor_id", task.MonitorID,
				"target_url", task.TargetURL,
				"missing_after", missingKWs,
			)
		} else {
			proxyURL := GetLocalization(task.RegionCode).HTTPProxy
			if fbFr, ok := attemptAllFallbacks(hCtx, c.headless, task.TargetURL, loc, proxyURL, "compliance targeted headless fail"); ok {
				fr = fbFr
				pageText = extractBodyText(fr.Doc)
				foundKWs, missingKWs = matchKeywordsInText(pageText, task.RequiredKeywords)
				slog.Info("Headless escalation recovered via fallback (targeted)",
					"monitor_id", task.MonitorID,
					"target_url", task.TargetURL,
					"scanned_via", fr.ScannedVia,
				)
			} else {
				slog.Warn("Headless escalation failed — keeping static result (targeted)",
					"monitor_id", task.MonitorID,
					"target_url", task.TargetURL,
					"error", hErr,
				)
			}
		}
	}

	// OR-based compliance: finding any single keyword on the targeted page
	// confirms the concept is present — the list is synonyms, not a checklist.
	compliant := len(foundKWs) > 0
	status := "NON_COMPLIANT"
	if compliant {
		status = "COMPLIANT"
	}

	// ── content hashing ──────────────────────────────────────────────────────
	currentHash := hashContent(pageText)

	contentChanged := "unknown" // no previous hash provided
	if task.PreviousContentHash != "" {
		if currentHash == task.PreviousContentHash {
			contentChanged = "false"
		} else {
			contentChanged = "true"
			slog.Warn("Compliance page content has changed",
				"monitor_id", task.MonitorID,
				"target_url", task.TargetURL,
				"previous_hash", task.PreviousContentHash,
				"current_hash", currentHash,
			)
		}
	}

	// Discovery v2: capture snippet evidence for each found keyword
	var evidence []KeywordEvidence
	lowerPageText := strings.ToLower(pageText)
	for _, kw := range foundKWs {
		idx := strings.Index(lowerPageText, strings.ToLower(kw))
		snippet := ""
		if idx >= 0 {
			snippet = extractSnippet(pageText, kw, idx)
		}
		evidence = append(evidence, KeywordEvidence{
			Keyword:         kw,
			Score:           1,
			Location:        "text",
			EvidenceContext: snippet,
			FoundAt:         task.TargetURL,
		})
	}

	foundKWJSON, _ := json.Marshal(foundKWs)
	missingKWJSON, _ := json.Marshal(missingKWs)
	emptyJSON, _ := json.Marshal([]string{})
	evidenceJSON, _ := json.Marshal(evidence)

	slog.Info("Compliance scan complete (targeted mode, Discovery v2)",
		"monitor_id", task.MonitorID,
		"target_url", task.TargetURL,
		"region", task.RegionCode,
		"status", status,
		"total_required", len(task.RequiredKeywords),
		"total_found", len(foundKWs),
		"total_missing", len(missingKWs),
		"missing_keywords", missingKWs,
		"content_changed", contentChanged,
		"content_hash", currentHash,
	)

	// ── AI content analysis (targeted mode only) ────────────────────────────
	// When keywords are found (page exists and is relevant) and AI is available,
	// send the page text for quality/completeness evaluation.  The AI assesses
	// whether the legal page actually covers the required elements (GDPR Art. 13/14
	// for privacy policies, Impressum fields, etc.) — not just whether it exists.
	var contentAI *complianceContentAnalysis
	if compliant && c.contentAI.Available() && task.SubType != "COOKIE_BANNER" {
		slog.Info("ComplianceAI: analyzing content quality",
			"monitor_id", task.MonitorID,
			"sub_type", task.SubType,
			"region", task.RegionCode,
			"text_len", len(pageText),
		)
		if aiResult, aiErr := c.contentAI.analyze(ctx, pageText, task.SubType, task.RegionCode); aiErr != nil {
			slog.Warn("ComplianceAI: content analysis failed — proceeding without AI",
				"monitor_id", task.MonitorID,
				"error", aiErr,
			)
		} else {
			contentAI = aiResult
			if aiResult.Score < 40 {
				status = "NON_COMPLIANT"
			}
		}
	}

	details := map[string]string{
		"mode":             "targeted",
		"target_url":       task.TargetURL,
		"status":           status,
		"found_keywords":   string(foundKWJSON),
		"broken_keywords":  string(emptyJSON), // N/A in targeted mode
		"missing_keywords": string(missingKWJSON),
		"found_links":      string(emptyJSON), // N/A in targeted mode
		"keyword_evidence": string(evidenceJSON),
		"total_required":   strconv.Itoa(len(task.RequiredKeywords)),
		"total_found":      strconv.Itoa(len(foundKWs)),
		"total_broken":     "0",
		"total_missing":    strconv.Itoa(len(missingKWs)),
		"content_hash":     currentHash,
		"content_changed":  contentChanged,
		"page_status":      strconv.Itoa(fr.StatusCode),
		"scanned_via":      fr.ScannedVia,
		// ── Accessibility fields ─────────────────────────────────────────────
		// "true"    = target URL found in homepage <a href> links
		// "false"   = homepage was fetched but target URL not linked from root
		// "unknown" = homepage fetch failed (WAF block, network error, etc.)
		"is_accessible_from_home": accessibleStr,
		"accessible_via_href":     acc.MatchedHref,
		"homepage_links_checked":  strconv.Itoa(acc.CheckedLinksCount),
	}
	if task.PreviousContentHash != "" {
		details["previous_hash"] = task.PreviousContentHash
	}

	// ── AI content analysis results ──────────────────────────────────────────
	if contentAI != nil {
		details["ai_used"] = "true"
		details["ai_content_score"] = strconv.Itoa(contentAI.Score)
		details["ai_content_reasoning"] = contentAI.Reasoning
		if len(contentAI.MissingElements) > 0 {
			if raw, err := json.Marshal(contentAI.MissingElements); err == nil {
				details["ai_missing_elements"] = string(raw)
			}
		}
		if len(contentAI.Issues) > 0 {
			if raw, err := json.Marshal(contentAI.Issues); err == nil {
				details["ai_content_issues"] = string(raw)
			}
		}
	}

	return &Result{
		MonitorID: task.MonitorID,
		Type:      c.Type(),
		Success:   true,
		ScannedAt: time.Now().UTC(),
		BatchID:   task.BatchID,
		Details:   details,
	}, nil
}

// buildItemSummary generates a concise outcome phrase for a single compliance
// monitor scan, stored in details["scan_item_summary"].
//
// Priority rules (evaluated in order):
//  1. Any broken link → "<keyword> ⚠ (<note>)"  — reported even when other
//     synonyms were found, because an inaccessible link needs attention.
//  2. COMPLIANT, no broken → "<first found keyword> ✓"  — uses the winning
//     synonym so the user sees the actual text matched, not an internal key.
//  3. NON_COMPLIANT, no broken → "<SubType label> ✗"  — nothing matched; use
//     the formatted SubType as the human-readable subject.
//
// The old per-keyword iteration produced misleading output such as
// "Privacy Policy ✓ | Privacy Notice ✗" when only one synonym of many was
// required. This version evaluates the overall scan state instead.
// mandatoryTierSatisfied returns true if at least one keyword from required
// appears in found.  When required is empty (subType has no mandatory native
// keywords) it returns true immediately — the compliance threshold is vacuously met.
func mandatoryTierSatisfied(required, found []string) bool {
	if len(required) == 0 {
		return true
	}
	reqSet := make(map[string]bool, len(required))
	for _, kw := range required {
		reqSet[kw] = true
	}
	for _, kw := range found {
		if reqSet[kw] {
			return true
		}
	}
	return false
}

func buildItemSummary(overallStatus, subType string, found, broken []string, foundLinks []FoundLink) string {
	if len(broken) > 0 {
		// Report the first broken keyword and the HTTP status that blocked it.
		kw := broken[0]
		httpStatus := 0
		for _, fl := range foundLinks {
			if fl.Broken && strings.EqualFold(fl.Keyword, kw) {
				httpStatus = fl.Status
				break
			}
		}
		return kw + " " + brokenItemNote(httpStatus)
	}

	if strings.EqualFold(overallStatus, "COMPLIANT") {
		// Show the specific synonym that satisfied the check — not all found ones.
		if len(found) > 0 {
			return found[0] + " ✓"
		}
		return formatSubType(subType) + " ✓"
	}

	// NON_COMPLIANT with no broken links — nothing was found at all.
	return formatSubType(subType) + " ✗"
}

// formatSubType converts a SubType constant (e.g. "PRIVACY_POLICY") into a
// human-readable label (e.g. "Privacy Policy") for use in summary strings.
// Known values are mapped explicitly; unknown values fall back to title-cased
// underscore-split words so future SubTypes degrade gracefully.
func formatSubType(subType string) string {
	switch subType {
	case "PRIVACY_POLICY":
		return "Privacy Policy"
	case "TERMS_OF_SERVICE":
		return "Terms of Use"
	case "IMPRESSUM":
		return "Impressum"
	case "COOKIE_POLICY":
		return "Cookie Policy"
	case "GDPR_REQUEST":
		return "GDPR Request"
	default:
		words := strings.Split(strings.ToLower(subType), "_")
		for i, w := range words {
			if len(w) > 0 {
				words[i] = strings.ToUpper(w[:1]) + w[1:]
			}
		}
		return strings.Join(words, " ")
	}
}

// brokenItemNote returns the short parenthetical appended to a broken keyword
// in the scan_item_summary string.
func brokenItemNote(status int) string {
	switch {
	case status == 403 || status == 429:
		return "⚠ (WAF blocked)"
	case status == 401:
		return "⚠ (auth required)"
	case status == 0:
		return "⚠ (network error)"
	default:
		return fmt.Sprintf("⚠ (HTTP %d)", status)
	}
}

// complianceFailResult builds a failed Result from a fetch or parse error.
func complianceFailResult(task Task, pageStatus int, err error) *Result {
	slog.Error("Compliance scan failed",
		"monitor_id", task.MonitorID,
		"url", task.WebsiteURL,
		"target_url", task.TargetURL,
		"error", err,
	)
	return &Result{
		MonitorID: task.MonitorID,
		Type:      "COMPLIANCE",
		Success:   false,
		ScannedAt: time.Now().UTC(),
		BatchID:   task.BatchID,
		Details: map[string]string{
			"error":       err.Error(),
			"page_status": strconv.Itoa(pageStatus),
		},
	}
}
