package scanner

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"time"
)

// Scanner is the core abstraction for all scan types.
// Each MonitorType (SSL, COMPLIANCE) maps to one implementation.
type Scanner interface {
	// Type returns the MonitorType string this scanner handles (e.g. "SSL").
	Type() string

	// Scan performs the check and returns a result or a non-nil error.
	// The caller always ACKs the Redis message regardless of outcome.
	Scan(ctx context.Context, task Task) (*Result, error)
}

// Task is the normalised representation of a Redis Stream message.
type Task struct {
	MonitorID           string
	WebsiteID           string
	WebsiteURL          string
	RegionCode          string
	MonitorType         string
	SubType             string   // optional; e.g. "PRIVACY_POLICY", "IMPRESSUM"
	RequiredKeywords    []string // optional; mandatory native-language keywords (≥1 found = PASSING)
	OptionalKeywords    []string // optional; bonus-tier native keywords (absence is NOT a warning)
	FallbackKeywords    []string // optional; English-language equivalents for language-consistency check
	ProxyURL            string   // optional; e.g. "http://proxy.internal:3128"
	TargetURL           string   // optional; when set, compliance scanner fetches this URL directly
	PreviousContentHash string   // optional; SHA-256 of the last known page content (targeted mode)
	BatchID             string   // optional; groups co-triggered scans in the audit trail
	AIHintSelector      string   // optional; CSS selector of the banner root element from a previous AI scan
	AIHintAcceptLabel   string   // optional; accept button label from a previous AI-assisted scan
	AIHintRejectLabel   string   // optional; reject button label from a previous AI-assisted scan
	AIHintDiscoveredURL string   // optional; compliance page URL discovered by AI in a previous homepage scan
	AIHintContentHash   string   // optional; SHA-256 of content at the AI-discovered URL from the previous scan
	// Semantic-verification hints (bank-style: link present but non-standard wording)
	AIHintVerifiedLinkHref     string // optional; href of the link AI previously verified as semantically equivalent
	AIHintVerifiedHomepageHash string // optional; SHA-256 of homepage links blob when the AI verdict was recorded
	// Compliance path hints (from website_compliance_paths table) — fast-path scan
	PathAnchorHref     string   // optional; href of the previously discovered compliance link on the homepage
	PathDiscoveredURL  string   // optional; absolute URL of the compliance document (for logging)
	FetchModeHint      string   // optional; "HEADLESS" = skip static fetch, go straight to headless
	ScreenshotsEnabled bool     // optional; when true, headless scans capture JPEG screenshots
	IsScout            bool     // optional; first homepage-parsing monitor in batch, performs page assessment
	BannerPhrases      []string // optional; DB-sourced banner-presence phrases (BannerScanner falls back to hardcoded defaults when empty)
	BannerAcceptTerms  []string // optional; DB-sourced accept button labels
	BannerRejectTerms  []string // optional; DB-sourced reject button labels
	BannerPrefTerms    []string // optional; DB-sourced preferences button labels
	// BaselineLinksOnPage is the median <a href> count from the website's
	// recent homepage-mode compliance scans.  When set, scanHomepage uses it
	// to detect drastic drops that signal a WAF interstitial slipping through
	// the primary shell-detection gates.  Zero means "no baseline available"
	// and disables the check.
	BaselineLinksOnPage int
}

// ParseTask builds a Task from raw Redis stream fields.
// Mandatory fields cause an error; optional fields are silently omitted when absent.
func ParseTask(fields map[string]any) (Task, error) {
	getString := func(key string) (string, error) {
		v, ok := fields[key]
		if !ok {
			return "", fmt.Errorf("missing field %q", key)
		}
		s, ok := v.(string)
		if !ok {
			return "", fmt.Errorf("field %q is not a string", key)
		}
		return s, nil
	}

	var t Task
	var err error

	// ── mandatory ────────────────────────────────────────────────────────────
	if t.MonitorID, err = getString("monitorId"); err != nil {
		return t, err
	}
	if t.WebsiteID, err = getString("websiteId"); err != nil {
		return t, err
	}
	if t.WebsiteURL, err = getString("websiteUrl"); err != nil {
		return t, err
	}
	if t.RegionCode, err = getString("regionCode"); err != nil {
		return t, err
	}
	if t.MonitorType, err = getString("monitorType"); err != nil {
		return t, err
	}

	// ── optional: subType ────────────────────────────────────────────────────
	if v, ok := fields["subType"]; ok {
		if s, ok := v.(string); ok {
			t.SubType = s
		}
	}

	// ── optional: keyword lists (JSON-encoded string arrays) ─────────────────
	if err := parseStringArrayField(fields, "requiredKeywords", &t.RequiredKeywords); err != nil {
		return t, err
	}
	if err := parseStringArrayField(fields, "optionalKeywords", &t.OptionalKeywords); err != nil {
		return t, err
	}
	if err := parseStringArrayField(fields, "fallbackKeywords", &t.FallbackKeywords); err != nil {
		return t, err
	}

	// ── optional: proxyUrl ───────────────────────────────────────────────────
	if v, ok := fields["proxyUrl"]; ok {
		if s, ok := v.(string); ok {
			t.ProxyURL = s
		}
	}

	// ── optional: targetUrl ───────────────────────────────────────────────────
	if v, ok := fields["targetUrl"]; ok {
		if s, ok := v.(string); ok {
			t.TargetURL = s
		}
	}

	// ── optional: previousContentHash ────────────────────────────────────────
	if v, ok := fields["previousContentHash"]; ok {
		if s, ok := v.(string); ok {
			t.PreviousContentHash = s
		}
	}

	// ── optional: batchId ─────────────────────────────────────────────────────
	if v, ok := fields["batchId"]; ok {
		if s, ok := v.(string); ok {
			t.BatchID = s
		}
	}

	// ── optional: AI hint fields from a previous AI-assisted scan ────────────
	if v, ok := fields["aiHintSelector"]; ok {
		if s, ok := v.(string); ok {
			t.AIHintSelector = s
		}
	}
	if v, ok := fields["aiHintAcceptLabel"]; ok {
		if s, ok := v.(string); ok {
			t.AIHintAcceptLabel = s
		}
	}
	if v, ok := fields["aiHintRejectLabel"]; ok {
		if s, ok := v.(string); ok {
			t.AIHintRejectLabel = s
		}
	}

	// ── optional: AI hint fields for compliance link discovery ───────────
	if v, ok := fields["aiHintDiscoveredURL"]; ok {
		if s, ok := v.(string); ok {
			t.AIHintDiscoveredURL = s
		}
	}
	if v, ok := fields["aiHintContentHash"]; ok {
		if s, ok := v.(string); ok {
			t.AIHintContentHash = s
		}
	}
	if v, ok := fields["aiHintVerifiedLinkHref"]; ok {
		if s, ok := v.(string); ok {
			t.AIHintVerifiedLinkHref = s
		}
	}
	if v, ok := fields["aiHintVerifiedHomepageHash"]; ok {
		if s, ok := v.(string); ok {
			t.AIHintVerifiedHomepageHash = s
		}
	}

	// ── optional: compliance path hints (fast-path scan) ─────────────────
	if v, ok := fields["pathAnchorHref"]; ok {
		if s, ok := v.(string); ok {
			t.PathAnchorHref = s
		}
	}
	if v, ok := fields["pathDiscoveredURL"]; ok {
		if s, ok := v.(string); ok {
			t.PathDiscoveredURL = s
		}
	}

	// ── optional: fetch-mode hint (skip static for JS-heavy sites) ──────
	if v, ok := fields["fetchModeHint"]; ok {
		if s, ok := v.(string); ok {
			t.FetchModeHint = s
		}
	}

	// ── optional: per-website screenshot opt-in ─────────────────────────
	if v, ok := fields["screenshotsEnabled"]; ok {
		if s, ok := v.(string); ok {
			t.ScreenshotsEnabled = s == "true"
		}
	}

	// ── optional: scout flag ─────────────────────────────────────────
	if v, ok := fields["isScout"]; ok {
		if s, ok := v.(string); ok {
			t.IsScout = s == "true"
		}
	}

	// ── optional: banner keyword lists (JSON-encoded string arrays) ──
	if err := parseStringArrayField(fields, "bannerPhrases", &t.BannerPhrases); err != nil {
		return t, err
	}
	if err := parseStringArrayField(fields, "bannerAcceptTerms", &t.BannerAcceptTerms); err != nil {
		return t, err
	}
	if err := parseStringArrayField(fields, "bannerRejectTerms", &t.BannerRejectTerms); err != nil {
		return t, err
	}
	if err := parseStringArrayField(fields, "bannerPrefTerms", &t.BannerPrefTerms); err != nil {
		return t, err
	}

	// ── optional: homepage link-count baseline (median of recent scans) ─────
	if v, ok := fields["baselineLinksOnPage"]; ok {
		if s, ok := v.(string); ok && s != "" {
			if n, err := strconv.Atoi(s); err == nil && n > 0 {
				t.BaselineLinksOnPage = n
			}
		}
	}

	return t, nil
}

// parseStringArrayField decodes a JSON-encoded string array from a Redis
// stream field into the given destination. Missing or empty fields are
// silently ignored — only malformed JSON produces an error.
func parseStringArrayField(fields map[string]any, key string, dst *[]string) error {
	v, ok := fields[key]
	if !ok {
		return nil
	}
	raw, ok := v.(string)
	if !ok || raw == "" {
		return nil
	}
	if err := json.Unmarshal([]byte(raw), dst); err != nil {
		return fmt.Errorf("parse %s: %w", key, err)
	}
	return nil
}

// Result holds the outcome of a completed scan.
type Result struct {
	MonitorID string
	Type      string
	Success   bool
	Details   map[string]string // values may be plain strings or JSON-encoded arrays
	ScannedAt time.Time
	BatchID   string // echoed from Task.BatchID for audit grouping
	RawDOM    string // optional; full merged DOM (including iframes/shadow roots) as fetched — persisted for audit
}
