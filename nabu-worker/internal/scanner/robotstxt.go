package scanner

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// robotsCache stores parsed robots.txt rules per domain with a 24-hour TTL.
// The cache is safe for concurrent access from multiple scanner goroutines.
var robotsCache = &robotsCacheStore{
	entries: make(map[string]*robotsCacheEntry),
}

const (
	robotsTTL          = 24 * time.Hour
	robotsFetchTimeout = 10 * time.Second
)

// robotsRule represents a single Allow or Disallow directive.
type robotsRule struct {
	path    string
	allowed bool // true = Allow, false = Disallow
}

// robotsRules holds the parsed rules for a single domain.
type robotsRules struct {
	rules   []robotsRule
	rawText string
}

// isAllowed checks whether the given URL path is permitted by the rules.
// Uses longest-match precedence (Google-style): the most specific matching
// rule wins.  If no rules match, access is allowed (default-open).
func (r *robotsRules) isAllowed(urlPath string) bool {
	if r == nil || len(r.rules) == 0 {
		return true
	}

	// Find the longest matching rule (Google-style precedence).
	bestLen := -1
	allowed := true
	for _, rule := range r.rules {
		if strings.HasPrefix(urlPath, rule.path) && len(rule.path) > bestLen {
			bestLen = len(rule.path)
			allowed = rule.allowed
		}
	}
	return allowed
}

type robotsCacheEntry struct {
	rules     *robotsRules
	fetchedAt time.Time
	err       error // non-nil if fetch failed — we allow access on error
}

type robotsCacheStore struct {
	mu      sync.RWMutex
	entries map[string]*robotsCacheEntry
}

// get returns cached rules for domain, or nil if not cached / expired.
func (c *robotsCacheStore) get(domain string) *robotsCacheEntry {
	c.mu.RLock()
	defer c.mu.RUnlock()
	e, ok := c.entries[domain]
	if !ok || time.Since(e.fetchedAt) > robotsTTL {
		return nil
	}
	return e
}

func (c *robotsCacheStore) set(domain string, entry *robotsCacheEntry) {
	c.mu.Lock()
	defer c.mu.Unlock()
	// Evict expired entries periodically to prevent unbounded growth.
	if len(c.entries) > 500 {
		for k, v := range c.entries {
			if time.Since(v.fetchedAt) > robotsTTL {
				delete(c.entries, k)
			}
		}
	}
	c.entries[domain] = entry
}

// parseRobotsTxt reads a robots.txt body and extracts rules for User-agent: *
// (we don't register a custom bot name, so * is what applies to us).
func parseRobotsTxt(body io.Reader) *robotsRules {
	scanner := bufio.NewScanner(body)
	var rules []robotsRule
	inWildcard := false

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())

		// Skip comments and empty lines.
		if idx := strings.IndexByte(line, '#'); idx >= 0 {
			line = strings.TrimSpace(line[:idx])
		}
		if line == "" {
			continue
		}

		lower := strings.ToLower(line)

		// Detect User-agent directives.
		if strings.HasPrefix(lower, "user-agent:") {
			agent := strings.TrimSpace(line[len("user-agent:"):])
			inWildcard = agent == "*"
			continue
		}

		if !inWildcard {
			continue
		}

		// Parse Allow / Disallow directives.
		if strings.HasPrefix(lower, "disallow:") {
			path := strings.TrimSpace(line[len("disallow:"):])
			if path != "" {
				rules = append(rules, robotsRule{path: path, allowed: false})
			}
			continue
		}
		if strings.HasPrefix(lower, "allow:") {
			path := strings.TrimSpace(line[len("allow:"):])
			if path != "" {
				rules = append(rules, robotsRule{path: path, allowed: true})
			}
			continue
		}
	}

	return &robotsRules{rules: rules}
}

// robotsClient is a shared HTTP client for robots.txt fetches.  It uses the
// same uTLS transport and SSRF-protected dialer as the crawl client.
var robotsClient = func() *http.Client {
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	ssrfDial := ssrfDialContext(dialer)
	return &http.Client{
		Timeout: robotsFetchTimeout,
		Transport: &http.Transport{
			DialContext:           ssrfDial,
			DialTLSContext:        utlsDialTLS(ssrfDial),
			ResponseHeaderTimeout: 3 * time.Second,
		},
	}
}()

// fetchRobotsTxt fetches and parses robots.txt for the given base URL.
func fetchRobotsTxt(ctx context.Context, baseURL string) (*robotsRules, error) {
	robotsURL := strings.TrimRight(baseURL, "/") + "/robots.txt"

	reqCtx, cancel := context.WithTimeout(ctx, robotsFetchTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, robotsURL, nil)
	if err != nil {
		return nil, fmt.Errorf("build robots.txt request: %w", err)
	}

	resp, err := robotsClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("fetch robots.txt: %w", err)
	}
	defer resp.Body.Close()

	// 404 / 410 / 5xx → no robots.txt → everything allowed.
	if resp.StatusCode != http.StatusOK {
		return &robotsRules{}, nil
	}

	// Limit read to 512 KB — robots.txt should never be this large.
	bodyBytes, err := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
	if err != nil {
		return &robotsRules{}, nil
	}
	bodyStr := string(bodyBytes)
	rules := parseRobotsTxt(strings.NewReader(bodyStr))
	rules.rawText = bodyStr
	return rules, nil
}

// CheckRobotsTxt returns true if the given URL is allowed by the domain's
// robots.txt.  Results are cached for 24 hours per domain.
//
// On fetch errors, access is allowed (fail-open) — a broken robots.txt should
// not block compliance scanning.
func CheckRobotsTxt(ctx context.Context, targetURL string) bool {
	u, err := url.Parse(targetURL)
	if err != nil || u.Host == "" {
		return true // malformed URL, skip robots check
	}
	domain := u.Host
	baseURL := u.Scheme + "://" + u.Host
	urlPath := u.Path
	if urlPath == "" {
		urlPath = "/"
	}

	// Check cache.
	if entry := robotsCache.get(domain); entry != nil {
		if entry.err != nil {
			return true // fail-open
		}
		return entry.rules.isAllowed(urlPath)
	}

	// Fetch and cache.
	rules, err := fetchRobotsTxt(ctx, baseURL)
	robotsCache.set(domain, &robotsCacheEntry{
		rules:     rules,
		fetchedAt: time.Now(),
		err:       err,
	})

	if err != nil {
		slog.Warn("robots.txt fetch failed — allowing scan (fail-open)",
			"domain", domain,
			"error", err,
		)
		return true
	}

	allowed := rules.isAllowed(urlPath)
	if !allowed {
		slog.Info("robots.txt disallows path — scan will be skipped",
			"domain", domain,
			"path", urlPath,
		)
	}
	return allowed
}

// GetRobotsTxtContent returns the cached raw robots.txt content for the
// given URL's domain. Returns empty string if not cached — callers should
// ensure CheckRobotsTxt has been called first to populate the cache.
func GetRobotsTxtContent(_ context.Context, targetURL string) string {
	u, err := url.Parse(targetURL)
	if err != nil || u.Host == "" {
		return ""
	}
	entry := robotsCache.get(u.Host)
	if entry == nil || entry.err != nil || entry.rules == nil {
		return ""
	}
	return entry.rules.rawText
}
