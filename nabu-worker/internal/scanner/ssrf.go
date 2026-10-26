package scanner

import (
	"context"
	"fmt"
	"net"
	"net/url"
)

// privateCIDRs is the set of IP ranges that must never be dialed by the scanner.
// Covers: loopback, RFC 1918, link-local, CGNAT, IPv6 loopback/ULA/link-local,
// and the AWS instance-metadata address (169.254.169.254).
var privateCIDRs []*net.IPNet

func init() {
	blocks := []string{
		"127.0.0.0/8",    // IPv4 loopback
		"10.0.0.0/8",     // RFC 1918 class A
		"172.16.0.0/12",  // RFC 1918 class B
		"192.168.0.0/16", // RFC 1918 class C
		"169.254.0.0/16", // link-local / AWS metadata (169.254.169.254)
		"100.64.0.0/10",  // CGNAT (RFC 6598)
		"::1/128",        // IPv6 loopback
		"fc00::/7",       // IPv6 ULA
		"fe80::/10",      // IPv6 link-local
	}
	for _, cidr := range blocks {
		_, ipNet, err := net.ParseCIDR(cidr)
		if err != nil {
			panic("ssrf: bad CIDR " + cidr + ": " + err.Error())
		}
		privateCIDRs = append(privateCIDRs, ipNet)
	}
}

// isPrivateIP returns true when ip falls inside any of the blocked ranges.
func isPrivateIP(ip net.IP) bool {
	for _, cidr := range privateCIDRs {
		if cidr.Contains(ip) {
			return true
		}
	}
	return false
}

// ssrfDialContext wraps inner and blocks connections to private/loopback IPs.
// The hostname is resolved before dialing so redirect-based SSRF attacks cannot
// bypass the check.
func ssrfDialContext(inner *net.Dialer) func(ctx context.Context, network, addr string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, fmt.Errorf("ssrf: malformed address %q: %w", addr, err)
		}

		ips, err := net.DefaultResolver.LookupHost(ctx, host)
		if err != nil {
			return nil, fmt.Errorf("ssrf: DNS lookup failed for %q: %w", host, err)
		}

		for _, ipStr := range ips {
			ip := net.ParseIP(ipStr)
			if ip == nil {
				continue
			}
			if isPrivateIP(ip) {
				return nil, fmt.Errorf("ssrf: blocked connection to private IP %s (resolved from %s)", ipStr, host)
			}
		}

		return inner.DialContext(ctx, network, net.JoinHostPort(ips[0], port))
	}
}

// checkSSRF resolves the host of rawURL and returns an error if any resolved IP
// is in a private range.  Used as a pre-flight check before headless Chrome
// requests where the dial-level hook is not available.
func checkSSRF(ctx context.Context, rawURL string) error {
	u, err := url.Parse(rawURL)
	if err != nil {
		return fmt.Errorf("ssrf: invalid URL %q: %w", rawURL, err)
	}

	host := u.Hostname()
	if host == "" {
		return fmt.Errorf("ssrf: empty host in URL %q", rawURL)
	}

	// If the host is already an IP literal, check it directly.
	if ip := net.ParseIP(host); ip != nil {
		if isPrivateIP(ip) {
			return fmt.Errorf("ssrf: blocked private IP %s in URL", host)
		}
		return nil
	}

	ips, err := net.DefaultResolver.LookupHost(ctx, host)
	if err != nil {
		return fmt.Errorf("ssrf: DNS lookup failed for %q: %w", host, err)
	}

	for _, ipStr := range ips {
		ip := net.ParseIP(ipStr)
		if ip == nil {
			continue
		}
		if isPrivateIP(ip) {
			return fmt.Errorf("ssrf: blocked connection to private IP %s (resolved from %s)", ipStr, host)
		}
	}

	return nil
}
