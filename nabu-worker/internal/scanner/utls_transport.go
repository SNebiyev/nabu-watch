package scanner

import (
	"context"
	"crypto/tls"
	"fmt"
	"log/slog"
	"net"
	"time"

	utls "github.com/refraction-networking/utls"
)

// chromeHelloID selects the uTLS ClientHello preset that mimics the latest
// stable Chrome release.  HelloChrome_Auto tracks Chrome updates automatically.
//
// Why this matters: standard Go crypto/tls produces a well-known JA3 hash that
// Akamai, Cloudflare, and PerimeterX use to instantly flag requests as bot
// traffic.  Swapping to a Chrome-realistic fingerprint eliminates this signal.
var chromeHelloID = utls.HelloChrome_Auto

// utlsDialTLS returns a DialTLSContext function for http.Transport that
// performs TLS handshakes using uTLS with a Chrome-like fingerprint.
//
// ALPN is set to ["http/1.1"] only.  However, some servers (notably Cloudflare)
// ignore ALPN and force HTTP/2 anyway.  When this happens, the negotiated
// protocol will be "h2" — which Go's http.Transport cannot handle via
// DialTLSContext.  In that case we close the uTLS connection and fall back to
// a standard crypto/tls handshake (which respects TLSNextProto=empty and
// guarantees HTTP/1.1).
//
// Parameters:
//   - tcpDial: the underlying TCP dialer (should include SSRF protection)
//
// patchedALPNSpec is computed once at init and reused on every connection.
// UTLSIdToSpec returns a deep copy, so we cache the patched version and
// re-derive a fresh copy per connection from it (ApplyPreset mutates).
var patchedALPNSpec *utls.ClientHelloSpec

func init() {
	spec, err := utls.UTLSIdToSpec(chromeHelloID)
	if err != nil {
		return
	}
	for i, ext := range spec.Extensions {
		if alpn, ok := ext.(*utls.ALPNExtension); ok {
			alpn.AlpnProtocols = []string{"http/1.1"}
			spec.Extensions[i] = alpn
			break
		}
	}
	patchedALPNSpec = &spec
}

func utlsDialTLS(
	tcpDial func(ctx context.Context, network, addr string) (net.Conn, error),
) func(ctx context.Context, network, addr string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, _, err := net.SplitHostPort(addr)
		if err != nil {
			host = addr
		}

		rawConn, err := tcpDial(ctx, network, addr)
		if err != nil {
			return nil, fmt.Errorf("utls tcp dial %s: %w", addr, err)
		}

		cfg := &utls.Config{
			ServerName:         host,
			InsecureSkipVerify: false,
			MinVersion:         tls.VersionTLS12,
		}
		uConn := utls.UClient(rawConn, cfg, chromeHelloID)

		// Apply cached ALPN-patched Chrome spec (fresh copy per connection).
		if patchedALPNSpec != nil {
			connSpec, specErr := utls.UTLSIdToSpec(chromeHelloID)
			if specErr == nil {
				for i, ext := range connSpec.Extensions {
					if alpn, ok := ext.(*utls.ALPNExtension); ok {
						alpn.AlpnProtocols = []string{"http/1.1"}
						connSpec.Extensions[i] = alpn
						break
					}
				}
				if applyErr := uConn.ApplyPreset(&connSpec); applyErr != nil {
					rawConn.Close()
					return nil, fmt.Errorf("utls apply preset: %w", applyErr)
				}
			}
		}

		if deadline, ok := ctx.Deadline(); ok {
			_ = uConn.SetDeadline(deadline)
		}
		if err := uConn.Handshake(); err != nil {
			rawConn.Close()
			return nil, fmt.Errorf("utls handshake %s: %w", host, err)
		}
		_ = uConn.SetDeadline(time.Time{})

		// Check if server ignored our ALPN and forced h2 anyway.
		negotiated := uConn.ConnectionState().NegotiatedProtocol
		if negotiated == "h2" {
			// Go's http.Transport cannot handle h2 over DialTLSContext.
			// Fall back to standard crypto/tls which will negotiate http/1.1
			// (because the caller sets TLSNextProto to an empty map).
			uConn.Close()
			slog.Debug("Server forced h2 despite ALPN http/1.1 — falling back to standard TLS",
				"host", host,
			)
			return stdTLSFallback(ctx, tcpDial, network, addr, host)
		}

		return uConn, nil
	}
}

// stdTLSFallback dials with standard Go crypto/tls (no JA3 spoofing) when
// uTLS cannot be used (e.g. server forces h2).  The caller's TLSNextProto
// empty-map trick ensures HTTP/1.1.
func stdTLSFallback(
	ctx context.Context,
	tcpDial func(ctx context.Context, network, addr string) (net.Conn, error),
	network, addr, host string,
) (net.Conn, error) {
	rawConn, err := tcpDial(ctx, network, addr)
	if err != nil {
		return nil, fmt.Errorf("std tls tcp dial %s: %w", addr, err)
	}

	tlsConn := tls.Client(rawConn, &tls.Config{
		ServerName: host,
		MinVersion: tls.VersionTLS12,
		NextProtos: []string{"http/1.1"},
	})

	if deadline, ok := ctx.Deadline(); ok {
		_ = tlsConn.SetDeadline(deadline)
	}
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		rawConn.Close()
		return nil, fmt.Errorf("std tls handshake %s: %w", host, err)
	}
	_ = tlsConn.SetDeadline(time.Time{})

	return tlsConn, nil
}
