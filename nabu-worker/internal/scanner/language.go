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

const englishFetchTimeout = 12 * time.Second

// englishPathCandidates are path segments injected after the hostname to
// locate the English version of a website, tried in priority order.
var englishPathCandidates = []string{"/en/", "/en", "/en-us/", "/en-gb/", "/english/"}

// englishQueryCandidates are query-string parameters tried on the homepage URL
// to request the English locale when path-based switching is not present.
var englishQueryCandidates = []string{"?lang=en", "?hl=en", "?locale=en", "?language=en"}

// LangCheckResult holds the outcome of a language-consistency check against
// the English version of a page.
type LangCheckResult struct {
	EnglishURL      string
	FoundKeywords   []string
	MissingKeywords []string
	Evidence        []KeywordEvidence
}

// findEnglishURL probes a website for its English-language URL using three
// strategies, in order:
//
//  1. HTML <link rel="alternate" hreflang="en*"> in the <head>
//  2. Common /en/ path prefixes appended to the origin
//  3. Query-string locale parameters appended to the homepage URL
//
// Return the first URL that responds with a 2xx status, or "" if none found.
func findEnglishURL(
	ctx context.Context,
	client *http.Client,
	doc *goquery.Document,
	baseURL string,
) string {
	// Strategy 1: hreflang alternate link tag
	var hreflangURL string
	doc.Find(`link[rel="alternate"][hreflang]`).Each(func(_ int, s *goquery.Selection) {
		if hreflangURL != "" {
			return
		}
		lang, _ := s.Attr("hreflang")
		if strings.HasPrefix(strings.ToLower(lang), "en") {
			if href, ok := s.Attr("href"); ok && href != "" {
				hreflangURL = href
			}
		}
	})
	if hreflangURL != "" {
		if pingURL(ctx, client, hreflangURL) {
			slog.Debug("Language check: found English URL via hreflang", "url", hreflangURL)
			return hreflangURL
		}
	}

	// Strategy 2: /en/ path prefix
	parsed, err := url.Parse(baseURL)
	if err == nil {
		origin := parsed.Scheme + "://" + parsed.Host
		for _, path := range englishPathCandidates {
			candidate := origin + path
			if pingURL(ctx, client, candidate) {
				slog.Debug("Language check: found English URL via path", "url", candidate)
				return candidate
			}
		}
	}

	// Strategy 3: query-string locale param on the homepage
	cleanBase := strings.SplitN(baseURL, "?", 2)[0]
	for _, qp := range englishQueryCandidates {
		candidate := cleanBase + qp
		if pingURL(ctx, client, candidate) {
			slog.Debug("Language check: found English URL via query param", "url", candidate)
			return candidate
		}
	}

	return ""
}

// pingURL performs a HEAD request (falling back to GET on 405) to check
// whether a URL is reachable.  Returns true on a 2xx or 3xx response.
func pingURL(ctx context.Context, client *http.Client, rawURL string) bool {
	reqCtx, cancel := context.WithTimeout(ctx, headCheckTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodHead, rawURL, nil)
	if err != nil {
		return false
	}
	resp, err := client.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusMethodNotAllowed {
		req2, err2 := http.NewRequestWithContext(reqCtx, http.MethodGet, rawURL, nil)
		if err2 != nil {
			return false
		}
		resp2, err3 := client.Do(req2)
		if err3 != nil {
			return false
		}
		defer resp2.Body.Close()
		return resp2.StatusCode < 400
	}
	return resp.StatusCode < 400
}

// buildEnglishClient creates an HTTP client that sends neutral English headers.
// Unlike the regional crawl client it carries no native-language cookies or
// Accept-Language preferences, ensuring we receive the international/English
// version of the page rather than a geo-redirected one.
func buildEnglishClient(proxyURL string) (*http.Client, error) {
	return buildCrawlClientWithConfig(proxyURL, LocalizationConfig{
		AcceptLanguage: "en-US,en;q=0.9",
		Referer:        "https://www.google.com/",
		// No LangCookie — deliberately omitted so the server uses its default
		// (usually English) rather than the previously-set native locale.
	})
}

// checkLanguageConsistency runs a secondary English-language scan when the
// primary (native-language) scan has missing keywords.  It tries to discover
// the English URL of the site and searches for the English-language equivalents
// of the missing keywords.
//
// Returns nil when:
//   - No fallback keywords were configured
//   - No keywords are missing from the native scan
//   - No reachable English URL could be found
//   - The English page also lacks the fallback keywords
func checkLanguageConsistency(
	ctx context.Context,
	nativeClient *http.Client, // used only for the English-URL probe (shares UA fingerprint)
	headless *HeadlessAllocator,
	nativeDoc *goquery.Document,
	baseURL string,
	fallbackKeywords []string,
	missingNativeKeywords []string,
) *LangCheckResult {
	if len(fallbackKeywords) == 0 || len(missingNativeKeywords) == 0 {
		return nil
	}

	// Use the native client for the URL probe so the User-Agent is consistent.
	englishURL := findEnglishURL(ctx, nativeClient, nativeDoc, baseURL)
	if englishURL == "" {
		slog.Info("Language consistency check: no English URL discovered", "base", baseURL)
		return nil
	}

	slog.Info("Language consistency check: scanning English version",
		"base", baseURL,
		"english_url", englishURL,
	)

	enClient, err := buildEnglishClient("")
	if err != nil {
		slog.Warn("Language consistency: could not build English HTTP client", "err", err)
		return nil
	}

	fetchCtx, cancel := context.WithTimeout(ctx, englishFetchTimeout)
	defer cancel()

	// Bare en-US locale — no region proxy override. This is a consistency
	// probe, not a geography-sensitive audit, so it runs through direct
	// egress regardless of how the primary scan was routed.
	fr, err := hybridFetch(fetchCtx, enClient, headless, englishURL, LocalizationConfig{
		AcceptLanguage: "en-US,en;q=0.9",
		Latitude:       40.7128, Longitude: -74.0060, TimeZone: "America/New_York",
	})
	if err != nil {
		slog.Warn("Language consistency: English page fetch failed",
			"url", englishURL, "err", err)
		return nil
	}

	// Run the same compliance check logic on the English page.
	links := extractAllLinks(fr.Doc, englishURL)
	cr := checkComplianceV2(ctx, enClient, links, fallbackKeywords, nil)

	// Level-1 deep crawl for any still-missing fallback keywords.
	promoted := make(map[string]struct{})
	deepCrawlFailed := make(map[string]struct{})
	for _, missingKW := range cr.MissingKeywords {
		if ev := deepCrawlMissing(ctx, enClient, links, missingKW, deepCrawlFailed); ev != nil {
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

	if len(cr.FoundKeywords) == 0 {
		slog.Info("Language consistency: English page also lacks fallback keywords",
			"url", englishURL, "fallback_keywords", fallbackKeywords)
		return nil
	}

	// Stamp evidence with the English URL when no specific sub-page is recorded.
	for i := range cr.Evidence {
		if cr.Evidence[i].FoundAt == "" {
			cr.Evidence[i].FoundAt = englishURL
		}
	}

	return &LangCheckResult{
		EnglishURL:      englishURL,
		FoundKeywords:   cr.FoundKeywords,
		MissingKeywords: cr.MissingKeywords,
		Evidence:        cr.Evidence,
	}
}
