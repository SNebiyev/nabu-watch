package scanner

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// cmpFingerprint maps a known Consent Management Platform vendor to one of its
// root-element CSS selectors.
type cmpFingerprint struct {
	name     string
	selector string
}

// knownCMPs lists well-known CMP DOM fingerprints.
// Checked in order; first visible match wins.
var knownCMPs = []cmpFingerprint{
	{"OneTrust", "#onetrust-consent-sdk"},
	{"OneTrust", "#onetrust-banner-sdk"},
	{"Cookiebot", "#CybotCookiebotDialog"},
	{"Cookiebot", "#CookieConsent"},
	{"Termly", "#termly-code-snippet-support"},
	{"TrustArc", "#truste-consent-track"},
	{"TrustArc", ".truste_overlay"},
	{"Osano", ".osano-cm-window"},
	{"Didomi", "#didomi-host"},
	{"Didomi", ".didomi-popup-notice"},
	{"Iubenda", ".iubenda-cs-container"},
	{"CookieFirst", "#cookiefirst-root"},
	{"Borlabs", "#BorlabsCookieBox"},
	{"Usercentrics", "#usercentrics-root"},
	{"CookieYes", "#cookie-law-info-bar"},
	{"CookieYes", ".cli_messagebar"},
	{"CookieNotice", "#cookie-notice"},
	// Sourcepoint — used by major publishers (large news publishers)
	// Dynamic numeric suffix on the ID; prefix match covers all variants.
	{"Sourcepoint", "[id^=sp_message_container]"},
	{"Sourcepoint", "[class*=sp-message-container]"},
	{"Sourcepoint", "[class*=message-overlay]"},
}

// genericBannerSelectors are checked when no named CMP fingerprint matches.
var genericBannerSelectors = []string{
	"[id*=cookie-consent]",
	"[id*=cookie_consent]",
	"[id*=cookieconsent]",
	"[id*=gdpr-banner]",
	"[id*=gdpr_banner]",
	"[id*=consent-manager]",
	"[id*=consent_manager]",
	"[class*=cookie-banner]",
	"[class*=cookie_banner]",
	"[class*=cookiebanner]",
	"[class*=consent-banner]",
	"[class*=consent-manager]",
	"[class*=cookie-notice]",
	"[class*=cookie-modal]",
	"[class*=cookie-overlay]",
	"[class*=cookie-wall]",
	"[class*=consent-overlay]",
	"[class*=consent-modal]",
	"[class*=privacy-banner]",
	".cc-window",
	"#cc-main",
}

// bannerTextSignals are phrases whose presence in a DOM element strongly
// indicates a cookie consent banner. Used only as a last-resort heuristic
// when no CMP fingerprint or generic selector matches.
// Each phrase is stored in lowercase; matching is case-insensitive.
var bannerTextSignals = []string{
	// German
	"diese seite verwendet cookies",
	"diese website verwendet cookies",
	"wir verwenden cookies",
	"wir nutzen cookies",
	"cookie-einstellungen",
	"cookie einstellungen",
	// English
	"this site uses cookies",
	"this website uses cookies",
	"we use cookies",
	"cookie preferences",
	"manage cookie",
	// French — standard cookie language
	"ce site utilise des cookies",
	"nous utilisons des cookies",
	"paramètres des cookies",
	// French — consent-wall / data-consent style (e.g. Le Monde)
	"utilisation de vos données",
	"votre consentement",
	"gestion des cookies",
	"accepter et continuer",
	// Spanish
	"este sitio utiliza cookies",
	"utilizamos cookies",
	// Italian
	"questo sito utilizza cookie",
	"utilizziamo cookie",
	// Dutch
	"deze site gebruikt cookies",
	"wij gebruiken cookies",
	// Polish
	"ta strona używa plików cookie",
	"używamy plików cookie",
	// Portuguese
	"este site utiliza cookies",
	"utilizamos cookies",
}

// acceptTerms are button/link labels indicating consent-accept action.
// Deliberately avoids very short terms (e.g. "ja", "ok") that produce false
// positives when matching against article headlines or navigation links.
var acceptTerms = []string{
	"accept", "agree", "allow", "ok", "akzeptieren", "accepter",
	"accepteren", "alle akzeptieren", "zustimmen", "stimme zu",
	"accept all", "accept cookies", "alle cookies akzeptieren", "alle zulassen",
	"consent and continue", "zustimmen und weiter",
}

// rejectTerms are button/link labels indicating consent-reject action.
// Includes both explicit reject buttons and functional equivalents:
// "apply settings" / "save choices" in a granular preferences panel means
// the user saved their (potentially unchecked) preferences — functionally
// equivalent to rejecting non-essential cookies.
var rejectTerms = []string{
	// Explicit reject
	"reject", "decline", "refuse", "deny", "ablehnen", "refuser",
	"weigeren", "nicht akzeptieren", "alle ablehnen", "reject all",
	"decline all", "refuser tout", "tout refuser", "alles ablehnen",
	// Functional reject (apply/save in granular preferences panel)
	"apply settings", "save settings", "save choices", "save preferences",
	"einstellungen speichern", "einstellungen übernehmen", "auswahl bestätigen",
	"auswahl speichern", "nur auswahl", "nur notwendige", "nur essentielle",
	// Withdraw / revoke consent
	"zustimmung widerrufen", "widerrufen", "ablehnen und schließen",
}

// prefTerms are labels indicating a preferences/settings action.
var prefTerms = []string{
	"settings", "preferences", "manage", "customize", "options",
	"einstellungen", "paramètres", "instellingen", "meer opties",
	"manage preferences", "cookie settings",
}

// bannerKeywords bundles the four keyword lists used by the banner scanner.
// When the task payload carries DB-sourced keywords (see banner_keywords table
// and BannerKeywordService), resolveBannerKeywords returns them; otherwise it
// returns the hardcoded package-level defaults as a safety net.
type bannerKeywords struct {
	phrases []string
	accept  []string
	reject  []string
	prefs   []string
}

// defaultBannerKeywords is the hardcoded fallback used when a task carries no
// DB-sourced keyword lists (older task payloads, misconfigured regions, or
// local Go-only tests).
var defaultBannerKeywords = bannerKeywords{
	phrases: bannerTextSignals,
	accept:  acceptTerms,
	reject:  rejectTerms,
	prefs:   prefTerms,
}

// resolveBannerKeywords picks the task-provided keyword lists when present,
// falling back per-field to the hardcoded defaults. Each task-provided list is
// normalised to lowercase because termMatches compares against strings.ToLower
// of the button text.
func resolveBannerKeywords(task Task) bannerKeywords {
	kws := defaultBannerKeywords
	if len(task.BannerPhrases) > 0 {
		kws.phrases = toLowerSlice(task.BannerPhrases)
	}
	if len(task.BannerAcceptTerms) > 0 {
		kws.accept = toLowerSlice(task.BannerAcceptTerms)
	}
	if len(task.BannerRejectTerms) > 0 {
		kws.reject = toLowerSlice(task.BannerRejectTerms)
	}
	if len(task.BannerPrefTerms) > 0 {
		kws.prefs = toLowerSlice(task.BannerPrefTerms)
	}
	return kws
}

func toLowerSlice(in []string) []string {
	out := make([]string, len(in))
	for i, s := range in {
		out[i] = strings.ToLower(s)
	}
	return out
}

// BannerScanner detects whether a website presents a GDPR/ePrivacy-compliant
// cookie consent banner with functional accept and reject controls.
//
// Primary detection is rule-based (CMP fingerprints + button text matching).
// When the rule-based pass is uncertain (no banner found, or banner found but
// no reject option), the scanner falls back to AI analysis via Claude Haiku —
// enabled only when ANTHROPIC_API_KEY is set in the environment.
type BannerScanner struct {
	headless *HeadlessAllocator
	ai       aiAnalyzer
	uploader *ScreenshotUploader
}

// NewBannerScanner creates a BannerScanner backed by the shared headless allocator.
// AI fallback is wired in automatically when ANTHROPIC_API_KEY or GEMINI_API_KEY
// is set. Use AI_PROVIDER=claude|gemini to force a specific backend.
// The uploader is optional — pass nil to disable screenshot capture.
func NewBannerScanner(headless *HeadlessAllocator, uploader *ScreenshotUploader) *BannerScanner {
	return &BannerScanner{
		headless: headless,
		ai:       newAIAnalyzer(),
		uploader: uploader,
	}
}

// Type returns "COMPLIANCE" so that BannerScanner slots into the existing
// COMPLIANCE monitor pipeline without requiring a new MonitorType on the Java side.
func (b *BannerScanner) Type() string { return "COMPLIANCE" }

// Scan detects the cookie consent banner and evaluates GDPR/ePrivacy compliance.
// It looks for known CMP fingerprints and verifies that both accept and reject
// controls are present — the minimum requirement under GDPR recital 32 and the
// ePrivacy Directive as enforced by most EU DPAs.
func (b *BannerScanner) Scan(ctx context.Context, task Task) (*Result, error) {

	targetURL := task.WebsiteURL
	if task.TargetURL != "" {
		targetURL = task.TargetURL
	}

	slog.Info("BannerScanner: starting banner detection",
		"monitor_id", task.MonitorID,
		"url", targetURL,
		"headless_available", b.headless.Available(),
	)

	if !b.headless.Available() {
		r := b.buildResult(task, bannerResult{
			issues:     []string{"Headless browser unavailable — banner detection requires JavaScript rendering"},
			scannedVia: "UNAVAILABLE",
		})
		return r, nil
	}

	loc := GetLocalization(task.RegionCode)
	proxyURL := loc.HTTPProxy
	loc.HTTPProxy = "" // Try direct egress first — proxy reserved for WAF fallback

	// Decide whether to capture a screenshot based on the website's opt-in.
	wantScreenshot := task.ScreenshotsEnabled && b.uploader != nil && b.uploader.Available()
	var ssBuf []byte
	var doc *goquery.Document
	var err error

	fetchBanner := func(fCtx context.Context, fLoc LocalizationConfig) {
		if wantScreenshot {
			_, doc, ssBuf, err = b.headless.FetchBannerPageWithScreenshot(fCtx, targetURL, fLoc, "")
		} else {
			_, doc, err = b.headless.FetchBannerPage(fCtx, targetURL, fLoc, "")
		}
	}

	fetchBanner(ctx, loc)
	// WAF fallback: if direct egress was blocked, retry with residential proxy.
	if err != nil && proxyURL != "" {
		slog.Info("BannerScanner: retrying with residential proxy",
			"monitor_id", task.MonitorID,
			"url", targetURL,
		)
		proxyCtx, proxyCancel := context.WithTimeout(context.Background(), proxyRetryTimeout)
		defer proxyCancel()
		proxyLoc := loc
		proxyLoc.HTTPProxy = proxyURL
		fetchBanner(proxyCtx, proxyLoc)
	}
	if err != nil {
		errMsg := err.Error()

		// Transient failures (site JS crashes, proxy tunnel errors) should not
		// mark the monitor as FAILED — preserve the previous state instead.
		// Proxy tunnel errors (ERR_TUNNEL_CONNECTION_FAILED etc.) are transient
		// infrastructure blips; the proxy will recover on the next scan cycle.
		if isTransientHeadlessError(errMsg) {
			slog.Warn("BannerScanner: transient fetch error — preserving previous state",
				"monitor_id", task.MonitorID,
				"url", targetURL,
				"error", errMsg,
			)
			return nil, fmt.Errorf("headless fetch failed (transient): %w", ErrTransientHeadless)
		}

		r := &Result{
			MonitorID: task.MonitorID,
			Type:      b.Type(),
			Success:   false,
			Details: map[string]string{
				"error":  fmt.Sprintf("headless fetch failed: %v", err),
				"status": "FAILED",
			},
			ScannedAt: time.Now().UTC(),
			BatchID:   task.BatchID,
		}
		return r, nil
	}

	// Task-provided keyword lists (from banner_keywords DB table) win over the
	// hardcoded defaults, so new languages reach the scanner without a redeploy.
	kws := resolveBannerKeywords(task)
	source := "hardcoded"
	if len(task.BannerPhrases) > 0 || len(task.BannerAcceptTerms) > 0 ||
		len(task.BannerRejectTerms) > 0 || len(task.BannerPrefTerms) > 0 {
		source = "task+db"
	}
	slog.Info("BannerScanner: keyword lists resolved",
		"monitor_id", task.MonitorID,
		"source", source,
		"phrases", len(kws.phrases),
		"accept", len(kws.accept),
		"reject", len(kws.reject),
		"prefs", len(kws.prefs),
	)

	// Upload screenshot early — before fast-path and CMP detection — so it is
	// always captured regardless of which detection path succeeds.
	totalButtons := doc.Find("button, [role=button], a").Length()
	var screenshotKey string
	if len(ssBuf) > 0 && totalButtons > 0 {
		key, uploadErr := b.uploader.Upload(ctx, task.MonitorID, ssBuf)
		if uploadErr != nil {
			slog.Warn("BannerScanner: screenshot upload failed", "monitor_id", task.MonitorID, "error", uploadErr)
		} else {
			screenshotKey = key
		}
	}

	// ── 0. Fast-path: verify stored selector from previous successful scan ────
	// Java injects the banner selector + button labels from the last compliant
	// scan (AI-assisted or plain HEADLESS) as task hints. When those hints confirm
	// the same banner is still present, we skip the heavier CMP fingerprint scan
	// and any AI call entirely.
	hintAlreadyTried := false
	if hasAIHints(task) {
		hintAlreadyTried = true
		var fpBr bannerResult
		scope := verifyAIHints(doc, task, &fpBr)
		if scope != nil && !fpBr.rejectFound {
			// Selector is present but exact label didn't match — the button text may
			// have changed slightly. Widen to a full keyword scan within the known
			// scope; avoids false positives from buttons outside the banner element.
			scanButtons(scope, &fpBr, kws)
			if fpBr.rejectFound {
				fpBr.detected = true
				fpBr.cmpSelector = task.AIHintSelector
				if fpBr.cmpName == "" {
					fpBr.cmpName = "Hint"
				}
			}
		}
		if fpBr.detected && fpBr.rejectFound {
			fpBr.scannedVia = "AI_HINT_VERIFIED"
			fpBr.screenshotKey = screenshotKey
			slog.Info("BannerScanner: fast-path hit — skipping CMP detection",
				"monitor_id", task.MonitorID,
				"selector", fpBr.cmpSelector,
				"reject_label", fpBr.rejectLabel,
			)
			r := b.buildResult(task, fpBr)
			return r, nil
		}
	}

	// ── 1 & 2. CMP detection (named fingerprint → generic fallback) ──────────
	cmpName, cmpSelector := detectCMP(doc, kws.phrases)

	slog.Info("BannerScanner: CMP detection result",
		"monitor_id", task.MonitorID,
		"cmp_detected", cmpName != "",
		"cmp_name", cmpName,
		"cmp_selector", cmpSelector,
		"total_buttons_in_doc", totalButtons,
	)
	if cmpName != "" {
	} else {
	}

	br := bannerResult{
		detected:      cmpName != "",
		cmpName:       cmpName,
		cmpSelector:   cmpSelector,
		scannedVia:    "HEADLESS",
		screenshotKey: screenshotKey,
	}

	// ── 3. Button analysis within the banner scope ────────────────────────────
	if br.detected {
		scope := doc.Find(cmpSelector)
		scopeButtonCount := scope.Find("button, [role=button], a[href]").Length()

		if scopeButtonCount == 0 {
			// Buttons may live outside the scoped CMP container (sibling div,
			// merged Shadow DOM fragment) — try the full document before AI.
			slog.Info("BannerScanner: banner element has no buttons in scope — trying full document fallback",
				"monitor_id", task.MonitorID,
				"cmp_name", br.cmpName,
			)
			scanButtons(doc.Selection, &br, kws)

			if br.acceptFound || br.rejectFound {
				slog.Info("BannerScanner: full-document fallback found buttons",
					"monitor_id", task.MonitorID,
					"accept_found", br.acceptFound,
					"reject_found", br.rejectFound,
					"accept_label", br.acceptLabel,
					"reject_label", br.rejectLabel,
				)
			} else {
				// Full document also yielded nothing — likely an iframe-hosted
				// CMP (e.g. Sourcepoint) where controls live in a cross-origin
				// frame that bannerDOMExtractorJS can only partially dump.
				br.geoGatedSuspected = true
				slog.Info("BannerScanner: no buttons in scope or full document — deferring to AI",
					"monitor_id", task.MonitorID,
					"cmp_name", br.cmpName,
				)
			}
		} else {
			scanButtons(scope, &br, kws)

			slog.Info("BannerScanner: button analysis (banner scope)",
				"monitor_id", task.MonitorID,
				"scope_button_count", scopeButtonCount,
				"accept_found", br.acceptFound,
				"reject_found", br.rejectFound,
				"prefs_found", br.prefsFound,
				"accept_label", br.acceptLabel,
				"reject_label", br.rejectLabel,
			)

			// Two-layer consent flow (e.g. large news sites): the initial banner only
			// shows Accept + Preferences; Reject options are in the preferences
			// panel that FetchBannerPage opened via prefsClickerJS. That panel is
			// merged into the document as htmlPart2 but sits outside the initial
			// banner element's selector scope — widen the search to the full DOM.
			if br.prefsFound && !br.rejectFound {
				slog.Info("BannerScanner: two-layer flow detected — widening search to full document",
					"monitor_id", task.MonitorID,
				)
				scanButtons(doc.Selection, &br, kws)
				slog.Info("BannerScanner: button analysis (full document)",
					"monitor_id", task.MonitorID,
					"reject_found", br.rejectFound,
					"reject_label", br.rejectLabel,
				)
			}
		}
	}

	// ── 4. AI hint verification ──────────────────────────────────────────────
	// Skip when fast-path (step 0) already tried the same hints and failed —
	// the DOM hasn't changed, so retrying against the same document is futile.
	if !hintAlreadyTried && (!br.detected || !br.rejectFound) && hasAIHints(task) {
		slog.Info("BannerScanner: trying AI hints from previous scan",
			"monitor_id", task.MonitorID,
			"hint_accept", task.AIHintAcceptLabel,
			"hint_reject", task.AIHintRejectLabel,
		)
		verifyAIHints(doc, task, &br)
		if br.detected && br.rejectFound {
			slog.Info("BannerScanner: AI hints confirmed — skipping AI call",
				"monitor_id", task.MonitorID,
				"accept_label", br.acceptLabel,
				"reject_label", br.rejectLabel,
			)
			br.scannedVia = "AI_HINT_VERIFIED"
		} else {
		}
	}

	// ── 5. AI fallback — only if hints didn't resolve it ────────────────────
	// Conditions: banner not detected at all, OR banner found but no reject
	// option identified (two-layer CMPs, unusual button labels, etc.).
	// Falls back gracefully on any API error — rule-based result is preserved.
	if (!br.detected || !br.rejectFound) && b.ai.Available() {
		slog.Info("BannerScanner: rule-based pass uncertain — calling AI fallback",
			"monitor_id", task.MonitorID,
			"detected", br.detected,
			"reject_found", br.rejectFound,
		)
		if analysis, err := b.ai.analyze(ctx, targetURL, br.cmpName, doc); err != nil {
			slog.Warn("BannerScanner: AI fallback failed — keeping rule-based result",
				"monitor_id", task.MonitorID,
				"error", err,
			)
		} else {
			// AI must never downgrade a rule-based detection. When the rule-based
			// pass already found a banner (br.detected=true) but missed the reject
			// button, AI is called only to find those buttons — not to re-evaluate
			// whether a banner exists. An AI "detected=false" in this scenario is
			// a hallucination that would wipe the correct rule-based finding.
			ruleAlreadyDetected := br.detected

			if analysis.Detected || !ruleAlreadyDetected {
				br.detected = analysis.Detected
			}
			if analysis.AcceptFound {
				br.acceptFound = true
				br.acceptLabel = analysis.AcceptLabel
			}
			if analysis.RejectFound {
				br.rejectFound = true
				br.rejectLabel = analysis.RejectLabel
			}
			br.issues = analysis.Issues
			br.aiReasoning = analysis.Reasoning
			br.scannedVia = "AI_ASSISTED"
			// Preserve the AI-identified selector so the next scan can use it
			// as a scoped hint instead of searching the full DOM.
			if analysis.BannerSelector != "" {
				br.cmpName = "AI"
				br.cmpSelector = analysis.BannerSelector
			}
		}
	}

	// ── 6. Compliance determination ───────────────────────────────────────────
	if !br.detected {
		br.issues = append(br.issues, "No cookie consent banner detected")
	} else if !br.rejectFound {
		br.issues = append(br.issues, "No reject/decline option found — possible dark pattern")
	}
	if br.geoGatedSuspected {
		br.issues = append(br.issues,
			"CMP detected but banner not rendered for this scanner's IP — likely IP-geo-gated. Result may not reflect what a local visitor sees. A regional proxy is recommended for an accurate audit.")
	}

	r := b.buildResult(task, br)
	// Capture the merged DOM (main document + shadow roots + iframes) for audit.
	// Errors are swallowed — the scan result itself is still valid without the snapshot.
	if html, err := doc.Html(); err == nil {
		r.RawDOM = html
	}
	return r, nil
}

// termMatches reports whether the button label matches the consent term.
// Short terms (≤3 chars like "ok") require an exact match to prevent false
// positives such as "Sudoku" matching "ok".  Longer terms use substring matching.
func termMatches(lower, term string) bool {
	if len(term) <= 3 {
		return lower == term
	}
	return strings.Contains(lower, term)
}

// scanButtons scans sel for accept / reject / prefs button labels using the
// supplied keyword lists and updates br in-place. Safe to call multiple
// times — already-found fields are skipped.
func scanButtons(sel *goquery.Selection, br *bannerResult, kws bannerKeywords) {
	sel.Find("button, [role=button], a").Each(func(_ int, s *goquery.Selection) {
		text := strings.TrimSpace(s.Text())
		// Skip blank or long entries — real consent button labels are always short.
		// Long texts are article headlines, image captions, or navigation links
		// that can produce false positives on accept/reject term matching.
		if text == "" || len(text) > 100 {
			return
		}
		lower := strings.ToLower(text)

		if !br.acceptFound {
			for _, t := range kws.accept {
				if termMatches(lower, t) {
					br.acceptFound = true
					br.acceptLabel = text
					break
				}
			}
		}
		if !br.rejectFound {
			for _, t := range kws.reject {
				if termMatches(lower, t) {
					br.rejectFound = true
					br.rejectLabel = text
					break
				}
			}
		}
		if !br.prefsFound {
			for _, t := range kws.prefs {
				if termMatches(lower, t) {
					br.prefsFound = true
					break
				}
			}
		}
	})
}

// hasAIHints returns true when the task carries a usable AI hint set: a CSS
// selector that scopes the search, plus at least one button label to verify.
func hasAIHints(task Task) bool {
	return task.AIHintSelector != "" &&
		(task.AIHintAcceptLabel != "" || task.AIHintRejectLabel != "")
}

// verifyAIHints tries to confirm a previous scan's findings by locating the
// banner element via the stored CSS selector, then searching only within that
// scoped subtree for the exact button labels stored in the task hints.
//
// Returns the matched scope selection so callers can reuse it without a second
// DOM query (e.g. to run a keyword scan when exact labels changed).
// Returns nil when the selector is not present in the current DOM.
func verifyAIHints(doc *goquery.Document, task Task, br *bannerResult) *goquery.Selection {
	scope := doc.Find(task.AIHintSelector)
	if scope.Length() == 0 {
		slog.Info("BannerScanner: AI hint selector not found in DOM — hints invalid",
			"selector", task.AIHintSelector,
		)
		return nil
	}

	hintAccept := strings.ToLower(task.AIHintAcceptLabel)
	hintReject := strings.ToLower(task.AIHintRejectLabel)

	scope.Find("button, [role=button], a").Each(func(_ int, s *goquery.Selection) {
		text := strings.ToLower(strings.TrimSpace(s.Text()))
		if text == "" || len(text) > 100 {
			return
		}
		if hintAccept != "" && !br.acceptFound && strings.Contains(text, hintAccept) {
			br.acceptFound = true
			br.acceptLabel = strings.TrimSpace(s.Text())
		}
		if hintReject != "" && !br.rejectFound && strings.Contains(text, hintReject) {
			br.rejectFound = true
			br.rejectLabel = strings.TrimSpace(s.Text())
		}
	})

	if br.acceptFound || br.rejectFound {
		br.detected = true
		br.cmpName = "AI"
		br.cmpSelector = task.AIHintSelector
	}
	return scope
}

// detectCMP scans doc for known CMP fingerprints, generic selectors, and as a
// last resort, text-based heuristics using the supplied banner phrases.
// Returns (name, selector) of the first match, or ("", "") when no banner
// element is found.
func detectCMP(doc *goquery.Document, phrases []string) (string, string) {
	// Pass 1: named CMP fingerprints
	for _, fp := range knownCMPs {
		if doc.Find(fp.selector).Length() > 0 {
			return fp.name, fp.selector
		}
	}
	// Pass 2: generic attribute-based selectors
	for _, gs := range genericBannerSelectors {
		if doc.Find(gs).Length() > 0 {
			return "Generic", gs
		}
	}
	// Pass 3: text-based heuristic — scan common overlay containers for
	// cookie-consent language. This catches custom/in-house CMPs that don't
	// use recognisable IDs or class names.
	return detectBannerByText(doc, phrases)
}

// bannerCandidateSelector targets elements that typically wrap consent banners:
// fixed/sticky overlays, modals, dialog elements, and GDPR/consent-named containers.
// Inline-style position checks (fixed/sticky) catch custom overlays whose position
// is set via a style attribute rather than a stylesheet class.
const bannerCandidateSelector = "div[role=dialog], dialog, [aria-modal=true], " +
	"div[style*=fixed], div[style*=sticky], " +
	"div[class*=modal], div[class*=overlay], div[class*=dialog], " +
	"div[class*=popup], div[class*=banner], " +
	"[class*=gdpr], [class*=consent], [id*=gdpr], [id*=consent], " +
	"[class*=paywall], [class*=wall]"

// detectBannerByText looks for banner-like text in overlay/dialog containers.
// It limits the search to elements that structurally resemble a banner to avoid
// false positives from marketing copy that mentions "cookies" in passing.
func detectBannerByText(doc *goquery.Document, phrases []string) (string, string) {
	var matchedSel string
	doc.Find(bannerCandidateSelector).EachWithBreak(func(_ int, s *goquery.Selection) bool {
		text := strings.ToLower(s.Text())
		for _, signal := range phrases {
			if strings.Contains(text, signal) {
				// Confirm it has at least one button — a real banner always has
				// an accept or reject control; a paragraph mentioning cookies does not.
				if s.Find("button, [role=button], a[href]").Length() > 0 {
					// Build a best-effort selector for logging
					if id, exists := s.Attr("id"); exists && id != "" {
						matchedSel = "#" + id
					} else if cls, exists := s.Attr("class"); exists && cls != "" {
						first := strings.Fields(cls)[0]
						matchedSel = "." + first
					} else {
						matchedSel = "div:text-heuristic"
					}
					return false // break
				}
			}
		}
		return true // continue
	})
	if matchedSel != "" {
		return "TextHeuristic", matchedSel
	}
	return "", ""
}

// bannerResult is an intermediate value object that holds the detection state
// before it is serialised into a Result.Details map.
type bannerResult struct {
	detected      bool
	cmpName       string
	cmpSelector   string
	acceptFound   bool
	acceptLabel   string
	rejectFound   bool
	rejectLabel   string
	prefsFound    bool
	issues        []string
	scannedVia    string
	aiReasoning   string
	screenshotKey string
	// geoGatedSuspected is set when a CMP host element is present in the
	// DOM but its scope contains zero interactive controls. This is the
	// signature of an IP-geo-gated banner: the CMP ships its wrapper to
	// everyone but only populates/renders the banner for visitors in its
	// enforced jurisdiction (e.g. Didomi loads the `#didomi-host` shell
	// for all users but the Shadow DOM iframe stays empty for non-EU IPs).
	// Surface this to the user so they know the scan's result reflects
	// the worker's IP, not necessarily what a local visitor would see.
	geoGatedSuspected bool
}

func (b *BannerScanner) buildResult(task Task, br bannerResult) *Result {
	compliant := br.detected && br.rejectFound

	issuesJSON := "[]"
	if len(br.issues) > 0 {
		if raw, err := json.Marshal(br.issues); err == nil {
			issuesJSON = string(raw)
		}
	}

	statusStr := "COMPLIANT"
	if !compliant {
		statusStr = "NON_COMPLIANT"
	}

	details := map[string]string{
		"banner_detected":          boolStr(br.detected),
		"cmp_detected":             br.cmpName,
		"banner_selector":          br.cmpSelector,
		"accept_button_found":      boolStr(br.acceptFound),
		"accept_button_label":      br.acceptLabel,
		"reject_button_found":      boolStr(br.rejectFound),
		"reject_button_label":      br.rejectLabel,
		"preferences_button_found": boolStr(br.prefsFound),
		"compliant":                boolStr(compliant),
		"compliance_issues":        issuesJSON,
		"scanned_via":              br.scannedVia,
		"status":                   statusStr,
	}
	if br.aiReasoning != "" {
		details["ai_reasoning"] = br.aiReasoning
		details["ai_used"] = "true"
	}
	if br.screenshotKey != "" {
		details["screenshot_key"] = br.screenshotKey
	}
	if br.geoGatedSuspected {
		details["geo_gated_suspected"] = "true"
	}

	return &Result{
		MonitorID: task.MonitorID,
		Type:      b.Type(),
		Success:   true,
		Details:   details,
		ScannedAt: time.Now().UTC(),
		BatchID:   task.BatchID,
	}
}

func boolStr(v bool) string {
	if v {
		return "true"
	}
	return "false"
}
