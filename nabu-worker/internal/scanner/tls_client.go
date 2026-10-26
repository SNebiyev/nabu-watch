package scanner

import (
	"fmt"
	"io"
	"log/slog"
	"strings"
	"sync"

	"github.com/PuerkitoBio/goquery"
	fhttp "github.com/bogdanfinn/fhttp"
	tls_client "github.com/bogdanfinn/tls-client"
	"github.com/bogdanfinn/tls-client/profiles"
)

// tlsClientPool reuses tls-client instances per region to avoid the overhead
// of creating a new Chrome-profiled TLS session on every request.
var tlsClientPool = &tlsPool{clients: make(map[string]tls_client.HttpClient)}

type tlsPool struct {
	mu      sync.Mutex
	clients map[string]tls_client.HttpClient
}

func (p *tlsPool) get(proxyURL, regionCode string) (tls_client.HttpClient, error) {
	key := regionCode + "|" + proxyURL
	p.mu.Lock()
	defer p.mu.Unlock()

	if c, ok := p.clients[key]; ok {
		return c, nil
	}

	opts := []tls_client.HttpClientOption{
		tls_client.WithTimeoutSeconds(45),
		tls_client.WithClientProfile(profiles.Chrome_144),
		tls_client.WithNotFollowRedirects(),
	}

	if proxyURL != "" {
		opts = append(opts, tls_client.WithProxyUrl(proxyURL))
	}

	c, err := tls_client.NewHttpClient(tls_client.NewNoopLogger(), opts...)
	if err != nil {
		return nil, fmt.Errorf("create tls-client: %w", err)
	}

	p.clients[key] = c
	return c, nil
}

// fetchWithTLSClient performs an HTTP GET using tls-client (Chrome_144 profile)
// which handles both TLS JA3 fingerprinting AND HTTP/2 SETTINGS fingerprinting.
// This is the primary fetch method — it works with Cloudflare h2-force sites
// that break standard Go net/http + utls.
//
// Returns status code, parsed goquery document, and error.
// On 4xx/5xx it returns the status code with an error (same as fetchAndParse).
func fetchWithTLSClient(proxyURL, regionCode, rawURL string) (int, *goquery.Document, error) {
	client, err := tlsClientPool.get(proxyURL, regionCode)
	if err != nil {
		return 0, nil, err
	}

	req, err := fhttp.NewRequest(fhttp.MethodGet, rawURL, nil)
	if err != nil {
		return 0, nil, fmt.Errorf("create tls-client request: %w", err)
	}

	// Apply browser-realistic headers.
	headers := BuildHeaders(regionCode)
	req.Header = fhttp.Header{
		"User-Agent":                {RandomUA()},
		"Accept":                    {headers.Get("Accept")},
		"Accept-Language":           {headers.Get("Accept-Language")},
		"Accept-Encoding":           {"gzip, deflate, br"},
		"Connection":                {"keep-alive"},
		"Upgrade-Insecure-Requests": {"1"},
		"Sec-Fetch-Dest":            {"document"},
		"Sec-Fetch-Mode":            {"navigate"},
		"Sec-Fetch-Site":            {"none"},
		"Sec-Fetch-User":            {"?1"},
		fhttp.HeaderOrderKey: {
			"user-agent",
			"accept",
			"accept-language",
			"accept-encoding",
			"connection",
			"upgrade-insecure-requests",
			"sec-fetch-dest",
			"sec-fetch-mode",
			"sec-fetch-site",
			"sec-fetch-user",
		},
		fhttp.PHeaderOrderKey: {
			":method",
			":authority",
			":scheme",
			":path",
		},
	}

	if ref := headers.Get("Referer"); ref != "" {
		req.Header.Set("Referer", ref)
	}
	if cookie := headers.Get("Cookie"); cookie != "" {
		req.Header.Set("Cookie", cookie)
	}

	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, fmt.Errorf("tls-client GET %s: %w", rawURL, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		if resp.StatusCode == fhttp.StatusForbidden {
			slog.Warn("tls-client received 403 — possible WAF block",
				"url", rawURL,
				"server", resp.Header.Get("Server"),
				"cf_ray", resp.Header.Get("CF-RAY"),
			)
		}
		return resp.StatusCode, nil, fmt.Errorf("tls-client: page returned HTTP %d", resp.StatusCode)
	}

	doc, err := goquery.NewDocumentFromReader(io.LimitReader(resp.Body, maxBodyBytes))
	if err != nil {
		return resp.StatusCode, nil, fmt.Errorf("tls-client parse HTML: %w", err)
	}

	return resp.StatusCode, doc, nil
}

// isH2ForceError checks if an error is caused by a server forcing HTTP/2
// despite ALPN negotiation for HTTP/1.1.
func isH2ForceError(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	// Go's net/http produces two variants when it receives HTTP/2 frames on
	// an HTTP/1.x connection: "malformed HTTP response" (full response parse
	// failure) and "malformed HTTP status code" (binary h2 SETTINGS frame
	// parsed as a status line, e.g. poste.it).
	if !strings.Contains(msg, "HTTP/1.x transport connection broken") {
		return false
	}
	return strings.Contains(msg, "malformed HTTP response") ||
		strings.Contains(msg, "malformed HTTP status code")
}
