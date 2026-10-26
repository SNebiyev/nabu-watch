package scanner

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/PuerkitoBio/goquery"
)

// newComplianceServer builds an httptest.Server that serves homepageHTML at "/"
// and responds to each path in routes with the configured HTTP status code.
// Any unregistered path returns 404.
func newComplianceServer(t *testing.T, homepageHTML string, routes map[string]int) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, homepageHTML)
	})

	for path, status := range routes {
		status := status
		mux.HandleFunc(path, func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(status)
		})
	}

	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

// assertJSONStringSlice deserialises jsonStr as []string and verifies that it
// contains exactly the elements in want (order-independent).
func assertJSONStringSlice(t *testing.T, field, jsonStr string, want []string) {
	t.Helper()
	var got []string
	if jsonStr != "" && jsonStr != "null" {
		if err := json.Unmarshal([]byte(jsonStr), &got); err != nil {
			t.Errorf("%s: cannot unmarshal %q: %v", field, jsonStr, err)
			return
		}
	}
	if len(got) != len(want) {
		t.Errorf("%s: got %v (len %d), want %v (len %d)", field, got, len(got), want, len(want))
		return
	}
	wantSet := make(map[string]bool, len(want))
	for _, w := range want {
		wantSet[w] = true
	}
	for _, g := range got {
		if !wantSet[g] {
			t.Errorf("%s: unexpected item %q in %v (want %v)", field, g, got, want)
		}
	}
}

// hashForHTML parses an HTML snippet and returns the content hash exactly as
// the scanner would compute it — useful for setting up "content unchanged" tests.
func hashForHTML(t *testing.T, html string) string {
	t.Helper()
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		t.Fatalf("parse test HTML: %v", err)
	}
	return hashContent(extractBodyText(doc))
}

// ─── homepage mode ───────────────────────────────────────────────────────────

func TestComplianceScanner_Type(t *testing.T) {
	if got := newComplianceScannerWithClient(nil, buildPlainClient()).Type(); got != "COMPLIANCE" {
		t.Fatalf("Type() = %q, want %q", got, "COMPLIANCE")
	}
}

func TestComplianceScanner_HomepageMode(t *testing.T) {
	tests := []struct {
		name          string
		homepageHTML  string
		routes        map[string]int // path → HTTP status code
		keywords      []string
		wantCompliant bool
		wantFound     []string
		wantBroken    []string
		wantMissing   []string
	}{
		{
			name: "all keywords found via link text",
			homepageHTML: `<html><body>
				<a href="/privacy-policy">Privacy Policy</a>
				<a href="/impressum">Impressum</a>
			</body></html>`,
			routes:        map[string]int{"/privacy-policy": 200, "/impressum": 200},
			keywords:      []string{"privacy policy", "impressum"},
			wantCompliant: true,
			wantFound:     []string{"privacy policy", "impressum"},
		},
		{
			name: "keyword matched via href substring",
			homepageHTML: `<html><body>
				<a href="/datenschutz">Datenschutzerklärung</a>
			</body></html>`,
			routes:        map[string]int{"/datenschutz": 200},
			keywords:      []string{"datenschutz"},
			wantCompliant: true,
			wantFound:     []string{"datenschutz"},
		},
		{
			name: "keyword matching is case-insensitive",
			homepageHTML: `<html><body>
				<a href="/privacy">PRIVACY POLICY</a>
			</body></html>`,
			routes:        map[string]int{"/privacy": 200},
			keywords:      []string{"privacy policy"},
			wantCompliant: true,
			wantFound:     []string{"privacy policy"},
		},
		{
			name: "keyword substring matches within longer link text",
			homepageHTML: `<html><body>
				<a href="/terms">Our Terms of Service and Policies</a>
			</body></html>`,
			routes:        map[string]int{"/terms": 200},
			keywords:      []string{"terms of service"},
			wantCompliant: true,
			wantFound:     []string{"terms of service"},
		},
		{
			name: "broken link (404) classifies keyword as broken",
			homepageHTML: `<html><body>
				<a href="/gone">Privacy Policy</a>
			</body></html>`,
			routes:        map[string]int{"/gone": 404},
			keywords:      []string{"privacy policy"},
			wantCompliant: false,
			wantBroken:    []string{"privacy policy"},
		},
		{
			name: "keyword absent from all links is classified as missing",
			homepageHTML: `<html><body>
				<a href="/about">About Us</a>
			</body></html>`,
			routes:        map[string]int{"/about": 200},
			keywords:      []string{"impressum"},
			wantCompliant: false,
			wantMissing:   []string{"impressum"},
		},
		{
			name: "non-navigable links (mailto, anchor, tel) are skipped",
			homepageHTML: `<html><body>
				<a href="mailto:test@example.com">Email</a>
				<a href="#section">Jump</a>
				<a href="tel:+49123456">Call</a>
				<a href="/privacy">Privacy Policy</a>
			</body></html>`,
			routes:        map[string]int{"/privacy": 200},
			keywords:      []string{"privacy policy"},
			wantCompliant: true,
			wantFound:     []string{"privacy policy"},
		},
		{
			name: "one working link among candidates is sufficient",
			homepageHTML: `<html><body>
				<a href="/privacy-broken">Privacy Policy</a>
				<a href="/privacy-ok">Privacy Policy</a>
			</body></html>`,
			routes:        map[string]int{"/privacy-broken": 404, "/privacy-ok": 200},
			keywords:      []string{"privacy policy"},
			wantCompliant: true,
			wantFound:     []string{"privacy policy"},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			srv := newComplianceServer(t, tc.homepageHTML, tc.routes)

			s := newComplianceScannerWithClient(nil, buildPlainClient())
			task := Task{
				MonitorID:        "mon-homepage",
				WebsiteID:        "web-1",
				WebsiteURL:       srv.URL + "/",
				RegionCode:       "DE",
				MonitorType:      "COMPLIANCE",
				RequiredKeywords: tc.keywords,
			}

			result, err := s.Scan(context.Background(), task)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if !result.Success {
				t.Fatalf("result.Success=false; error: %q", result.Details["error"])
			}
			if result.Details["mode"] != "homepage" {
				t.Errorf("mode = %q, want %q", result.Details["mode"], "homepage")
			}

			wantStatus := "COMPLIANT"
			if !tc.wantCompliant {
				wantStatus = "NON_COMPLIANT"
			}
			if got := result.Details["status"]; got != wantStatus {
				t.Errorf("status = %q, want %q", got, wantStatus)
			}

			assertJSONStringSlice(t, "found_keywords", result.Details["found_keywords"], tc.wantFound)
			assertJSONStringSlice(t, "broken_keywords", result.Details["broken_keywords"], tc.wantBroken)
			assertJSONStringSlice(t, "missing_keywords", result.Details["missing_keywords"], tc.wantMissing)
		})
	}
}

// ─── targeted mode ───────────────────────────────────────────────────────────

func TestComplianceScanner_TargetedMode(t *testing.T) {
	tests := []struct {
		name     string
		pageHTML string
		keywords []string
		// previousHash: empty = no prior hash; "compute" = derive from pageHTML
		previousHash       string
		wantCompliant      bool
		wantFound          []string
		wantMissing        []string
		wantContentChanged string // "true" | "false" | "unknown" | "" (skip check)
	}{
		{
			name:          "all keywords present in page text",
			pageHTML:      `<html><body>This page contains datenschutz and impressum information.</body></html>`,
			keywords:      []string{"datenschutz", "impressum"},
			wantCompliant: true,
			wantFound:     []string{"datenschutz", "impressum"},
		},
		{
			name:          "keyword matching is case-insensitive",
			pageHTML:      `<html><body>Welcome to our PRIVACY POLICY page.</body></html>`,
			keywords:      []string{"privacy policy"},
			wantCompliant: true,
			wantFound:     []string{"privacy policy"},
		},
		{
			name:          "keywords absent from page text are classified as missing",
			pageHTML:      `<html><body>Welcome to our website. Nothing relevant here.</body></html>`,
			keywords:      []string{"privacy policy", "terms of service"},
			wantCompliant: false,
			wantMissing:   []string{"privacy policy", "terms of service"},
		},
		{
			name:               "content change detected when hash differs from previous",
			pageHTML:           `<html><body>New content was added here.</body></html>`,
			keywords:           []string{"new"},
			previousHash:       "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			wantCompliant:      true,
			wantFound:          []string{"new"},
			wantContentChanged: "true",
		},
		{
			name:               "content unchanged when current hash matches previous",
			pageHTML:           `<html><body>stable content</body></html>`,
			keywords:           []string{"stable"},
			previousHash:       "compute", // derived at test time from pageHTML
			wantCompliant:      true,
			wantFound:          []string{"stable"},
			wantContentChanged: "false",
		},
		{
			name:               "no previous hash yields content_changed=unknown",
			pageHTML:           `<html><body>some content</body></html>`,
			keywords:           []string{"some"},
			previousHash:       "",
			wantCompliant:      true,
			wantFound:          []string{"some"},
			wantContentChanged: "unknown",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", "text/html; charset=utf-8")
				fmt.Fprint(w, tc.pageHTML)
			}))
			t.Cleanup(srv.Close)

			prevHash := tc.previousHash
			if prevHash == "compute" {
				prevHash = hashForHTML(t, tc.pageHTML)
			}

			s := newComplianceScannerWithClient(nil, buildPlainClient())
			task := Task{
				MonitorID:           "mon-targeted",
				WebsiteID:           "web-1",
				WebsiteURL:          srv.URL,
				RegionCode:          "DE",
				MonitorType:         "COMPLIANCE",
				RequiredKeywords:    tc.keywords,
				TargetURL:           srv.URL,
				PreviousContentHash: prevHash,
			}

			result, err := s.Scan(context.Background(), task)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if !result.Success {
				t.Fatalf("result.Success=false; error: %q", result.Details["error"])
			}
			if result.Details["mode"] != "targeted" {
				t.Errorf("mode = %q, want %q", result.Details["mode"], "targeted")
			}

			wantStatus := "COMPLIANT"
			if !tc.wantCompliant {
				wantStatus = "NON_COMPLIANT"
			}
			if got := result.Details["status"]; got != wantStatus {
				t.Errorf("status = %q, want %q", got, wantStatus)
			}

			assertJSONStringSlice(t, "found_keywords", result.Details["found_keywords"], tc.wantFound)
			assertJSONStringSlice(t, "missing_keywords", result.Details["missing_keywords"], tc.wantMissing)

			if tc.wantContentChanged != "" {
				if got := result.Details["content_changed"]; got != tc.wantContentChanged {
					t.Errorf("content_changed = %q, want %q", got, tc.wantContentChanged)
				}
			}
			if result.Details["content_hash"] == "" {
				t.Error("content_hash detail must be non-empty in targeted mode")
			}
		})
	}
}

// ─── baseline sanity check ───────────────────────────────────────────────────

// A homepage with exactly 2 navigable links — looks like a WAF interstitial
// when the baseline was ~50 but is perfectly fine when no baseline is set.
const interstitialLikeHTML = `<html><body>
<a href="/privacy">Privacy Policy</a>
<a href="/continue">Continue</a>
</body></html>`

func TestComplianceScanner_BaselineSanity(t *testing.T) {
	tests := []struct {
		name        string
		baseline    int
		wantWAFErr  bool
		wantSuccess bool
	}{
		{
			name:        "no baseline — scan proceeds normally",
			baseline:    0,
			wantWAFErr:  false,
			wantSuccess: true,
		},
		{
			name:        "baseline 50, current 2 (4%) — flagged as WAF block",
			baseline:    50,
			wantWAFErr:  true,
			wantSuccess: false,
		},
		{
			name:        "baseline 7, current 2 (29%) — ratio low but baseline too small, still flagged",
			baseline:    7,
			wantWAFErr:  true,
			wantSuccess: false,
		},
		{
			name:        "baseline 4, current 2 (50%) — above threshold, no flag",
			baseline:    4,
			wantWAFErr:  false,
			wantSuccess: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			srv := newComplianceServer(t, interstitialLikeHTML, map[string]int{
				"/privacy":  200,
				"/continue": 200,
			})
			s := newComplianceScannerWithClient(nil, buildPlainClient())
			task := Task{
				MonitorID:           "mon-baseline",
				WebsiteID:           "web-1",
				WebsiteURL:          srv.URL + "/",
				RegionCode:          "DE",
				MonitorType:         "COMPLIANCE",
				RequiredKeywords:    []string{"privacy policy"},
				BaselineLinksOnPage: tc.baseline,
			}

			result, err := s.Scan(context.Background(), task)
			if tc.wantWAFErr {
				if !errors.Is(err, ErrWAFStillBlocking) {
					t.Fatalf("expected ErrWAFStillBlocking, got err=%v result=%v", err, result)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if result == nil || result.Success != tc.wantSuccess {
				t.Fatalf("success=%v, want %v", result != nil && result.Success, tc.wantSuccess)
			}
		})
	}
}

// ─── edge cases ──────────────────────────────────────────────────────────────

func TestComplianceScanner_NoKeywords(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(w, `<html><body>irrelevant</body></html>`)
	}))
	t.Cleanup(srv.Close)

	result, err := newComplianceScannerWithClient(nil, buildPlainClient()).Scan(context.Background(), Task{
		MonitorID:        "mon-noop",
		WebsiteID:        "web-1",
		WebsiteURL:       srv.URL,
		RegionCode:       "DE",
		MonitorType:      "COMPLIANCE",
		RequiredKeywords: nil,
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !result.Success {
		t.Errorf("expected success=true for no-keyword task")
	}
	if !strings.Contains(result.Details["warning"], "no required_keywords") {
		t.Errorf("expected warning about missing keywords, got: %q", result.Details["warning"])
	}
}
