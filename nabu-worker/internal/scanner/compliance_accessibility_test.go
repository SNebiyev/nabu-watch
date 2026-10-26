package scanner

import (
	"testing"
)

// ─── canonicalizeURL ─────────────────────────────────────────────────────────

func TestCanonicalizeURL(t *testing.T) {
	tests := []struct {
		name    string
		input   string
		want    string
		wantErr bool
	}{
		// ── Baseline ─────────────────────────────────────────────────────────
		{
			name:  "already canonical",
			input: "https://example.com/datenschutz",
			want:  "https://example.com/datenschutz",
		},
		{
			name:  "root path preserved",
			input: "https://example.com/",
			want:  "https://example.com/",
		},
		{
			name:  "root path without trailing slash",
			input: "https://example.com",
			want:  "https://example.com",
		},

		// ── Scheme and host lowercasing ───────────────────────────────────────
		{
			name:  "uppercase scheme lowercased",
			input: "HTTPS://Example.COM/datenschutz",
			want:  "https://example.com/datenschutz",
		},
		{
			name:  "mixed-case host lowercased",
			input: "https://SPIEGEL.de/datenschutz",
			want:  "https://spiegel.de/datenschutz",
		},

		// ── www stripping ─────────────────────────────────────────────────────
		{
			name:  "www prefix stripped",
			input: "https://www.spiegel.de/datenschutz",
			want:  "https://spiegel.de/datenschutz",
		},
		{
			name:  "www4 prefix not stripped (not www.)",
			input: "https://www4.spiegel.de/datenschutz",
			want:  "https://www4.spiegel.de/datenschutz",
		},

		// ── Default port removal ──────────────────────────────────────────────
		{
			name:  "https default port 443 removed",
			input: "https://example.com:443/datenschutz",
			want:  "https://example.com/datenschutz",
		},
		{
			name:  "http default port 80 removed",
			input: "http://example.com:80/datenschutz",
			want:  "http://example.com/datenschutz",
		},
		{
			name:  "non-default port preserved",
			input: "https://example.com:8443/datenschutz",
			want:  "https://example.com:8443/datenschutz",
		},

		// ── Fragment stripping ───────────────────────────────────────────────
		{
			name:  "fragment stripped",
			input: "https://example.com/datenschutz#section-2",
			want:  "https://example.com/datenschutz",
		},
		{
			name:  "fragment-only URL stripped to path",
			input: "https://example.com/datenschutz#",
			want:  "https://example.com/datenschutz",
		},

		// ── Trailing slash normalization ──────────────────────────────────────
		{
			name:  "trailing slash stripped from non-root path",
			input: "https://example.com/datenschutz/",
			want:  "https://example.com/datenschutz",
		},
		{
			name:  "deep path trailing slash stripped",
			input: "https://example.com/legal/privacy/",
			want:  "https://example.com/legal/privacy",
		},
		{
			name:  "root path trailing slash preserved",
			input: "https://example.com/",
			want:  "https://example.com/",
		},

		// ── Combined normalizations ───────────────────────────────────────────
		{
			name:  "www + port 443 + fragment + trailing slash",
			input: "https://www.spiegel.de:443/datenschutz/#impressum",
			want:  "https://spiegel.de/datenschutz",
		},
		{
			name:  "uppercase host + www + port 80 on http",
			input: "HTTP://WWW.EXAMPLE.COM:80/privacy",
			want:  "http://example.com/privacy",
		},

		// ── Error cases ───────────────────────────────────────────────────────
		{
			name:    "mailto scheme rejected",
			input:   "mailto:admin@example.com",
			wantErr: true,
		},
		{
			name:    "javascript scheme rejected",
			input:   "javascript:void(0)",
			wantErr: true,
		},
		{
			name:    "ftp scheme rejected",
			input:   "ftp://example.com/file",
			wantErr: true,
		},
		{
			name:    "fragment-only input rejected",
			input:   "#section",
			wantErr: true,
		},
		{
			name:    "empty string rejected",
			input:   "",
			wantErr: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := canonicalizeURL(tc.input)
			if tc.wantErr {
				if err == nil {
					t.Errorf("canonicalizeURL(%q) = %q, want error", tc.input, got)
				}
				return
			}
			if err != nil {
				t.Errorf("canonicalizeURL(%q) unexpected error: %v", tc.input, err)
				return
			}
			if got != tc.want {
				t.Errorf("canonicalizeURL(%q)\n  got  %q\n  want %q", tc.input, got, tc.want)
			}
		})
	}
}

// ─── resolveAndCompareURL ────────────────────────────────────────────────────

func TestResolveAndCompareURL(t *testing.T) {
	const base = "https://spiegel.de"
	const baseWWW = "https://www.spiegel.de"

	tests := []struct {
		name   string
		base   string
		href   string
		target string
		want   bool
	}{
		// ── Relative hrefs ────────────────────────────────────────────────────
		{
			name:   "absolute-path href matches absolute target",
			base:   base,
			href:   "/datenschutz",
			target: "https://spiegel.de/datenschutz",
			want:   true,
		},
		{
			name:   "relative href resolves against base",
			base:   "https://spiegel.de/section/",
			href:   "datenschutz",
			target: "https://spiegel.de/section/datenschutz",
			want:   true,
		},
		{
			name:   "absolute-path href does not match different path",
			base:   base,
			href:   "/impressum",
			target: "https://spiegel.de/datenschutz",
			want:   false,
		},

		// ── Absolute hrefs ────────────────────────────────────────────────────
		{
			name:   "absolute href matches identical target",
			base:   base,
			href:   "https://spiegel.de/datenschutz",
			target: "https://spiegel.de/datenschutz",
			want:   true,
		},
		{
			name:   "absolute href on different domain does not match",
			base:   base,
			href:   "https://other.com/datenschutz",
			target: "https://spiegel.de/datenschutz",
			want:   false,
		},

		// ── Protocol-relative hrefs ───────────────────────────────────────────
		{
			name:   "protocol-relative href resolved from https base",
			base:   base,
			href:   "//spiegel.de/datenschutz",
			target: "https://spiegel.de/datenschutz",
			want:   true,
		},
		{
			name:   "protocol-relative href with wrong host does not match",
			base:   base,
			href:   "//other.de/datenschutz",
			target: "https://spiegel.de/datenschutz",
			want:   false,
		},

		// ── Fragment stripping ────────────────────────────────────────────────
		{
			name:   "href with fragment matches fragment-free target",
			base:   base,
			href:   "/datenschutz#abschnitt-2",
			target: "https://spiegel.de/datenschutz",
			want:   true,
		},
		{
			name:   "target with fragment matches href without fragment",
			base:   base,
			href:   "/datenschutz",
			target: "https://spiegel.de/datenschutz#inhalt",
			want:   true,
		},

		// ── Trailing slash normalization ──────────────────────────────────────
		{
			name:   "href with trailing slash matches target without",
			base:   base,
			href:   "/datenschutz/",
			target: "https://spiegel.de/datenschutz",
			want:   true,
		},
		{
			name:   "target with trailing slash matches href without",
			base:   base,
			href:   "/datenschutz",
			target: "https://spiegel.de/datenschutz/",
			want:   true,
		},

		// ── www prefix normalization ──────────────────────────────────────────
		{
			name:   "href with www matches target without www",
			base:   base,
			href:   "https://www.spiegel.de/datenschutz",
			target: "https://spiegel.de/datenschutz",
			want:   true,
		},
		{
			name:   "base www resolves relative href; target without www",
			base:   baseWWW,
			href:   "/datenschutz",
			target: "https://spiegel.de/datenschutz",
			want:   true,
		},

		// ── Default port normalization ────────────────────────────────────────
		{
			name:   "href with explicit port 443 matches portless target",
			base:   base,
			href:   "https://spiegel.de:443/datenschutz",
			target: "https://spiegel.de/datenschutz",
			want:   true,
		},

		// ── Case normalization ────────────────────────────────────────────────
		{
			name:   "uppercase host in href normalized",
			base:   base,
			href:   "https://SPIEGEL.DE/datenschutz",
			target: "https://spiegel.de/datenschutz",
			want:   true,
		},

		// ── Non-navigable hrefs ───────────────────────────────────────────────
		{
			name:   "mailto href does not match",
			base:   base,
			href:   "mailto:datenschutz@spiegel.de",
			target: "https://spiegel.de/datenschutz",
			want:   false,
		},
		{
			name:   "javascript href does not match",
			base:   base,
			href:   "javascript:void(0)",
			target: "https://spiegel.de/datenschutz",
			want:   false,
		},
		{
			name:   "on-page anchor (#) does not match absolute target",
			base:   base,
			href:   "#datenschutz",
			target: "https://spiegel.de/datenschutz",
			want:   false,
		},

		// ── Edge cases ────────────────────────────────────────────────────────
		{
			name:   "empty href does not match",
			base:   base,
			href:   "",
			target: "https://spiegel.de/datenschutz",
			want:   false,
		},
		{
			name:   "unparseable base returns false (not panic)",
			base:   "://bad-base",
			href:   "/datenschutz",
			target: "https://spiegel.de/datenschutz",
			want:   false,
		},
		{
			name:   "unparseable target returns false (not panic)",
			base:   base,
			href:   "/datenschutz",
			target: "://bad-target",
			want:   false,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := resolveAndCompareURL(tc.base, tc.href, tc.target)
			if got != tc.want {
				t.Errorf("resolveAndCompareURL(%q, %q, %q) = %v, want %v",
					tc.base, tc.href, tc.target, got, tc.want)
			}
		})
	}
}
