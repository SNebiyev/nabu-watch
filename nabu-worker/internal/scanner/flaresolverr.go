package scanner

// ── FlareSolverr Integration ──────────────────────────────────────────────────
//
// FlareSolverr (https://github.com/FlareSolverr/FlareSolverr) is a proxy server
// that runs a real Selenium-managed Chrome instance with undetected-chromedriver.
// It is the last-resort tier for sites that completely block our CDP-based
// headless Chrome (e.g. sites running Akamai Bot Manager v3 or Cloudflare's
// "I'm Under Attack" mode with JS challenge that fingerprints the CDP protocol
// itself, not just navigator.webdriver).
//
// ── Architecture ─────────────────────────────────────────────────────────────
//
// Deployment (Docker Compose):
//
//	flaresolverr:
//	  image: ghcr.io/flaresolverr/flaresolverr:latest
//	  ports:
//	    - "8191:8191"
//	  environment:
//	    - LOG_LEVEL=info
//	  restart: unless-stopped
//
// Configuration:
//
//	Set NABU_FLARESOLVERR_URL=http://localhost:8191 in the worker's environment.
//	NewHeadlessAllocator() auto-detects this var and stores the client as h.Flare.
//	When nil, the tier is silently skipped.
//
// Escalation ladder (hybridFetch, per request):
//
//	Tier 1 — Static HTTP (fastest, ~0.5 s):        fetchAndParse()
//	Tier 2 — chromedp headless, mobile UA (~5 s):  h.FetchPage()
//	Tier 3 — FlareSolverr proxy (~10–20 s):        h.Flare.Solve()  ⚠️  NOT YET WIRED
//
// NOTE: Tier 3 is fully implemented below but NOT yet integrated into
// hybridFetch(). The client is created by NewHeadlessAllocator() and stored
// as h.Flare, but no code path currently calls Solve(). See the integration
// snippet below for how to enable it.
//
// ── Session handling ──────────────────────────────────────────────────────────
//
// FlareSolverr sessions are stateful: a single session ID keeps the same
// Selenium profile across multiple requests to the same domain, preserving
// cookies and evading "new visitor" bot signals.  We create one session per
// domain and destroy it after the scan completes.
//
// Protocol (REST JSON, port 8191):
//
//	POST /v1
//	  Body: {"cmd":"sessions.create","session":"<id>"}
//	  Body: {"cmd":"request.get","url":"<url>","session":"<id>","maxTimeout":60000}
//	  Body: {"cmd":"sessions.destroy","session":"<id>"}
//
// ── Integration point in hybridFetch ──────────────────────────────────────────
//
// After Tier 2 fails (block page detection returns true AND mobile-UA retry
// also fails), add to hybridFetch in fetcher.go:
//
//	if isBlockPage(extractBodyText(mDoc)) && headless.Flare != nil {
//	    slog.Info("Escalating to FlareSolverr tier-3", "url", rawURL)
//	    fDoc, fErr := headless.Flare.Solve(ctx, rawURL)
//	    if fErr == nil {
//	        return FetchResult{StatusCode: 200, Doc: fDoc, ScannedVia: "FLARESOLVERR"}, nil
//	    }
//	    slog.Warn("FlareSolverr failed", "url", rawURL, "error", fErr)
//	}

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

const (
	flareSolverrTimeout    = 90 * time.Second // FlareSolverr can be slow — JS challenge solve takes ~15 s
	flareSolverrMaxTimeout = 60000            // milliseconds forwarded to FlareSolverr's own timeout
)

// ── Wire protocol types ───────────────────────────────────────────────────────

type flareRequest struct {
	Cmd        string `json:"cmd"`
	URL        string `json:"url,omitempty"`
	Session    string `json:"session,omitempty"`
	MaxTimeout int    `json:"maxTimeout,omitempty"`
}

type flareSolution struct {
	URL      string            `json:"url"`
	Status   int               `json:"status"`
	Headers  map[string]string `json:"headers"`
	Response string            `json:"response"` // the rendered HTML body
	Cookies  []struct {
		Name  string `json:"name"`
		Value string `json:"value"`
	} `json:"cookies"`
}

type flareResponse struct {
	Status   string        `json:"status"` // "ok" or "error"
	Message  string        `json:"message"`
	Solution flareSolution `json:"solution"`
}

// ── Client ────────────────────────────────────────────────────────────────────

// FlareSolverrClient calls a running FlareSolverr instance to solve Cloudflare
// and Akamai JS challenges that chromedp cannot bypass.
type FlareSolverrClient struct {
	baseURL    string
	httpClient *http.Client
}

// NewFlareSolverrClient constructs a client for the FlareSolverr service at
// baseURL (e.g. "http://localhost:8191").  The URL should NOT have a trailing
// slash.
func NewFlareSolverrClient(baseURL string) *FlareSolverrClient {
	return &FlareSolverrClient{
		baseURL: strings.TrimRight(baseURL, "/"),
		httpClient: &http.Client{
			Timeout: flareSolverrTimeout,
		},
	}
}

// Solve navigates to targetURL via FlareSolverr, solving any JS challenge, and
// returns the rendered HTML as a *goquery.Document.
//
// It creates a throw-away session (so the real Selenium profile is isolated per
// call), fetches the page, then destroys the session.  Session cleanup runs even
// on error to prevent orphaned Selenium instances on the FlareSolverr host.
func (f *FlareSolverrClient) Solve(ctx context.Context, targetURL string) (*goquery.Document, error) {
	sessionID := fmt.Sprintf("nabu-%d", time.Now().UnixNano())

	// ── Create session ────────────────────────────────────────────────────
	if err := f.do(ctx, flareRequest{Cmd: "sessions.create", Session: sessionID}, nil); err != nil {
		return nil, fmt.Errorf("flaresolverr create session: %w", err)
	}
	// Always destroy the session — even if the subsequent fetch fails.
	defer func() {
		dCtx, dCancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer dCancel()
		if err := f.do(dCtx, flareRequest{Cmd: "sessions.destroy", Session: sessionID}, nil); err != nil {
			slog.Warn("FlareSolverr session cleanup failed", "session", sessionID, "error", err)
		}
	}()

	// ── Fetch page ────────────────────────────────────────────────────────
	var resp flareResponse
	err := f.do(ctx, flareRequest{
		Cmd:        "request.get",
		URL:        targetURL,
		Session:    sessionID,
		MaxTimeout: flareSolverrMaxTimeout,
	}, &resp)
	if err != nil {
		return nil, fmt.Errorf("flaresolverr request.get %s: %w", targetURL, err)
	}
	if resp.Status != "ok" {
		return nil, fmt.Errorf("flaresolverr returned status %q: %s", resp.Status, resp.Message)
	}

	html := resp.Solution.Response
	if strings.TrimSpace(html) == "" {
		return nil, fmt.Errorf("flaresolverr returned empty HTML for %s", targetURL)
	}

	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("parse flaresolverr HTML: %w", err)
	}

	slog.Info("FlareSolverr solve succeeded",
		"url", targetURL,
		"http_status", resp.Solution.Status,
		"html_len", len(html),
	)
	return doc, nil
}

// do sends a POST /v1 request to FlareSolverr and optionally decodes the
// response JSON into out (pass nil to discard the body).
func (f *FlareSolverrClient) do(ctx context.Context, payload flareRequest, out interface{}) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal flare request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.baseURL+"/v1", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("build flare request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := f.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("POST %s/v1: %w", f.baseURL, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("flaresolverr HTTP %d", resp.StatusCode)
	}

	if out != nil {
		if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
			return fmt.Errorf("decode flare response: %w", err)
		}
	}
	return nil
}
