package scanner

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// proxyRetryTimeout is the independent timeout for residential proxy retries.
// This is separate from the main scan context so proxy retries are not starved
// by time already spent on direct-egress tiers.
const proxyRetryTimeout = 30 * time.Second

// wafBlockHTMLThreshold is the rendered-HTML byte count below which a page is
// presumed to be a WAF challenge shell (Akamai interstitial, Cloudflare JS
// challenge, etc.) rather than real site content.
const wafBlockHTMLThreshold = 10_000

// isWAFShellPage returns true when the rendered HTML is suspiciously small
// (below wafBlockHTMLThreshold) OR the body text matches known WAF/CAPTCHA
// block-page patterns.  Use this as the single predicate for all WAF-shell
// detection decisions (retry gating, cache-skip, etc.).
func isWAFShellPage(rawHTML, bodyText string) bool {
	return len(rawHTML) < wafBlockHTMLThreshold || isBlockPage(bodyText)
}

// minUsableLinkCount rejects WAF interstitials (typically 0–2 links) without
// false-rejecting minimal real homepages (which have ≥3 nav links).
const minUsableLinkCount = 3

// isUsableFetchResult gates WAF-bypass tiers (FlareSolverr, proxy) against
// interstitials that slip past isBlockPage — an HTTP-200 shell with healthy
// size but near-zero links is still a challenge page, not real content.
func isUsableFetchResult(doc *goquery.Document) bool {
	if doc == nil {
		return false
	}
	if isBlockTitle(strings.ToLower(doc.Find("title").Text())) {
		return false
	}
	rawHTML, err := doc.Html()
	if err != nil {
		return false
	}
	if isWAFShellPage(rawHTML, extractBodyText(doc)) {
		return false
	}
	if doc.Find("a[href]").Length() < minUsableLinkCount {
		return false
	}
	return true
}

// attemptFlareSolverr tries FlareSolverr as a last-resort WAF bypass.
// Returns (result, true) on success, (zero, false) when unavailable or failed.
func attemptFlareSolverr(ctx context.Context, headless *HeadlessAllocator, rawURL, logContext string) (FetchResult, bool) {
	if headless == nil || headless.Flare == nil {
		return FetchResult{}, false
	}
	slog.Info("Escalating to FlareSolverr",
		"url", rawURL,
		"context", logContext,
	)
	fDoc, fErr := headless.Flare.Solve(ctx, rawURL)
	if fErr != nil {
		slog.Warn("FlareSolverr failed", "url", rawURL, "context", logContext, "error", fErr)
		return FetchResult{}, false
	}
	if !isUsableFetchResult(fDoc) {
		slog.Warn("FlareSolverr returned unusable page (WAF shell / too few links)",
			"url", rawURL,
			"context", logContext,
			"link_count", fDoc.Find("a[href]").Length(),
		)
		return FetchResult{}, false
	}
	slog.Info("FlareSolverr bypass succeeded", "url", rawURL, "context", logContext)
	return FetchResult{StatusCode: 200, Doc: fDoc, ScannedVia: "FLARESOLVERR"}, true
}

// attemptProxyRetry re-fetches rawURL through a residential proxy as a last-resort
// WAF bypass. The proxy is only used when all direct-egress tiers have failed,
// minimising proxy bandwidth costs. Returns (result, true) on success.
func attemptProxyRetry(_ context.Context, headless *HeadlessAllocator, rawURL string, loc LocalizationConfig, proxyURL, logContext string) (FetchResult, bool) {
	if proxyURL == "" || headless == nil || !headless.Available() {
		return FetchResult{}, false
	}
	slog.Info("Escalating to residential proxy",
		"url", rawURL,
		"context", logContext,
	)
	// Use an independent context so proxy retries are not starved by time
	// already consumed by direct-egress tiers. The parent scan context may
	// already be near its deadline.
	proxyCtx, proxyCancel := context.WithTimeout(context.Background(), proxyRetryTimeout)
	defer proxyCancel()
	proxyLoc := loc
	proxyLoc.HTTPProxy = proxyURL
	pStatus, pDoc, pErr := headless.FetchPage(proxyCtx, rawURL, proxyLoc, MobileUA())
	if pErr != nil {
		slog.Warn("Proxy retry failed", "url", rawURL, "context", logContext, "error", pErr)
		return FetchResult{}, false
	}
	if !isUsableFetchResult(pDoc) {
		slog.Warn("Proxy retry returned unusable page (WAF shell / too few links)",
			"url", rawURL,
			"context", logContext,
			"link_count", pDoc.Find("a[href]").Length(),
		)
		return FetchResult{}, false
	}
	slog.Info("Proxy retry succeeded", "url", rawURL, "context", logContext)
	return FetchResult{StatusCode: pStatus, Doc: pDoc, ScannedVia: "HEADLESS+PROXY"}, true
}

// attemptAllFallbacks tries FlareSolverr then residential proxy as WAF bypass.
// Returns (result, true) on the first success. Single source of truth for the
// escalation order so all call sites stay consistent.
func attemptAllFallbacks(ctx context.Context, headless *HeadlessAllocator, rawURL string, loc LocalizationConfig, proxyURL, logContext string) (FetchResult, bool) {
	if fr, ok := attemptFlareSolverr(ctx, headless, rawURL, logContext); ok {
		return fr, true
	}
	if fr, ok := attemptProxyRetry(ctx, headless, rawURL, loc, proxyURL, logContext); ok {
		return fr, true
	}
	return FetchResult{}, false
}

// isWAFBlockPage checks title first (cheap O(1) selector) then falls back to
// full body text extraction only when the title is inconclusive.
func isWAFBlockPage(doc *goquery.Document) bool {
	if isBlockTitle(strings.ToLower(doc.Find("title").Text())) {
		return true
	}
	return isBlockPage(extractBodyText(doc))
}

// spaBodyTextThreshold is the maximum visible-text length (in bytes) below which
// a page may be an empty SPA shell awaiting JavaScript rendering.
const spaBodyTextThreshold = 300

// minUsableBodyLen is the minimum visible-text length (in bytes) for a headless
// result to be considered real content rather than a WAF/CAPTCHA block page.
// Typical block pages render to ~100-3000 bytes of visible text.
const minUsableBodyLen = 5000

// FetchResult is the output of hybridFetch.
type FetchResult struct {
	StatusCode int
	Doc        *goquery.Document
	// ScannedVia is "STATIC" when the plain HTTP response was used and
	// "HEADLESS" when chromedp re-fetched the page for JS rendering.
	ScannedVia string
}

// hybridFetch fetches rawURL with a plain HTTP GET.  If the response looks like
// an empty SPA shell (React, Vue, Angular, Next.js …) and headless Chrome is
// available, it re-fetches the URL with a headless browser to obtain the fully
// rendered DOM.
//
// 403 handling — two-attempt escalation strategy:
//  1. Static GET with desktop UA → 403: immediately escalate to headless with
//     mobile UA.  Many WAFs (Akamai, Cloudflare) block generic HTTP clients but
//     pass Chrome with a mobile fingerprint.
//  2. Headless with desktop UA → if still blocked (error or block page): retry
//     headless once with a mobile UA before giving up.
//
// loc is forwarded to the headless browser to prevent geo-redirects and to
// apply region-specific geolocation coordinates + timezone (so the scan sees
// what a real local visitor would).
//
// The function always returns a usable FetchResult on a nil error — headless
// failures are logged and the static result is returned as a fallback.
func hybridFetch(
	ctx context.Context,
	client *http.Client,
	headless *HeadlessAllocator,
	rawURL string,
	loc LocalizationConfig,
	fetchModeHint ...string,
) (FetchResult, error) {
	// SSRF pre-flight is only required for headless Chrome, which bypasses the
	// Go net.Dialer entirely.  Static HTTP requests are protected by the
	// ssrfDialContext hook embedded in the HTTP client transport — no duplicate
	// check needed here.  checkSSRF is called lazily (once, before the first
	// headless FetchPage call) using the closure below.
	ssrfOK := headless == nil || !headless.Available() // if no headless, nothing to pre-check
	ensureSSRF := func() error {
		if ssrfOK {
			return nil
		}
		ssrfOK = true // guard: run at most once per hybridFetch call
		if err := checkSSRF(ctx, rawURL); err != nil {
			return fmt.Errorf("hybridFetch: %w", err)
		}
		return nil
	}

	hint := ""
	if len(fetchModeHint) > 0 {
		hint = fetchModeHint[0]
	}

	// ── Proxy-as-last-resort strategy ────────────────────────────────────
	// When a residential proxy is configured for this region, we do NOT use
	// it for the initial fetch tiers (static, headless, FlareSolverr).
	// Instead, the proxy is held in reserve and only used as a final retry
	// after all direct-egress tiers are exhausted.  This minimises proxy
	// bandwidth costs — only WAF-blocked sites consume proxy GB.
	proxyURL := loc.HTTPProxy
	loc.HTTPProxy = "" // clear proxy for initial direct-egress tiers

	// ── HEADLESS hint: skip static fetch entirely ─────────────────────────
	// When a previous scan detected that this website requires JS rendering,
	// the task carries fetchModeHint="HEADLESS".  Going straight to headless
	// saves the 1-5s static fetch that would fail anyway.
	if hint == "HEADLESS" && headless != nil && headless.Available() {
		if ssrfErr := ensureSSRF(); ssrfErr != nil {
			return FetchResult{}, ssrfErr
		}
		slog.Info("Fetch-mode hint HEADLESS — skipping static, going headless directly",
			"url", rawURL,
		)
		hStatus, hDoc, hErr := headless.FetchPage(ctx, rawURL, loc, "")
		if hErr != nil {
			if fr, ok := attemptAllFallbacks(ctx, headless, rawURL, loc, proxyURL, "after HEADLESS hint fail"); ok {
				return fr, nil
			}
			return FetchResult{}, fmt.Errorf("headless fetch (hint) %s: %w", rawURL, hErr)
		}
		if isWAFBlockPage(hDoc) {
			if fr, ok := attemptAllFallbacks(ctx, headless, rawURL, loc, proxyURL, "HEADLESS hint block page"); ok {
				return fr, nil
			}
			slog.Warn("HEADLESS hint returned WAF block page — all fallbacks failed",
				"url", rawURL,
			)
		}
		return FetchResult{StatusCode: hStatus, Doc: hDoc, ScannedVia: "HEADLESS"}, nil
	}

	status, doc, err := fetchAndParse(ctx, client, rawURL)

	// ── h2-force fallback via tls-client ─────────────────────────────────
	// Some servers (Cloudflare) force HTTP/2 despite ALPN negotiation for
	// HTTP/1.1.  The utls+net/http transport cannot handle h2 frames,
	// producing a "malformed HTTP response / HTTP/1.x transport connection
	// broken" error.  tls-client (fhttp+utls) handles h2 natively.
	if err != nil && isH2ForceError(err) {
		slog.Info("h2-force detected — retrying with tls-client (Chrome h2 profile)",
			"url", rawURL,
		)
		tlsStatus, tlsDoc, tlsErr := fetchWithTLSClient(proxyURL, "", rawURL)
		if tlsErr == nil {
			return FetchResult{StatusCode: tlsStatus, Doc: tlsDoc, ScannedVia: "STATIC"}, nil
		}
		// tls-client also failed — continue to headless escalation with
		// the tls-client error/status.
		slog.Warn("tls-client h2 fallback also failed — continuing to headless", "url", rawURL, "error", tlsErr)
		status = tlsStatus
		err = tlsErr
	}

	// ── 403 / connection-error escalation path ───────────────────────────
	// On a 403 (WAF block) or a connection-level error (e.g. server forcing
	// HTTP/2 despite ALPN http/1.1 — Cloudflare does this), skip the
	// SPA-detection heuristic and go straight to headless with a mobile UA.
	if err != nil && (status == http.StatusForbidden || status == 0) && headless != nil && headless.Available() {
		if ssrfErr := ensureSSRF(); ssrfErr != nil {
			return FetchResult{}, ssrfErr
		}
		slog.Info("403 received — escalating to headless with mobile UA",
			"url", rawURL,
		)
		hStatus, hDoc, hErr := headless.FetchPage(ctx, rawURL, loc, MobileUA())
		if hErr != nil {
			if fr, ok := attemptAllFallbacks(ctx, headless, rawURL, loc, proxyURL, "after 403 headless fail"); ok {
				return fr, nil
			}
			slog.Warn("All bypass tiers failed after 403 — scan will be marked blocked",
				"url", rawURL,
				"error", hErr,
			)
			return FetchResult{}, err
		}
		renderedText := extractBodyText(hDoc)
		if isBlockPage(renderedText) || len(renderedText) < minUsableBodyLen {
			if fr, ok := attemptAllFallbacks(ctx, headless, rawURL, loc, proxyURL, "after 403 block page"); ok {
				return fr, nil
			}
			slog.Warn("All bypass tiers exhausted after 403 — scan will be marked blocked",
				"url", rawURL,
				"rendered_body_len", len(renderedText),
			)
			return FetchResult{}, fmt.Errorf("WAF block page persisted after all bypass tiers for %s", rawURL)
		}
		slog.Info("Headless mobile-UA bypass succeeded after 403",
			"url", rawURL,
			"rendered_body_len", len(renderedText),
		)
		return FetchResult{StatusCode: hStatus, Doc: hDoc, ScannedVia: "HEADLESS"}, nil
	}

	if err != nil {
		return FetchResult{}, err
	}

	bodyText := extractBodyText(doc)

	// ── Normal SPA-shell path ─────────────────────────────────────────────
	if headless != nil && headless.Available() && isSPAShell(doc, bodyText) {
		if ssrfErr := ensureSSRF(); ssrfErr != nil {
			return FetchResult{}, ssrfErr
		}
		slog.Info("SPA shell detected — switching to headless rendering",
			"url", rawURL,
			"static_body_len", len(bodyText),
		)
		// First headless attempt: desktop UA (already randomised inside FetchPage).
		hStatus, hDoc, hErr := headless.FetchPage(ctx, rawURL, loc, "")
		if hErr != nil {
			slog.Warn("Headless fetch failed — falling back to static result",
				"url", rawURL,
				"error", hErr,
			)
			return FetchResult{StatusCode: status, Doc: doc, ScannedVia: "STATIC"}, nil
		}

		// If the rendered page looks like an access-denied / CAPTCHA page,
		// retry once with a mobile UA before accepting the result.
		renderedText := extractBodyText(hDoc)
		if isBlockPage(renderedText) {
			slog.Info("Headless desktop-UA returned block page — retrying with mobile UA",
				"url", rawURL,
			)
			mStatus, mDoc, mErr := headless.FetchPage(ctx, rawURL, loc, MobileUA())
			if mErr == nil && !isBlockPage(extractBodyText(mDoc)) {
				slog.Info("Headless mobile-UA retry succeeded",
					"url", rawURL,
					"rendered_body_len", len(extractBodyText(mDoc)),
				)
				return FetchResult{StatusCode: mStatus, Doc: mDoc, ScannedVia: "HEADLESS"}, nil
			}
			if fr, ok := attemptAllFallbacks(ctx, headless, rawURL, loc, proxyURL, "SPA block page"); ok {
				return fr, nil
			}
			// All tiers exhausted — fall through with the original headless
			// result; at least we have _some_ content.
			slog.Warn("All bypass tiers exhausted on SPA — using desktop result",
				"url", rawURL,
			)
		}

		slog.Info("Headless rendering complete",
			"url", rawURL,
			"rendered_body_len", len(renderedText),
		)
		return FetchResult{StatusCode: hStatus, Doc: hDoc, ScannedVia: "HEADLESS"}, nil
	}

	return FetchResult{StatusCode: status, Doc: doc, ScannedVia: "STATIC"}, nil
}

// isBlockPage heuristically detects WAF / CAPTCHA block pages by looking for
// known phrases in the visible body text.  False positives are acceptable here
// because we only use the result to decide whether to retry with a mobile UA —
// a false positive wastes one extra headless tab but causes no data loss.
func isBlockPage(bodyText string) bool {
	lower := strings.ToLower(bodyText)
	for _, marker := range []string{
		"access denied",
		"403 forbidden",
		"you have been blocked",
		"enable javascript and cookies",
		"checking your browser",
		"please verify you are a human",
		"ddos protection",
		"ray id",       // Cloudflare Ray ID footer
		"cloudflare",   // Cloudflare brand on challenge/block pages
		"reference #",  // Akamai error page pattern (reference #18.xxx)
		"reference id", // Akamai alternate phrasing
		"akamai technologies",
	} {
		if strings.Contains(lower, marker) {
			return true
		}
	}
	return false
}

// isBlockTitle heuristically detects WAF block pages by their <title> text,
// which is often the clearest signal (e.g. "Access Denied | shop").
// Kept separate from isBlockPage so callers can check title and body
// independently without paying for a full body-text extraction twice.
func isBlockTitle(lowerTitle string) bool {
	for _, marker := range []string{
		"access denied",
		"403 forbidden",
		"blocked",
		"cloudflare",
		"attention required",
		"just a moment", // Cloudflare interstitial title
	} {
		if strings.Contains(lowerTitle, marker) {
			return true
		}
	}
	return false
}

// isSPAShell returns true when both conditions hold:
//  1. The visible body text is shorter than spaBodyTextThreshold — indicating
//     that the page was served as a near-empty HTML shell.
//  2. The document contains at least one known SPA mount-point element
//     (React #root, Vue #app, Next.js #__next, Nuxt #__nuxt, etc.).
//
// This heuristic avoids triggering headless mode for small-but-static pages
// (e.g. redirects, maintenance pages) that legitimately have little content.
func isSPAShell(doc *goquery.Document, bodyText string) bool {
	if len(strings.TrimSpace(bodyText)) >= spaBodyTextThreshold {
		return false
	}
	for _, sel := range []string{
		`#app`, `#root`, `#__nuxt`, `#__next`,
		`[data-reactroot]`, `[data-v-app]`,
		`div[ng-app]`, `div[ng-version]`,
	} {
		if doc.Find(sel).Length() > 0 {
			return true
		}
	}
	return false
}
