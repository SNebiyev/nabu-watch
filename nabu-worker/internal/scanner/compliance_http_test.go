package scanner

import "testing"

func TestIsTransientHeadlessError(t *testing.T) {
	transient := []string{
		// JS crashes
		"Uncaught TypeError: cannot read property",
		"TYPEERROR: x is not a function",
		"ReferenceError: foo is not defined",
		"SyntaxError: unexpected token",
		"RangeError: maximum call stack exceeded",
		"uncaught exception in page script",
		// Proxy tunnel / infra failures
		"headless navigate https://example.com: context canceled",
		"fetch homepage: headless fetch (hint) https://example.com: headless navigate https://example.com: context canceled",
		"context deadline exceeded",
		"headless navigate https://www.bank.example.at: page load error net::ERR_TUNNEL_CONNECTION_FAILED",
		"headless navigate https://example.com: page load error net::ERR_PROXY_CONNECTION_FAILED",
		"headless navigate https://example.com: page load error net::ERR_NO_SUPPORTED_PROXIES",
		// Case insensitive
		"err_tunnel_connection_failed",
		"ERR_PROXY_CONNECTION_FAILED",
		"err_no_supported_proxies",
	}
	for _, msg := range transient {
		if !isTransientHeadlessError(msg) {
			t.Errorf("expected transient=true for %q", msg)
		}
	}

	nonTransient := []string{
		"headless fetch failed: net::ERR_CONNECTION_REFUSED",
		"WAF block page detected",
		"parse headless HTML from https://example.com",
		"net::ERR_NAME_NOT_RESOLVED",
	}
	for _, msg := range nonTransient {
		if isTransientHeadlessError(msg) {
			t.Errorf("expected transient=false for %q", msg)
		}
	}
}
