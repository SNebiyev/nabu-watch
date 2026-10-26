package scanner

import (
	"context"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

const (
	deepCrawlTimeout = 8 * time.Second // per sub-page fetch during deep crawl
	maxDeepCrawlURLs = 5               // cap sub-pages visited per missing keyword
)

// deepCrawlPatterns are href substrings used to identify sub-pages that are
// likely to contain legal content when a keyword is not on the homepage.
var deepCrawlPatterns = []string{
	// Universal legal page patterns
	"cookie", "privacy", "legal", "terms", "gdpr", "conditions",
	// German-language legal pages
	"datenschutz", "impressum", "dsgvo", "datenschutzanfrage",
	// GDPR data-request specific pages
	"betroffenen", "meine-daten", "privacy-center", "datenschutz-center",
	"datenschutzeinstellungen", "anfrage", "auskunft",
	// DPO / contact pages that often contain GDPR request info
	"kontakt", "contact",
}

// pageLink represents a single <a href> element extracted from a page.
type pageLink struct {
	Text     string
	Href     string
	InFooter bool // true when the <a> element has a <footer> ancestor
}

// complianceResult holds the three-category outcome of a homepage compliance check.
type complianceResult struct {
	FoundKeywords   []string    // keywords with at least one working (2xx/3xx) link
	BrokenKeywords  []string    // keywords found in links but every link is 4xx/network error
	MissingKeywords []string    // keywords not found in any link text or href
	FoundLinks      []FoundLink // all verified candidate links (for diagnostics)
}

// evidenceComplianceResult extends complianceResult with per-keyword evidence.
type evidenceComplianceResult struct {
	FoundKeywords   []string
	BrokenKeywords  []string
	MissingKeywords []string
	FoundLinks      []FoundLink
	Evidence        []KeywordEvidence
}

// extractAllLinks parses all <a href> elements from doc, resolves them against
// baseRawURL, deduplicates by absolute URL, and returns a slice of pageLinks.
//
// NOTE: "#" and "javascript:" hrefs are intentionally kept — CMPs (Sourcepoint,
// OneTrust) use them as modal triggers (e.g. href="#sp_privacy_manager").
// headCheck treats them as instantly-valid (status=200, broken=false).
func extractAllLinks(doc *goquery.Document, baseRawURL string) []pageLink {
	base, err := url.Parse(baseRawURL)
	if err != nil {
		slog.Warn("extractAllLinks: invalid base URL", "url", baseRawURL, "error", err)
		return nil
	}

	seen := make(map[string]struct{})
	var links []pageLink

	doc.Find("a[href]").Each(func(_ int, s *goquery.Selection) {
		href, _ := s.Attr("href")
		href = strings.TrimSpace(href)

		// Skip truly non-navigable and untrackable schemes.
		for _, skip := range []string{"mailto:", "tel:", "data:"} {
			if strings.HasPrefix(href, skip) {
				return
			}
		}
		if href == "" {
			return
		}

		// For on-page actions ("#" / "javascript:") use the raw href as the key
		// so they are deduplicated but never passed through url.ResolveReference
		// (which would mangle them).
		isOnPage := strings.HasPrefix(href, "#") || strings.HasPrefix(strings.ToLower(href), "javascript:")
		var absURL string
		if isOnPage {
			absURL = href
		} else {
			absURL = resolveRef(base, href)
			if absURL == "" {
				return
			}
		}

		// Deduplicate by absolute URL (or raw on-page action)
		if _, dup := seen[absURL]; dup {
			return
		}
		seen[absURL] = struct{}{}

		// Normalise whitespace in link text
		text := strings.Join(strings.Fields(s.Text()), " ")
		inFooter := s.Closest("footer").Length() > 0
		links = append(links, pageLink{Text: text, Href: absURL, InFooter: inFooter})
	})

	return links
}

// extractBodyText returns the full visible text of the <body> element with
// whitespace normalised.  Falls back to the whole document if no <body> exists.
func extractBodyText(doc *goquery.Document) string {
	body := doc.Find("body")
	if body.Length() == 0 {
		body = doc.Selection
	}
	return strings.Join(strings.Fields(body.Text()), " ")
}

// matchKeywordsInText performs a case-insensitive whole-word search for each
// keyword and classifies each as found or missing.
func matchKeywordsInText(text string, keywords []string) (found, missing []string) {
	for _, kw := range keywords {
		if containsWholeWord(text, kw) {
			found = append(found, kw)
		} else {
			missing = append(missing, kw)
		}
	}
	return
}

// checkComplianceV2 is the Discovery v2 compliance check with per-keyword evidence.
// In addition to keyword classification, it:
//   - scores each found keyword by link context (footer bonus)
//   - captures an evidence context string for display in the UI
//   - short-circuits fallback keywords once any native keyword is confirmed
//
// nativeKeywords is the subset of keywords that are the primary (native-language)
// terms for this monitor.  Once one of them is found, all remaining keywords that
// are NOT in nativeKeywords are skipped — they are English synonyms that are only
// needed when the native language search fails completely.
// Pass nil / empty slice to disable short-circuiting (behaves like the old version).
func checkComplianceV2(
	ctx context.Context,
	client *http.Client,
	links []pageLink,
	keywords []string,
	nativeKeywords []string,
) evidenceComplianceResult {
	var result evidenceComplianceResult

	nativeSet := make(map[string]bool, len(nativeKeywords))
	for _, kw := range nativeKeywords {
		nativeSet[kw] = true
	}
	nativeFound := false // flipped to true the first time a native keyword is confirmed

	// Cache headCheck results so the same URL is never fetched twice within one
	// scan. Without this, N keywords matching the same href (e.g. "Cookie",
	// "Cookie-Richtlinie", "Cookie-Einstellungen" all pointing at /cookies)
	// each trigger an independent HEAD request — flooding WAF-protected sites
	// with dozens of redundant 403s.
	headCheckCache := make(map[string]int)
	cachedHeadCheck := func(href string) int {
		if status, ok := headCheckCache[href]; ok {
			return status
		}
		status := headCheck(ctx, client, href)
		headCheckCache[href] = status
		return status
	}

	for _, keyword := range keywords {
		// Short-circuit: if we already have a native term, skip every fallback
		// keyword — they are synonyms and their absence is not a compliance gap.
		if nativeFound && !nativeSet[keyword] {
			continue
		}
		normKW := normalizeForMatch(keyword)

		var candidates []pageLink
		for _, l := range links {
			if containsWholeWord(normalizeForMatch(l.Text), normKW) ||
				containsWholeWord(normalizeForMatch(l.Href), normKW) {
				candidates = append(candidates, l)
			}
		}

		// href-text-node fallback — if no link matched through text/href
		// whole-word check, promote any link whose href contains the normalized
		// keyword as a whole word. This catches cases like href="/privacy_policy"
		// with empty or unrelated anchor text (common in icon-only footer links).
		if len(candidates) == 0 {
			for _, l := range links {
				if containsWholeWord(normalizeForMatch(l.Href), normKW) {
					candidates = append(candidates, l)
				}
			}
			if len(candidates) > 0 {
				slog.Debug("Keyword matched via href-slug fallback",
					"keyword", keyword, "href", candidates[0].Href)
			}
		}

		if len(candidates) == 0 {
			slog.Debug("Keyword not found in any link", "keyword", keyword, "total_links", len(links))
			result.MissingKeywords = append(result.MissingKeywords, keyword)
			continue
		}

		workingFound := false
		for _, candidate := range candidates {
			// On-page modal triggers ("#" / "javascript:") are always valid —
			// they open a consent manager panel, not a separate URL.
			// Skip headCheck entirely and treat them as healthy (status=200).
			var status int
			var broken bool
			isOnPage := strings.HasPrefix(candidate.Href, "#") ||
				strings.HasPrefix(strings.ToLower(candidate.Href), "javascript:")
			if isOnPage {
				status = 200
				broken = false
			} else {
				status = cachedHeadCheck(candidate.Href)
				broken = status == 0 || status >= 400
			}

			result.FoundLinks = append(result.FoundLinks, FoundLink{
				Keyword: keyword,
				Text:    candidate.Text,
				Href:    candidate.Href,
				Status:  status,
				Broken:  broken,
			})

			slog.Debug("Keyword link verified",
				"keyword", keyword,
				"href", candidate.Href,
				"status", status,
				"broken", broken,
				"on_page", isOnPage,
			)

			if !broken {
				workingFound = true
				score, location := 5, "link"
				if candidate.InFooter {
					score, location = 7, "footer_link"
				}
				ctx := candidate.Text
				if ctx == "" {
					ctx = candidate.Href
				}
				result.Evidence = append(result.Evidence, KeywordEvidence{
					Keyword:         keyword,
					Score:           score,
					Location:        location,
					EvidenceContext: ctx,
					FoundAt:         candidate.Href,
				})
				break
			}
		}

		if workingFound {
			result.FoundKeywords = append(result.FoundKeywords, keyword)
			// Flip the short-circuit flag so subsequent fallback keywords are skipped.
			if nativeSet[keyword] {
				nativeFound = true
			}
		} else {
			slog.Warn("Keyword found but all links are broken",
				"keyword", keyword, "candidates", len(candidates))
			result.BrokenKeywords = append(result.BrokenKeywords, keyword)
		}
	}

	return result
}

// deepCrawlMissing performs a Level-1 deep crawl for a single missing keyword.
// failedURLs is shared across keyword iterations — URLs that returned errors
// are skipped on subsequent calls to avoid redundant 403 floods against WAF-
// protected domains.
func deepCrawlMissing(
	ctx context.Context,
	client *http.Client,
	links []pageLink,
	keyword string,
	failedURLs map[string]struct{},
) *KeywordEvidence {
	seen := make(map[string]struct{})
	var candidates []string
	for _, l := range links {
		if _, failed := failedURLs[l.Href]; failed {
			continue
		}
		hrefLower := strings.ToLower(l.Href)
		for _, pat := range deepCrawlPatterns {
			if strings.Contains(hrefLower, pat) {
				if _, dup := seen[l.Href]; !dup {
					seen[l.Href] = struct{}{}
					candidates = append(candidates, l.Href)
				}
				break
			}
		}
		if len(candidates) >= maxDeepCrawlURLs {
			break
		}
	}

	for _, pageURL := range candidates {
		fetchCtx, cancel := context.WithTimeout(ctx, deepCrawlTimeout)
		_, doc, err := fetchAndParse(fetchCtx, client, pageURL)
		cancel()
		if err != nil {
			failedURLs[pageURL] = struct{}{}
			slog.Debug("Deep crawl fetch failed",
				"url", pageURL, "keyword", keyword, "error", err)
			continue
		}
		pageText := extractBodyText(doc)
		if containsWholeWord(pageText, keyword) {
			idx := strings.Index(strings.ToLower(pageText), strings.ToLower(keyword))
			snippet := extractSnippet(pageText, keyword, idx)
			slog.Info("Keyword found via deep crawl (Level 1)",
				"keyword", keyword, "url", pageURL)
			return &KeywordEvidence{
				Keyword:         keyword,
				Score:           3,
				Location:        "deep_link",
				EvidenceContext: snippet,
				FoundAt:         pageURL,
			}
		}
	}
	return nil
}
