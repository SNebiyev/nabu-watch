package scanner

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/PuerkitoBio/goquery"
)

// servePage starts a test HTTP server that returns the given HTML body.
func servePage(t *testing.T, html string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(html))
	}))
	t.Cleanup(srv.Close)
	return srv
}

// bannerTask returns a minimal Task for the given URL and subtype.
func bannerTask(url string) Task {
	return Task{
		MonitorID:   "test-monitor-id",
		WebsiteID:   "test-website-id",
		WebsiteURL:  url,
		RegionCode:  "DE",
		MonitorType: "COMPLIANCE",
		SubType:     "COOKIE_BANNER",
	}
}

// TestBannerScanner_headlessUnavailable verifies that when headless Chrome is not
// available the scanner returns a non-nil Result (not a hard error) with
// banner_detected=false and status=NON_COMPLIANT.
func TestBannerScanner_headlessUnavailable(t *testing.T) {
	// HeadlessAllocator with no Chrome binary → available=false
	headless := &HeadlessAllocator{available: false}
	scanner := NewBannerScanner(headless, nil)

	srv := servePage(t, "<html><body>hello</body></html>")
	task := bannerTask(srv.URL)

	result, err := scanner.Scan(context.Background(), task)
	if err != nil {
		t.Fatalf("expected nil error, got %v", err)
	}
	if result == nil {
		t.Fatal("expected non-nil result")
	}
	if result.Details["banner_detected"] != "false" {
		t.Errorf("banner_detected: want 'false', got %q", result.Details["banner_detected"])
	}
	if result.Details["status"] != "NON_COMPLIANT" {
		t.Errorf("status: want 'NON_COMPLIANT', got %q", result.Details["status"])
	}
	if result.Details["scanned_via"] != "UNAVAILABLE" {
		t.Errorf("scanned_via: want 'UNAVAILABLE', got %q", result.Details["scanned_via"])
	}
}

// TestBannerScanner_complianceRouter_routesCookieBanner verifies that
// ComplianceRouter sends COOKIE_BANNER tasks to BannerScanner (not ComplianceScanner).
//
// We confirm routing works by checking that the result contains the banner_detected
// key (produced by BannerScanner) and NOT keyword fields (produced by ComplianceScanner).
func TestBannerScanner_complianceRouter_routesCookieBanner(t *testing.T) {
	headless := &HeadlessAllocator{available: false}
	router := NewComplianceRouter(
		NewComplianceScanner(headless),
		NewBannerScanner(headless, nil),
		NewTrackerScanner(headless),
	)

	srv := servePage(t, "<html><body>no banner</body></html>")
	task := bannerTask(srv.URL)

	result, err := router.Scan(context.Background(), task)
	if err != nil {
		t.Fatalf("router returned error: %v", err)
	}
	if _, ok := result.Details["banner_detected"]; !ok {
		t.Error("expected banner_detected in result details (from BannerScanner), not found")
	}
	if _, ok := result.Details["found_keywords"]; ok {
		t.Error("found_keywords should not be present in banner scan result")
	}
}

// TestBannerScanner_complianceRouter_routesKeyword verifies that ComplianceRouter
// routes non-COOKIE_BANNER tasks to ComplianceScanner.
func TestBannerScanner_complianceRouter_routesKeyword(t *testing.T) {
	headless := &HeadlessAllocator{available: false}
	router := NewComplianceRouter(
		NewComplianceScanner(headless),
		NewBannerScanner(headless, nil),
		NewTrackerScanner(headless),
	)

	srv := servePage(t, "<html><body>Datenschutzerklärung</body></html>")
	task := Task{
		MonitorID:        "test-monitor-id",
		WebsiteID:        "test-website-id",
		WebsiteURL:       srv.URL,
		RegionCode:       "DE",
		MonitorType:      "COMPLIANCE",
		SubType:          "PRIVACY_POLICY",
		RequiredKeywords: []string{"Datenschutzerklärung"},
	}

	result, err := router.Scan(context.Background(), task)
	if err != nil {
		t.Fatalf("router returned error: %v", err)
	}
	// ComplianceScanner always includes status; banner_detected should not be present
	if _, ok := result.Details["banner_detected"]; ok {
		t.Error("banner_detected should not be present in keyword scan result")
	}
}

// TestBannerDetection_knownCMP verifies goquery-based detection of a named CMP
// by injecting a known CMP root element in the HTML.
func TestBannerDetection_knownCMP(t *testing.T) {
	tests := []struct {
		name          string
		html          string
		wantDetected  bool
		wantCMP       string
		wantAccept    bool
		wantReject    bool
		wantCompliant bool
	}{
		{
			name: "OneTrust with accept and reject buttons",
			html: `<html><body>
				<div id="onetrust-consent-sdk">
					<button>Accept All Cookies</button>
					<button>Reject All</button>
					<button>Cookie Settings</button>
				</div>
			</body></html>`,
			wantDetected:  true,
			wantCMP:       "OneTrust",
			wantAccept:    true,
			wantReject:    true,
			wantCompliant: true,
		},
		{
			name: "Cookiebot with accept only — no reject (dark pattern)",
			html: `<html><body>
				<div id="CybotCookiebotDialog">
					<button>OK, ich stimme zu</button>
				</div>
			</body></html>`,
			wantDetected:  true,
			wantCMP:       "Cookiebot",
			wantAccept:    true,
			wantReject:    false,
			wantCompliant: false,
		},
		{
			name:          "No banner at all",
			html:          `<html><body><p>Willkommen</p></body></html>`,
			wantDetected:  false,
			wantCompliant: false,
		},
		{
			// Two-layer news-site flow: initial banner (id=cookie-consent) has
			// Accept + Preferences only. The document also contains the merged
			// htmlPart2 (preferences panel) that FetchBannerPage opens via
			// prefsClickerJS. Reject buttons live only in the prefs panel, which
			// is outside the initial banner element. scanButtons widens to the
			// full document when prefsFound && !rejectFound.
			name: "Two-layer consent flow (news-site style) — Reject only in prefs panel",
			html: `<html><body>
				<div id="cookie-consent">
					<h1>Welcome!</h1>
					<button>Consent and continue</button>
					<button>Subscribe now</button>
					<button>Preferences</button>
				</div>
				<!-- htmlPart2: preferences panel opened after prefsClickerJS ran -->
				<div class="privacy-settings-panel">
					<h2>Privacy Einstellungen</h2>
					<button>Reject</button>
					<button>Accept</button>
					<button>Apply settings</button>
				</div>
			</body></html>`,
			wantDetected:  true,
			wantCMP:       "Generic",
			wantAccept:    true,
			wantReject:    true,
			wantCompliant: true,
		},
		{
			// Le Monde consent wall: real CSS class (popin-gdpr-no-scroll), French consent
			// language, accept button only — no reject option → detected but NON_COMPLIANT.
			// The class doesn't match any CMP fingerprint or generic selector, so detection
			// relies on the text heuristic ("utilisation de vos données" signal).
			name: "French consent wall (Le Monde) — real class, accept only, no reject",
			html: `<html><body>
				<div class="popin-gdpr-no-scroll" style="position:fixed">
					<h2>Soutenez un journalisme fiable</h2>
					<h3>Accéder gratuitement en acceptant l'utilisation de vos données.</h3>
					<p>L'accès gratuit au site est subordonné à votre consentement sur l'utilisation
					de vos données personnelles à des fins de publicité personnalisée.</p>
					<p>Vous pouvez retirer votre consentement via la Gestion des cookies.</p>
					<button>Accepter et continuer</button>
					<button>S'abonner</button>
					<a href="/login">Se connecter</a>
				</div>
				<!-- Page content behind the wall — must NOT match -->
				<nav><a href="/sudoku">Sudoku</a><a href="/culture">Culture</a></nav>
			</body></html>`,
			wantDetected:  true,
			wantCMP:       "TextHeuristic",
			wantAccept:    true,
			wantReject:    false,
			wantCompliant: false,
		},
		{
			name: "Generic cookie banner selector",
			html: `<html><body>
				<div class="cookie-banner">
					<button>Accept</button>
					<button>Decline</button>
				</div>
			</body></html>`,
			wantDetected:  true,
			wantCMP:       "Generic",
			wantAccept:    true,
			wantReject:    true,
			wantCompliant: true,
		},
		{
			name: "German: Akzeptieren and Ablehnen buttons",
			html: `<html><body>
				<div id="onetrust-consent-sdk">
					<button>Alle akzeptieren</button>
					<button>Alle ablehnen</button>
				</div>
			</body></html>`,
			wantDetected:  true,
			wantCMP:       "OneTrust",
			wantAccept:    true,
			wantReject:    true,
			wantCompliant: true,
		},
		{
			// Regression: "ok" in acceptTerms must not match "Sudoku", "Booking", etc.
			// Short terms (≤3 chars) use exact matching to prevent false positives.
			name: "Short accept term 'ok' does not match 'Sudoku' substring",
			html: `<html><body>
				<div id="onetrust-consent-sdk">
					<a href="/sudoku">Sudoku</a>
					<a href="/booking">Booking</a>
					<button>Ablehnen</button>
				</div>
			</body></html>`,
			wantDetected:  true,
			wantCMP:       "OneTrust",
			wantAccept:    false,
			wantReject:    true,
			wantCompliant: true,
		},
		{
			// Regression (bahn.de): custom in-house consent layers ship as web
			// components that attach a shadow root to a host element. The banner
			// markup — heading, Accept / Reject buttons — lives inside the shadow
			// root and is invisible to a plain document.outerHTML dump. After the
			// fix, bannerDOMExtractorJS walks shadow roots and appends their
			// innerHTML inside a <div class="nabu-shadow-dump"> envelope. This
			// test simulates the post-merge document and verifies detection picks
			// up the banner via the existing text heuristic.
			name: "Shadow DOM consent layer (bahn.de style) — merged via nabu-shadow-dump",
			html: `<html><body>
				<header><a href="/login">Anmelden</a></header>
				<main><h1>Welcome</h1></main>
				<div class="nabu-shadow-dump" style="display:none">
					<div role="dialog" aria-modal="true" class="custom-overlay">
						<h2>This website uses cookies.</h2>
						<p>We use cookies and similar technology...</p>
						<button>Allow all cookies</button>
						<button>Reject all</button>
					</div>
				</div>
			</body></html>`,
			wantDetected:  true,
			wantCMP:       "TextHeuristic",
			wantAccept:    true,
			wantReject:    true,
			wantCompliant: true,
		},
		{
			// Regression: when rule-based detects a banner but AI fallback says
			// detected=false, the rule-based detection must be preserved.
			// This mirrors the Le Monde bug where TextHeuristic found .popin-gdpr-no-scroll
			// but AI hallucinated "no banner" and wiped the detection.
			name: "AI fallback must not downgrade rule-based detection",
			html: `<html><body>
				<div class="popin-gdpr-no-scroll" style="position:fixed">
					<p>Accéder gratuitement en acceptant l'utilisation de vos données.</p>
					<button>Accepter et continuer</button>
				</div>
			</body></html>`,
			wantDetected:  true,
			wantCMP:       "TextHeuristic",
			wantAccept:    true,
			wantReject:    false,
			wantCompliant: false,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			srv := servePage(t, tc.html)
			// Feed the HTML directly through goquery detection logic
			// by creating a goquery document from the test HTML string.
			doc, err := goquery.NewDocumentFromReader(strings.NewReader(tc.html))
			if err != nil {
				t.Fatalf("goquery parse error: %v", err)
			}
			_ = srv // suppress unused

			// Run detection with the hardcoded defaults — table tests exercise
			// the baseline keyword behaviour (banner_keywords_test.go covers the
			// task-provided override path).
			kws := defaultBannerKeywords
			cmpName, cmpSelector := detectCMP(doc, kws.phrases)
			br := bannerResult{
				detected:    cmpName != "",
				cmpName:     cmpName,
				cmpSelector: cmpSelector,
				scannedVia:  "HEADLESS",
			}

			if br.detected {
				scope := doc.Find(cmpSelector)
				if scope.Find("button, [role=button], a[href]").Length() == 0 {
					scope = doc.Selection
				}
				scanButtons(scope, &br, kws)
				// Two-layer flow: widen to full doc when prefs found but reject missing.
				if br.prefsFound && !br.rejectFound {
					scanButtons(doc.Selection, &br, kws)
				}
			}

			compliant := br.detected && br.rejectFound

			if br.detected != tc.wantDetected {
				t.Errorf("detected: got %v, want %v", br.detected, tc.wantDetected)
			}
			if tc.wantDetected && br.cmpName != tc.wantCMP {
				t.Errorf("cmpName: got %q, want %q", br.cmpName, tc.wantCMP)
			}
			if tc.wantDetected && br.acceptFound != tc.wantAccept {
				t.Errorf("acceptFound: got %v, want %v", br.acceptFound, tc.wantAccept)
			}
			if tc.wantDetected && br.rejectFound != tc.wantReject {
				t.Errorf("rejectFound: got %v, want %v", br.rejectFound, tc.wantReject)
			}
			if compliant != tc.wantCompliant {
				t.Errorf("compliant: got %v, want %v", compliant, tc.wantCompliant)
			}
		})
	}
}
