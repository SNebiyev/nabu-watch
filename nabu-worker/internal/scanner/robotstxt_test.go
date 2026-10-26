package scanner

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestParseRobotsTxt_WildcardDisallow(t *testing.T) {
	body := `
User-agent: *
Disallow: /private/
Disallow: /admin
Allow: /private/public/
`
	rules := parseRobotsTxt(strings.NewReader(body))

	tests := []struct {
		path    string
		allowed bool
	}{
		{"/", true},
		{"/about", true},
		{"/private/secret", false},
		{"/private/public/page", true}, // Allow overrides (longer match)
		{"/admin", false},
		{"/admin/settings", false},
		{"/administrator", false}, // prefix match
	}

	for _, tt := range tests {
		got := rules.isAllowed(tt.path)
		if got != tt.allowed {
			t.Errorf("isAllowed(%q) = %v, want %v", tt.path, got, tt.allowed)
		}
	}
}

func TestParseRobotsTxt_IgnoresOtherAgents(t *testing.T) {
	body := `
User-agent: Googlebot
Disallow: /

User-agent: *
Disallow: /secret/
`
	rules := parseRobotsTxt(strings.NewReader(body))

	// Only User-agent: * rules apply — "/" is NOT disallowed for us.
	if !rules.isAllowed("/page") {
		t.Error("expected /page to be allowed — Googlebot rules should not apply")
	}
	if rules.isAllowed("/secret/doc") {
		t.Error("expected /secret/doc to be disallowed")
	}
}

func TestParseRobotsTxt_EmptyDisallow(t *testing.T) {
	// "Disallow:" with empty path means nothing is disallowed.
	body := `
User-agent: *
Disallow:
`
	rules := parseRobotsTxt(strings.NewReader(body))
	if !rules.isAllowed("/anything") {
		t.Error("empty Disallow should allow everything")
	}
}

func TestParseRobotsTxt_Comments(t *testing.T) {
	body := `
# This is a comment
User-agent: * # applies to all
Disallow: /hidden/ # secret area
`
	rules := parseRobotsTxt(strings.NewReader(body))
	if rules.isAllowed("/hidden/page") {
		t.Error("expected /hidden/page to be disallowed")
	}
	if !rules.isAllowed("/visible") {
		t.Error("expected /visible to be allowed")
	}
}

func TestParseRobotsTxt_NoWildcard(t *testing.T) {
	body := `
User-agent: Googlebot
Disallow: /
`
	rules := parseRobotsTxt(strings.NewReader(body))
	// No User-agent: * block → everything allowed.
	if !rules.isAllowed("/anything") {
		t.Error("no wildcard agent — should allow everything")
	}
}

func TestCheckRobotsTxt_Integration(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/robots.txt" {
			w.Write([]byte("User-agent: *\nDisallow: /blocked/\n"))
			return
		}
		w.WriteHeader(200)
	}))
	defer srv.Close()

	// Use a plain client for tests (SSRF protection blocks localhost).
	origClient := robotsClient
	robotsClient = &http.Client{Timeout: 5 * time.Second}
	t.Cleanup(func() { robotsClient = origClient })

	// Clear cache for this test.
	robotsCache.mu.Lock()
	delete(robotsCache.entries, strings.TrimPrefix(srv.URL, "http://"))
	robotsCache.mu.Unlock()

	ctx := t.Context()

	if !CheckRobotsTxt(ctx, srv.URL+"/allowed/page") {
		t.Error("expected /allowed/page to pass robots.txt check")
	}
	if CheckRobotsTxt(ctx, srv.URL+"/blocked/page") {
		t.Error("expected /blocked/page to fail robots.txt check")
	}
}

func TestCheckRobotsTxt_NoRobotsTxt(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(404)
	}))
	defer srv.Close()

	origClient := robotsClient
	robotsClient = &http.Client{Timeout: 5 * time.Second}
	t.Cleanup(func() { robotsClient = origClient })

	robotsCache.mu.Lock()
	delete(robotsCache.entries, strings.TrimPrefix(srv.URL, "http://"))
	robotsCache.mu.Unlock()

	// 404 robots.txt → everything allowed.
	if !CheckRobotsTxt(t.Context(), srv.URL+"/anything") {
		t.Error("missing robots.txt should allow everything")
	}
}

func TestParseRobotsTxt_RawTextCaptured(t *testing.T) {
	body := "User-agent: *\nDisallow: /private/\n"
	rules := parseRobotsTxt(strings.NewReader(body))
	// parseRobotsTxt does not set rawText — that is set by fetchRobotsTxt.
	// Verify rules parsed correctly; rawText is empty at this layer.
	if rules.isAllowed("/private/secret") {
		t.Error("expected /private/secret to be disallowed")
	}
}

func TestGetRobotsTxtContent_Integration(t *testing.T) {
	robotsTxtBody := "User-agent: *\nDisallow: /blocked/\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/robots.txt" {
			w.Write([]byte(robotsTxtBody))
			return
		}
		w.WriteHeader(200)
	}))
	defer srv.Close()

	origClient := robotsClient
	robotsClient = &http.Client{Timeout: 5 * time.Second}
	t.Cleanup(func() { robotsClient = origClient })

	// Clear cache.
	domain := strings.TrimPrefix(srv.URL, "http://")
	robotsCache.mu.Lock()
	delete(robotsCache.entries, domain)
	robotsCache.mu.Unlock()

	// Populate cache via CheckRobotsTxt first (required before GetRobotsTxtContent).
	CheckRobotsTxt(t.Context(), srv.URL+"/page")

	content := GetRobotsTxtContent(t.Context(), srv.URL+"/page")
	if content != robotsTxtBody {
		t.Errorf("GetRobotsTxtContent() = %q, want %q", content, robotsTxtBody)
	}
}

func TestGetRobotsTxtContent_CacheHit(t *testing.T) {
	rawText := "User-agent: *\nDisallow: /secret/\n"
	fakeDomain := "cached-robots-test.example.com"

	robotsCache.mu.Lock()
	robotsCache.entries[fakeDomain] = &robotsCacheEntry{
		rules:     &robotsRules{rawText: rawText},
		fetchedAt: time.Now(),
		err:       nil,
	}
	robotsCache.mu.Unlock()
	t.Cleanup(func() {
		robotsCache.mu.Lock()
		delete(robotsCache.entries, fakeDomain)
		robotsCache.mu.Unlock()
	})

	content := GetRobotsTxtContent(t.Context(), "https://"+fakeDomain+"/page")
	if content != rawText {
		t.Errorf("GetRobotsTxtContent() cache hit = %q, want %q", content, rawText)
	}
}
