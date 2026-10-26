package scanner

import (
	"context"
	"crypto/tls"
	"fmt"
	"log/slog"
	"math"
	"net"
	"net/url"
	"time"
)

// SSLScanner performs a real TLS handshake and extracts certificate metadata.
type SSLScanner struct {
	// tlsConfig, when non-nil, is cloned and used as the base TLS configuration
	// instead of the default. ServerName is always overridden from the task URL.
	// Intended for tests that need to inject a custom RootCAs pool.
	tlsConfig *tls.Config
}

func NewSSLScanner() *SSLScanner { return &SSLScanner{} }

func (s *SSLScanner) Type() string { return "SSL" }

func (s *SSLScanner) Scan(ctx context.Context, task Task) (*Result, error) {

	// robots.txt — respect Disallow before connecting.
	if !CheckRobotsTxt(ctx, task.WebsiteURL) {
		slog.Info("SSL scan skipped — disallowed by robots.txt",
			"monitor_id", task.MonitorID,
			"url", task.WebsiteURL,
		)
		return &Result{
			MonitorID: task.MonitorID,
			Type:      s.Type(),
			Success:   true,
			ScannedAt: time.Now().UTC(),
			BatchID:   task.BatchID,
			Details: map[string]string{
				"skipped":     "true",
				"skip_reason": "robots_txt_disallowed",
				"url":         task.WebsiteURL,
			},
		}, nil
	}

	host, port, err := extractHostPort(task.WebsiteURL)
	if err != nil {
		return nil, fmt.Errorf("parse url: %w", err)
	}

	addr := net.JoinHostPort(host, port)

	tlsCfg := &tls.Config{
		ServerName: host,
		MinVersion: tls.VersionTLS12,
	}
	if s.tlsConfig != nil {
		tlsCfg = s.tlsConfig.Clone()
		tlsCfg.ServerName = host
	}

	dialer := tls.Dialer{
		NetDialer: &net.Dialer{},
		Config:    tlsCfg,
	}

	// Retry once on transient TLS errors (e.g. macOS SecPolicyCreateSSL race
	// condition when many goroutines dial concurrently on cold start).
	const maxAttempts = 2
	var conn net.Conn
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		var dialErr error
		conn, dialErr = dialer.DialContext(ctx, "tcp", addr)
		if dialErr == nil {
			break
		}
		if attempt == maxAttempts {
			return failResult(task, fmt.Errorf("tls dial %s: %w", addr, dialErr)), nil
		}
		slog.Warn("SSL dial failed, retrying",
			"monitor_id", task.MonitorID,
			"addr", addr,
			"attempt", attempt,
			"error", dialErr,
		)
		select {
		case <-ctx.Done():
			return failResult(task, fmt.Errorf("tls dial %s: %w", addr, ctx.Err())), nil
		case <-time.After(500 * time.Millisecond):
		}
	}
	defer conn.Close()

	tlsConn, ok := conn.(*tls.Conn)
	if !ok {
		return failResult(task, fmt.Errorf("unexpected conn type")), nil
	}

	state := tlsConn.ConnectionState()
	if len(state.PeerCertificates) == 0 {
		return failResult(task, fmt.Errorf("no peer certificates from %s", addr)), nil
	}

	cert := state.PeerCertificates[0]
	expiry := cert.NotAfter
	daysLeft := math.Floor(time.Until(expiry).Hours() / 24)

	result := &Result{
		MonitorID: task.MonitorID,
		Type:      s.Type(),
		Success:   true,
		ScannedAt: time.Now().UTC(),
		BatchID:   task.BatchID,
		Details: map[string]string{
			"host":           host,
			"issuer":         cert.Issuer.CommonName,
			"subject":        cert.Subject.CommonName,
			"expiry":         expiry.Format(time.RFC3339),
			"days_remaining": fmt.Sprintf("%.0f", daysLeft),
			"tls_version":    tlsVersionName(state.Version),
			"serial":         cert.SerialNumber.String(),
		},
	}

	// Include robots.txt content for UI display (best-effort).
	if rtContent := GetRobotsTxtContent(ctx, task.WebsiteURL); rtContent != "" {
		result.Details["robots_txt_content"] = rtContent
	}

	slog.Info("SSL scan complete",
		"monitor_id", task.MonitorID,
		"host", host,
		"issuer", cert.Issuer.CommonName,
		"expiry", expiry.Format("2006-01-02"),
		"days_remaining", daysLeft,
		"tls_version", result.Details["tls_version"],
	)

	if daysLeft <= 14 {
		slog.Warn("SSL certificate expiring soon",
			"monitor_id", task.MonitorID,
			"host", host,
			"days_remaining", daysLeft,
		)
	}

	return result, nil
}

// ─── helpers ─────────────────────────────────────────────────────────────────

func extractHostPort(rawURL string) (host, port string, err error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return "", "", err
	}
	host = u.Hostname()
	port = u.Port()
	if port == "" {
		switch u.Scheme {
		case "https":
			port = "443"
		case "http":
			port = "80"
		default:
			port = "443"
		}
	}
	return host, port, nil
}

func failResult(task Task, err error) *Result {
	slog.Error("SSL scan failed",
		"monitor_id", task.MonitorID,
		"url", task.WebsiteURL,
		"error", err,
	)
	return &Result{
		MonitorID: task.MonitorID,
		Type:      "SSL",
		Success:   false,
		ScannedAt: time.Now().UTC(),
		BatchID:   task.BatchID,
		Details:   map[string]string{"error": err.Error()},
	}
}

func tlsVersionName(version uint16) string {
	switch version {
	case tls.VersionTLS10:
		return "TLS 1.0"
	case tls.VersionTLS11:
		return "TLS 1.1"
	case tls.VersionTLS12:
		return "TLS 1.2"
	case tls.VersionTLS13:
		return "TLS 1.3"
	default:
		return fmt.Sprintf("unknown(0x%04x)", version)
	}
}
