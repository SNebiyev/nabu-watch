package scanner

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// ErrWAFStillBlocking is returned by scanHomepage / scanTargetedPage when
// every bypass attempt (static → headless mobile → hard-reset desktop) still
// yields a WAF / CAPTCHA block page.  The caller should NOT record a
// NON_COMPLIANT result for a transient infrastructure block; instead it should
// surface the error so operators can investigate or schedule a retry.
var ErrWAFStillBlocking = errors.New("WAF_STILL_BLOCKING")

// ErrBatchAborted is returned by the scout scanner when AI confirms the
// homepage is not scannable (WAF/CAPTCHA/empty). The consumer should abort
// all remaining monitors for this website in the current batch.
var ErrBatchAborted = errors.New("BATCH_ABORTED")

// ErrTransientHeadless is returned when a headless fetch fails due to a
// transient issue (e.g. site JS crash, timeout) that is not a real compliance
// failure. The consumer should preserve the previous monitor state.
var ErrTransientHeadless = errors.New("TRANSIENT_HEADLESS")

// isTransientHeadlessError checks if an error message indicates a transient
// infrastructure or site-side failure that is not a real compliance change.
// Two categories are treated as transient:
//  1. Site-side JS crashes (TypeError, ReferenceError, etc.) — the page
//     misbehaved; the compliance posture hasn't changed.
//  2. Proxy tunnel failures (ERR_TUNNEL_CONNECTION_FAILED, ERR_PROXY_*) — our
//     residential proxy is unreachable; the site itself is fine.
//
// In both cases the previous monitor state is preserved (no ScanLog written,
// no spurious HIGH alert fired).
func isTransientHeadlessError(errMsg string) bool {
	upper := strings.ToUpper(errMsg)
	return strings.Contains(upper, "TYPEERROR") ||
		strings.Contains(upper, "REFERENCEERROR") ||
		strings.Contains(upper, "SYNTAXERROR") ||
		strings.Contains(upper, "RANGEERROR") ||
		strings.Contains(upper, "UNCAUGHT") ||
		strings.Contains(upper, "ERR_TUNNEL_CONNECTION_FAILED") ||
		strings.Contains(upper, "ERR_PROXY_CONNECTION_FAILED") ||
		strings.Contains(upper, "ERR_NO_SUPPORTED_PROXIES") ||
		// Scanner fetch budget expired — the site's response latency exceeded
		// our headless timeout. Not a compliance change; preserve previous state.
		strings.Contains(upper, "CONTEXT CANCELED") ||
		strings.Contains(upper, "CONTEXT DEADLINE EXCEEDED")
}

const (
	maxBodyBytes     = 5 * 1024 * 1024  // 5 MB — protects against unbounded reads
	fetchTimeout     = 45 * time.Second // matches headless multi-phase budget (pre+click+post)
	headCheckTimeout = 5 * time.Second
	warmUpTimeout    = 15 * time.Second // session warm-up budget
	maxRedirects     = 5
)

// buildPlainClient returns a minimal *http.Client with no SSRF dial protection
// and no stealth headers.  Intended exclusively for unit tests that use
// httptest.Server — production callers must use buildCrawlClient instead.
func buildPlainClient() *http.Client {
	jar, _ := cookiejar.New(nil)
	return &http.Client{
		Transport: &http.Transport{
			TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12},
			MaxIdleConnsPerHost: 4,
		},
		Jar: jar,
	}
}

// buildCrawlClientWithConfig constructs an *http.Client using an explicit
// LocalizationConfig instead of a region code.  Used by the English-language
// fallback client which needs neutral (en-US) headers.
func buildCrawlClientWithConfig(proxyURL string, cfg LocalizationConfig) (*http.Client, error) {
	dialer := &net.Dialer{
		Timeout:   15 * time.Second,
		KeepAlive: 30 * time.Second,
	}
	ssrfDial := ssrfDialContext(dialer)
	base := &http.Transport{
		DialContext:         ssrfDial,
		DialTLSContext:      utlsDialTLS(ssrfDial), // Chrome JA3 fingerprint, ALPN forced to http/1.1
		DisableKeepAlives:   false,
		MaxIdleConnsPerHost: 4,
	}
	if proxyURL != "" {
		u, err := url.Parse(proxyURL)
		if err != nil {
			return nil, fmt.Errorf("invalid proxy URL %q: %w", proxyURL, err)
		}
		base.Proxy = http.ProxyURL(u)
	}
	transport := &StealthTransport{Base: base, Headers: buildHeadersFromConfig(cfg, RandomUA())}
	jar, _ := cookiejar.New(nil)
	return &http.Client{
		Transport: transport,
		Jar:       jar,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= maxRedirects {
				return fmt.Errorf("stopped after %d redirects", maxRedirects)
			}
			if prev := via[len(via)-1]; prev.URL != nil {
				req.Header.Set("Referer", prev.URL.String())
			}
			return nil
		},
	}, nil
}

// buildCrawlClient constructs an *http.Client for page crawling.
// If proxyURL is non-empty it is configured as an HTTP/HTTPS proxy.
// regionCode is used to inject localised stealth headers (Accept-Language,
// Referer, lang cookie) via StealthTransport on every request.
//
// A cookie jar is attached so that Set-Cookie responses from the warm-up
// request (and subsequent hops) are automatically replayed, preventing
// geo-redirect loops caused by missing locale cookies.
//
// HTTP/2 is explicitly disabled: some WAFs (Akamai, Cloudflare) behave
// differently over h2 and may fingerprint or reject non-browser h2 clients.
// Forcing HTTP/1.1 makes the request profile more predictable and avoids
// ALPN-triggered blocks.
func buildCrawlClient(proxyURL, regionCode string) (*http.Client, error) {
	dialer := &net.Dialer{
		Timeout:   15 * time.Second,
		KeepAlive: 30 * time.Second,
	}

	ssrfDial := ssrfDialContext(dialer)
	base := &http.Transport{
		DialContext:         ssrfDial,
		DialTLSContext:      utlsDialTLS(ssrfDial), // Chrome JA3 fingerprint, ALPN forced to http/1.1
		DisableKeepAlives:   false,
		MaxIdleConnsPerHost: 4,
	}

	if proxyURL != "" {
		u, err := url.Parse(proxyURL)
		if err != nil {
			return nil, fmt.Errorf("invalid proxy URL %q: %w", proxyURL, err)
		}
		base.Proxy = http.ProxyURL(u)
		slog.Debug("Compliance scanner using proxy", "proxy", redactProxy(proxyURL))
	}

	// Wrap with StealthTransport so region-aware headers are injected on
	// every request (including deep-crawl sub-pages) without modifying
	// individual call sites.
	stealthHeaders := BuildHeaders(regionCode)
	transport := &StealthTransport{Base: base, Headers: stealthHeaders}

	jar, _ := cookiejar.New(nil) // nolint: errcheck — cookiejar.New never errors

	return &http.Client{
		Transport: transport,
		Jar:       jar,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= maxRedirects {
				return fmt.Errorf("stopped after %d redirects", maxRedirects)
			}
			// Update Referer on each redirect hop; all other stealth headers
			// are injected by StealthTransport.RoundTrip automatically.
			if prev := via[len(via)-1]; prev.URL != nil {
				req.Header.Set("Referer", prev.URL.String())
			}
			return nil
		},
		// No client-level Timeout — context deadlines govern each individual request.
	}, nil
}

// warmUpSession performs a brief GET to the website homepage so that
// Set-Cookie headers (lang, locale, consent) are captured into the client's
// cookie jar.  This prevents geo-IP-driven redirects on the real scan request
// because the server will see the lang cookie it previously set.
// Errors are silently ignored — warm-up failure is non-fatal.
func warmUpSession(ctx context.Context, client *http.Client, websiteURL string) {
	warmCtx, cancel := context.WithTimeout(ctx, warmUpTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(warmCtx, http.MethodGet, websiteURL, nil)
	if err != nil {
		return
	}
	resp, err := client.Do(req)
	if err != nil {
		slog.Debug("Session warm-up failed (non-fatal)", "url", websiteURL, "error", err)
		return
	}
	defer resp.Body.Close()
	// Drain a small chunk so the connection is eligible for reuse and any
	// body-triggered cookie redirects have time to settle.
	_, _ = io.CopyN(io.Discard, resp.Body, 4096)
	slog.Debug("Session warm-up complete",
		"url", websiteURL, "status", resp.StatusCode,
		"cookies_captured", len(resp.Cookies()),
	)
}

// fetchAndParse performs a GET request and parses the response body as HTML.
// Headers (User-Agent, Accept, Accept-Language, etc.) are injected by
// StealthTransport on every outbound request.
func fetchAndParse(ctx context.Context, client *http.Client, rawURL string) (int, *goquery.Document, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return 0, nil, fmt.Errorf("create GET request: %w", err)
	}

	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, fmt.Errorf("GET %s: %w", rawURL, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		if resp.StatusCode == http.StatusForbidden {
			// Log WAF fingerprint headers to help diagnose which protection layer
			// is blocking us (Akamai, Cloudflare, etc.).
			slog.Warn("Received 403 Forbidden — possible WAF block",
				"url", rawURL,
				"server", resp.Header.Get("Server"),
				"x_akamai_status", resp.Header.Get("X-Akamai-Status"),
				"cf_ray", resp.Header.Get("CF-RAY"),
				"x_cache", resp.Header.Get("X-Cache"),
				"x_powered_by", resp.Header.Get("X-Powered-By"),
			)
		}
		return resp.StatusCode, nil, fmt.Errorf("page returned HTTP %d", resp.StatusCode)
	}

	doc, err := goquery.NewDocumentFromReader(io.LimitReader(resp.Body, maxBodyBytes))
	if err != nil {
		return resp.StatusCode, nil, fmt.Errorf("parse HTML: %w", err)
	}

	return resp.StatusCode, doc, nil
}

// headCheck sends a HEAD request to targetURL and returns the HTTP status code.
// Falls back to GET when the server responds 405 (Method Not Allowed).
// Returns 0 on network or connection failure.
func headCheck(ctx context.Context, client *http.Client, targetURL string) int {
	doReq := func(method string) (int, error) {
		reqCtx, cancel := context.WithTimeout(ctx, headCheckTimeout)
		defer cancel()

		req, err := http.NewRequestWithContext(reqCtx, method, targetURL, nil)
		if err != nil {
			return 0, err
		}
		// Headers injected by StealthTransport automatically.

		resp, err := client.Do(req)
		if err != nil {
			return 0, err
		}
		defer resp.Body.Close()
		// Drain body to allow connection reuse (important for GET fallback)
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return resp.StatusCode, nil
	}

	status, err := doReq(http.MethodHead)
	if err != nil {
		if s := h2FallbackStatus(targetURL, err); s != 0 {
			return s
		}
		slog.Debug("HEAD check network failure", "url", targetURL, "error", err)
		return 0
	}

	// 405/500 → retry with GET (some servers disallow HEAD or return 500 for it)
	if status == http.StatusMethodNotAllowed || status == http.StatusInternalServerError {
		status, err = doReq(http.MethodGet)
		if err != nil {
			if s := h2FallbackStatus(targetURL, err); s != 0 {
				return s
			}
			return 0
		}
	}

	return status
}

// h2FallbackStatus retries via tls-client when the error indicates the server
// forced HTTP/2. Returns the actual status code, or 0 if the fallback didn't
// apply or also failed.
func h2FallbackStatus(targetURL string, err error) int {
	if !isH2ForceError(err) {
		return 0
	}
	slog.Debug("headCheck h2-force — retrying with tls-client", "url", targetURL)
	status, _, tlsErr := fetchWithTLSClient("", "", targetURL)
	if tlsErr != nil {
		return 0
	}
	return status
}
