package scanner

import (
	"math/rand/v2"
	"net/http"
)

// StealthTransport wraps any http.RoundTripper and injects localised stealth
// headers onto every outbound request that does not already have them set.
// Placing the injection here ensures headers are applied on both the initial
// request and every redirect hop without modifying call-site code.
type StealthTransport struct {
	Base    http.RoundTripper
	Headers http.Header
}

func (t *StealthTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	r2 := r.Clone(r.Context())
	for k, vs := range t.Headers {
		if r2.Header.Get(k) == "" {
			for _, v := range vs {
				r2.Header.Add(k, v)
			}
		}
	}
	return t.Base.RoundTrip(r2)
}

// userAgents is a pool of modern desktop browser User-Agent strings.
// Mixing Chrome, Firefox, and Edge across Windows, macOS, and Linux prevents
// WAFs from fingerprinting the crawler on a single static UA string.
var userAgents = []string{
	// Chrome 124 — Windows
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
	// Chrome 124 — macOS
	"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
	// Chrome 124 — Linux
	"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
	// Chrome 123 — Windows (stagger versions for diversity)
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
	// Edge 124 — Windows
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0",
	// Firefox 125 — Windows
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
	// Firefox 125 — macOS
	"Mozilla/5.0 (Macintosh; Intel Mac OS X 14.4; rv:125.0) Gecko/20100101 Firefox/125.0",
	// Firefox 125 — Linux
	"Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0",
}

// mobileUserAgents is a separate pool used for the 403-retry strategy.
// Some WAFs (e.g. Akamai) block generic desktop bots but are more permissive
// toward mobile traffic.  Rotating to a mobile string is the first mitigation
// when a desktop request is blocked.
var mobileUserAgents = []string{
	// iPhone 16 Pro — Safari 17
	"Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
	// iPhone 15 — Chrome 124
	"Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/124.0.6367.82 Mobile/15E148 Safari/604.1",
	// Samsung Galaxy S24 — Chrome 124
	"Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
	// Google Pixel 8 — Chrome 124
	"Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
	// iPad Pro — Safari 17
	"Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
}

// RandomUA returns a randomly selected desktop User-Agent string from the pool.
func RandomUA() string {
	return userAgents[rand.IntN(len(userAgents))]
}

// MobileUA returns a randomly selected mobile User-Agent string.
// Used when a desktop request is blocked (403) to attempt a mobile-UA retry.
func MobileUA() string {
	return mobileUserAgents[rand.IntN(len(mobileUserAgents))]
}

// buildHeadersFromConfig constructs request headers from an explicit
// LocalizationConfig and a caller-supplied User-Agent string.
// Used by buildCrawlClientWithConfig so that the UA can be fixed per-session
// rather than randomised on each call.
func buildHeadersFromConfig(cfg LocalizationConfig, ua string) http.Header {
	h := http.Header{}
	h.Set("User-Agent", ua)
	h.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
	h.Set("Accept-Language", cfg.AcceptLanguage)
	h.Set("Accept-Encoding", "gzip, deflate, br")
	h.Set("Connection", "keep-alive")
	h.Set("Upgrade-Insecure-Requests", "1")
	h.Set("Sec-Fetch-Dest", "document")
	h.Set("Sec-Fetch-Mode", "navigate")
	h.Set("Sec-Fetch-Site", "none")
	h.Set("Sec-Fetch-User", "?1")
	if cfg.Referer != "" {
		h.Set("Referer", cfg.Referer)
	}
	if cfg.LangCookie != "" {
		h.Set("Cookie", cfg.LangCookie)
	}
	return h
}

// BuildHeaders constructs a complete, browser-realistic set of HTTP request
// headers for the given region. The Accept-Language, Referer, and Cookie
// headers are localised so regional WAFs and geo-redirect logic see a
// convincing local visitor profile.
func BuildHeaders(regionCode string) http.Header {
	loc := GetLocalization(regionCode)
	h := http.Header{}

	h.Set("User-Agent", RandomUA())
	h.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
	h.Set("Accept-Language", loc.AcceptLanguage)
	h.Set("Accept-Encoding", "gzip, deflate, br")
	h.Set("Connection", "keep-alive")
	h.Set("Upgrade-Insecure-Requests", "1")
	h.Set("Sec-Fetch-Dest", "document")
	h.Set("Sec-Fetch-Mode", "navigate")
	h.Set("Sec-Fetch-Site", "none")
	h.Set("Sec-Fetch-User", "?1")
	if loc.Referer != "" {
		h.Set("Referer", loc.Referer)
	}
	if loc.LangCookie != "" {
		h.Set("Cookie", loc.LangCookie)
	}

	return h
}
