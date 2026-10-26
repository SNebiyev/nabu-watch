package scanner

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

// generateTestCert creates a self-signed ECDSA certificate valid for
// daysUntilExpiry days from now, issued for 127.0.0.1 to match httptest servers.
func generateTestCert(t *testing.T, daysUntilExpiry int) tls.Certificate {
	t.Helper()

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate ECDSA key: %v", err)
	}

	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "nabu-test-server"},
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1")},
		NotBefore:             time.Now().Add(-1 * time.Hour),
		NotAfter:              time.Now().Add(time.Duration(daysUntilExpiry) * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IsCA:                  true,
		BasicConstraintsValid: true,
	}

	certDER, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create certificate: %v", err)
	}

	return tls.Certificate{
		Certificate: [][]byte{certDER},
		PrivateKey:  key,
	}
}

// startTLSTestServer starts an httptest TLS server with the given certificate
// and returns the server along with a tls.Config that trusts that certificate.
func startTLSTestServer(t *testing.T, tlsCert tls.Certificate) (*httptest.Server, *tls.Config) {
	t.Helper()

	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	srv.TLS = &tls.Config{Certificates: []tls.Certificate{tlsCert}}
	srv.StartTLS()
	t.Cleanup(srv.Close)

	x509Cert, err := x509.ParseCertificate(tlsCert.Certificate[0])
	if err != nil {
		t.Fatalf("parse test certificate: %v", err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(x509Cert)

	return srv, &tls.Config{
		RootCAs:    pool,
		MinVersion: tls.VersionTLS12,
	}
}

func TestSSLScanner_Type(t *testing.T) {
	if got := NewSSLScanner().Type(); got != "SSL" {
		t.Fatalf("Type() = %q, want %q", got, "SSL")
	}
}

func TestSSLScanner_ExtractsCertMetadata(t *testing.T) {
	const daysLeft = 60
	cert := generateTestCert(t, daysLeft)
	srv, clientCfg := startTLSTestServer(t, cert)

	s := &SSLScanner{tlsConfig: clientCfg}
	task := Task{
		MonitorID:   "mon-1",
		WebsiteID:   "web-1",
		WebsiteURL:  srv.URL,
		RegionCode:  "DE",
		MonitorType: "SSL",
	}

	result, err := s.Scan(context.Background(), task)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !result.Success {
		t.Fatalf("expected success=true; error detail: %q", result.Details["error"])
	}

	days, err := strconv.Atoi(result.Details["days_remaining"])
	if err != nil {
		t.Fatalf("days_remaining is not an integer: %q", result.Details["days_remaining"])
	}
	// Allow ±1 day for timing jitter between cert creation and scan.
	if days < daysLeft-1 || days > daysLeft {
		t.Errorf("days_remaining = %d, want ~%d", days, daysLeft)
	}

	for _, field := range []string{"host", "issuer", "subject", "expiry", "tls_version", "serial"} {
		if result.Details[field] == "" {
			t.Errorf("detail %q must be non-empty", field)
		}
	}
	if result.MonitorID != task.MonitorID {
		t.Errorf("MonitorID = %q, want %q", result.MonitorID, task.MonitorID)
	}
	if result.Type != "SSL" {
		t.Errorf("Type = %q, want %q", result.Type, "SSL")
	}
}

// TestSSLScanner_ExpiringCertificate verifies that the scanner still succeeds
// and reports the correct days_remaining when a certificate expires within 14
// days (the threshold that triggers a warning log).
func TestSSLScanner_ExpiringCertificate(t *testing.T) {
	const daysLeft = 10
	cert := generateTestCert(t, daysLeft)
	srv, clientCfg := startTLSTestServer(t, cert)

	s := &SSLScanner{tlsConfig: clientCfg}
	task := Task{
		MonitorID:   "mon-expiring",
		WebsiteID:   "web-1",
		WebsiteURL:  srv.URL,
		RegionCode:  "DE",
		MonitorType: "SSL",
	}

	result, err := s.Scan(context.Background(), task)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !result.Success {
		t.Fatalf("expected success=true; error: %q", result.Details["error"])
	}

	days, _ := strconv.Atoi(result.Details["days_remaining"])
	if days < daysLeft-1 || days > daysLeft {
		t.Errorf("days_remaining = %d, want ~%d", days, daysLeft)
	}
}

// TestSSLScanner_TLSDialFailure verifies that a connection failure produces a
// failed Result (not a hard Go error) with a populated error detail.
func TestSSLScanner_TLSDialFailure(t *testing.T) {
	// Find a port that is guaranteed to refuse connections by listening on it
	// and then closing before the scanner tries to connect.
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("could not allocate test port: %v", err)
	}
	addr := l.Addr().String()
	l.Close()

	s := NewSSLScanner()
	task := Task{
		MonitorID:   "mon-refused",
		WebsiteID:   "web-1",
		WebsiteURL:  "https://" + addr,
		RegionCode:  "DE",
		MonitorType: "SSL",
	}

	result, err := s.Scan(context.Background(), task)
	if err != nil {
		t.Fatalf("expected nil error (soft failure result), got: %v", err)
	}
	if result.Success {
		t.Error("expected success=false for refused connection")
	}
	if result.Details["error"] == "" {
		t.Error("error detail must be populated on dial failure")
	}
}
