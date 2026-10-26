package scanner

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// accessibilityTimeout caps how long the homepage fetch in checkHomepageAccessibility
// may block. It is intentionally shorter than fetchTimeout (45 s) so the
// accessibility pre-check never consumes the entire 60-second scan budget,
// leaving adequate headroom for the mandatory targeted-page content scan.
const accessibilityTimeout = 20 * time.Second

// AccessibilityResult holds the outcome of checking whether task.TargetURL
// is directly reachable via an <a href> link on the website homepage.
//
// This is a read-only enrichment added to targeted-mode scan results. It does
// NOT affect the compliance status — content compliance (keyword presence on
// the target page) is the primary assessment axis. A site can satisfy keyword
// requirements even when the compliance page is not linked from root (e.g.
// accessible only through JS-rendered navigation or a lazy-loaded footer).
type AccessibilityResult struct {
	// IsAccessible is true when at least one <a href> on the homepage resolves
	// to the same canonical URL as task.TargetURL.
	IsAccessible bool

	// MatchedHref is the raw href attribute value on the homepage that resolved
	// to the target URL. Empty string when IsAccessible is false.
	MatchedHref string

	// CheckedLinksCount is the total number of distinct <a href> elements
	// extracted from the homepage DOM.
	CheckedLinksCount int

	// HomepageStatus is the HTTP status code returned by the homepage fetch.
	// Zero means the fetch failed entirely (network error or WAF block).
	HomepageStatus int

	// ScannedVia mirrors the FetchResult field: "STATIC", "HEADLESS",
	// "HEADLESS_DESKTOP", or any of the above suffixed with "+CACHED".
	ScannedVia string

	// FetchErr is non-nil when the homepage could not be fetched. When set,
	// IsAccessible is always false and all numeric fields are zero.
	FetchErr error
}

// canonicalizeURL returns the canonical form of rawURL for reliable equality
// comparisons between href values and user-configured target URLs.
//
// Normalizations applied (in order):
//  1. Trim surrounding whitespace
//  2. Lowercase scheme and host
//  3. Strip scheme-default ports  (http:80, https:443)
//  4. Strip www. host prefix      (www.example.com → example.com)
//  5. Remove URL fragment         (#section stripped entirely)
//  6. Strip trailing slash from non-root paths  (/about/ → /about)
//
// Returns a non-nil error for non-http/https URLs or unparseable input.
func canonicalizeURL(rawURL string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil {
		return "", fmt.Errorf("parse %q: %w", rawURL, err)
	}
	switch u.Scheme {
	case "http", "https":
		// valid
	default:
		return "", fmt.Errorf("unsupported scheme %q in %q", u.Scheme, rawURL)
	}

	u.Scheme = strings.ToLower(u.Scheme)
	host := strings.ToLower(u.Hostname())
	port := u.Port()

	// Remove scheme-default ports so https://example.com:443/ == https://example.com/
	if (u.Scheme == "https" && port == "443") || (u.Scheme == "http" && port == "80") {
		port = ""
	}
	if port != "" {
		u.Host = host + ":" + port
	} else {
		u.Host = host
	}

	// Collapse www variant: www.example.com == example.com
	if strings.HasPrefix(u.Host, "www.") {
		u.Host = u.Host[4:]
	}

	// Fragment identifies a section within a page, not the page itself.
	u.Fragment = ""
	u.RawFragment = ""

	// Normalise trailing slash on non-root paths: /about/ → /about.
	// The root path "/" is preserved to keep the URL syntactically valid.
	if len(u.Path) > 1 {
		u.Path = strings.TrimRight(u.Path, "/")
	}

	return u.String(), nil
}

// resolveAndCompareURL reports whether href, when resolved against baseRaw,
// refers to the same web resource as targetRaw after URL canonicalization.
//
// It handles the full spectrum of href forms found in live compliance pages:
//   - Relative path:             /datenschutz
//   - Root-relative:             //example.com/datenschutz
//   - Absolute with fragment:    https://example.com/datenschutz#section
//   - Trailing-slash variant:    /datenschutz/  vs  /datenschutz
//   - www prefix difference:     www.example.com  vs  example.com
//   - Explicit default port:     https://example.com:443/d  vs  https://example.com/d
//
// Returns false on any parse or resolution error — treat as "no match".
func resolveAndCompareURL(baseRaw, href, targetRaw string) bool {
	base, err := url.Parse(baseRaw)
	if err != nil {
		return false
	}
	resolved := resolveRef(base, href)
	if resolved == "" {
		return false
	}
	canonResolved, err := canonicalizeURL(resolved)
	if err != nil {
		return false
	}
	canonTarget, err := canonicalizeURL(targetRaw)
	if err != nil {
		return false
	}
	return canonResolved == canonTarget
}

// checkHomepageAccessibility fetches task.WebsiteURL and reports whether
// task.TargetURL appears as a navigable <a href> link in the page DOM.
//
// Cache-first strategy: the HomepageCrawlCache is checked before any live
// network request.  This is critical for batch scans — when several compliance
// monitors share the same domain, the first targeted monitor fetches the page
// and the rest receive a free cache hit (~2 ms vs ~45 s for headless).
//
// On a successful live fetch the result is written into the cache using the
// same batchID-scoped key used by scanHomepage, so the two scan modes share a
// single cache namespace and never double-fetch the same page within a batch.
//
// The function is intentionally error-free from the caller's perspective:
// when the homepage cannot be fetched, AccessibilityResult.FetchErr is set
// and IsAccessible is false — the caller should surface this as "unknown".
func (c *ComplianceScanner) checkHomepageAccessibility(
	ctx context.Context,
	client *http.Client,
	task Task,
) AccessibilityResult {
	loc := GetLocalization(task.RegionCode)
	domain := apexDomain(task.WebsiteURL)
	key := cacheKey(task.BatchID, domain)

	var fr FetchResult

	// ── Cache lookup ──────────────────────────────────────────────────────────
	if cached, ok := c.cache.get(key); ok {
		doc, err := cached.reparse()
		if err == nil {
			fr = FetchResult{
				StatusCode: cached.statusCode,
				Doc:        doc,
				ScannedVia: cached.scannedVia + "+CACHED",
			}
			slog.Debug("HomepageCrawlCache HIT (accessibility pre-check)",
				"monitor_id", task.MonitorID,
				"domain", domain,
				"key", key,
			)
		}
	}

	// ── Live fetch on cache miss ──────────────────────────────────────────────
	// Use ctx directly — the caller (scanTargetedPage) has already bounded it
	// with accessibilityTimeout, so we avoid double-wrapping the deadline.
	//
	// Pass nil for headless: accessibility is purely informational, so burning
	// 15–25 s on headless WAF-bypass attempts for the homepage is wasteful and
	// steals timeout budget from the mandatory target-page fetch that follows.
	// A static-only fetch is sufficient — if the homepage returns 403 we simply
	// record accessibility as "unknown" and let the real scan proceed.
	if fr.Doc == nil {
		var err error
		fr, err = hybridFetch(ctx, client, nil, task.WebsiteURL, loc)
		if err != nil {
			return AccessibilityResult{
				FetchErr: fmt.Errorf("accessibility: fetch homepage %q: %w", task.WebsiteURL, err),
			}
		}

		// Always populate cache — even WAF block pages benefit subsequent
		// monitors by avoiding repeated 30-45s headless fetches.
		if rawHTML, htmlErr := fr.Doc.Html(); htmlErr == nil {
			c.cache.put(key, &crawlEntry{
				rawHTML:    rawHTML,
				statusCode: fr.StatusCode,
				scannedVia: fr.ScannedVia,
				cachedAt:   time.Now(),
			})
			slog.Debug("HomepageCrawlCache populated (accessibility pre-check)",
				"monitor_id", task.MonitorID,
				"key", key,
				"scanned_via", fr.ScannedVia,
			)
		}
	}

	// ── Link scan ─────────────────────────────────────────────────────────────
	links := extractAllLinks(fr.Doc, task.WebsiteURL)
	for _, l := range links {
		if resolveAndCompareURL(task.WebsiteURL, l.Href, task.TargetURL) {
			slog.Info("Accessibility check: target URL found on homepage",
				"monitor_id", task.MonitorID,
				"target_url", task.TargetURL,
				"matched_href", l.Href,
				"in_footer", l.InFooter,
				"scanned_via", fr.ScannedVia,
			)
			return AccessibilityResult{
				IsAccessible:      true,
				MatchedHref:       l.Href,
				CheckedLinksCount: len(links),
				HomepageStatus:    fr.StatusCode,
				ScannedVia:        fr.ScannedVia,
			}
		}
	}

	slog.Info("Accessibility check: target URL NOT found on homepage",
		"monitor_id", task.MonitorID,
		"target_url", task.TargetURL,
		"links_checked", len(links),
		"scanned_via", fr.ScannedVia,
	)
	return AccessibilityResult{
		IsAccessible:      false,
		CheckedLinksCount: len(links),
		HomepageStatus:    fr.StatusCode,
		ScannedVia:        fr.ScannedVia,
	}
}
