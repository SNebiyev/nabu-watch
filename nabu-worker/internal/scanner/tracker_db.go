package scanner

import (
	"net/url"
	"strings"
)

// TrackerCategory classifies the purpose of a third-party tracker.
type TrackerCategory string

const (
	TrackerAnalytics      TrackerCategory = "analytics"
	TrackerAdvertising    TrackerCategory = "advertising"
	TrackerSocial         TrackerCategory = "social"
	TrackerFingerprinting TrackerCategory = "fingerprinting"
	TrackerTagManager     TrackerCategory = "tag_manager"
	TrackerHeatmap        TrackerCategory = "heatmap"
	TrackerCDP            TrackerCategory = "customer_data_platform"
)

// TrackerInfo describes a known third-party tracker.
type TrackerInfo struct {
	Name     string
	Category TrackerCategory
}

// knownTrackers maps domain suffixes to their tracker classification.
// classifyDomain walks the domain's suffix chain to find a match.
var knownTrackers = map[string]TrackerInfo{
	// ── Analytics ────────────────────────────────────────────────────────────
	"google-analytics.com":  {"Google Analytics", TrackerAnalytics},
	"analytics.google.com":  {"Google Analytics 4", TrackerAnalytics},
	"plausible.io":          {"Plausible Analytics", TrackerAnalytics},
	"matomo.cloud":          {"Matomo", TrackerAnalytics},
	"cdn.amplitude.com":     {"Amplitude", TrackerAnalytics},
	"mixpanel.com":          {"Mixpanel", TrackerAnalytics},
	"analytics.tiktok.com":  {"TikTok Analytics", TrackerAnalytics},
	"stats.wp.com":          {"WordPress Stats", TrackerAnalytics},
	"static.chartbeat.com":  {"Chartbeat", TrackerAnalytics},
	"cdn.heapanalytics.com": {"Heap Analytics", TrackerAnalytics},
	"t.co":                  {"X/Twitter Click Tracking", TrackerAnalytics},

	// ── Tag Managers ────────────────────────────────────────────────────────
	"googletagmanager.com": {"Google Tag Manager", TrackerTagManager},
	"tags.tiqcdn.com":      {"Tealium", TrackerTagManager},
	"cdn.segment.com":      {"Segment", TrackerTagManager},
	"segment.io":           {"Segment", TrackerTagManager},

	// ── Advertising ─────────────────────────────────────────────────────────
	"doubleclick.net":               {"Google Ads (DoubleClick)", TrackerAdvertising},
	"googlesyndication.com":         {"Google AdSense", TrackerAdvertising},
	"googleadservices.com":          {"Google Ads Conversion", TrackerAdvertising},
	"adservice.google.com":          {"Google Ad Services", TrackerAdvertising},
	"pagead2.googlesyndication.com": {"Google Publisher Ads", TrackerAdvertising},
	"ads.linkedin.com":              {"LinkedIn Ads", TrackerAdvertising},
	"snap.licdn.com":                {"LinkedIn Insight Tag", TrackerAdvertising},
	"ads.twitter.com":               {"X/Twitter Ads", TrackerAdvertising},
	"analytics.twitter.com":         {"X/Twitter Analytics", TrackerAnalytics},
	"sc-static.net":                 {"Snapchat Pixel", TrackerAdvertising},
	"ads.pinterest.com":             {"Pinterest Ads", TrackerAdvertising},
	"ct.pinterest.com":              {"Pinterest Conversion", TrackerAdvertising},
	"bat.bing.com":                  {"Microsoft Advertising", TrackerAdvertising},
	"ad.doubleclick.net":            {"DoubleClick Ad Exchange", TrackerAdvertising},
	"www.googletagservices.com":     {"Google Publisher Tag", TrackerAdvertising},
	"criteo.com":                    {"Criteo", TrackerAdvertising},
	"criteo.net":                    {"Criteo CDN", TrackerAdvertising},
	"adsrvr.org":                    {"The Trade Desk", TrackerAdvertising},
	"taboola.com":                   {"Taboola", TrackerAdvertising},
	"outbrain.com":                  {"Outbrain", TrackerAdvertising},

	// ── Social ──────────────────────────────────────────────────────────────
	"connect.facebook.net":   {"Meta SDK", TrackerSocial},
	"www.facebook.com/tr":    {"Meta Pixel", TrackerAdvertising},
	"platform.twitter.com":   {"X/Twitter Widget", TrackerSocial},
	"platform.instagram.com": {"Instagram Widget", TrackerSocial},
	"platform.linkedin.com":  {"LinkedIn Widget", TrackerSocial},
	"assets.pinterest.com":   {"Pinterest Widget", TrackerSocial},

	// ── Heatmap / Session Replay ────────────────────────────────────────────
	"hotjar.com":             {"Hotjar", TrackerHeatmap},
	"static.hotjar.com":      {"Hotjar", TrackerHeatmap},
	"mouseflow.com":          {"Mouseflow", TrackerHeatmap},
	"fullstory.com":          {"FullStory", TrackerHeatmap},
	"clarity.ms":             {"Microsoft Clarity", TrackerHeatmap},
	"cdn.mouseflow.com":      {"Mouseflow", TrackerHeatmap},
	"cdn.logrocket.io":       {"LogRocket", TrackerHeatmap},
	"rs.fullstory.com":       {"FullStory", TrackerHeatmap},
	"smartlook.com":          {"Smartlook", TrackerHeatmap},
	"recorder.smartlook.com": {"Smartlook", TrackerHeatmap},

	// ── Fingerprinting / Bot Detection ──────────────────────────────────────
	"fpcdn.io":    {"FingerprintJS", TrackerFingerprinting},
	"api.fpjs.io": {"FingerprintJS Pro", TrackerFingerprinting},

	// ── Customer Data Platforms ─────────────────────────────────────────────
	"segment.com":     {"Segment", TrackerCDP},
	"rudderstack.com": {"RudderStack", TrackerCDP},
	"cdn.mxpnl.com":   {"Mixpanel CDN", TrackerCDP},
}

// classifyDomain checks whether the given domain (or any parent suffix)
// matches a known tracker.  Returns the tracker info and true if found.
//
// For "ssl.google-analytics.com" it tries:
//
//	"ssl.google-analytics.com" → miss
//	"google-analytics.com"     → hit
func classifyDomain(domain string) (TrackerInfo, bool) {
	domain = strings.ToLower(strings.TrimSuffix(domain, "."))
	// Direct lookup first.
	if info, ok := knownTrackers[domain]; ok {
		return info, true
	}
	// Walk suffix chain: strip one label at a time from the left.
	for {
		idx := strings.Index(domain, ".")
		if idx < 0 {
			break
		}
		domain = domain[idx+1:]
		if info, ok := knownTrackers[domain]; ok {
			return info, true
		}
	}
	return TrackerInfo{}, false
}

// extractDomain extracts the hostname from a URL, stripped of port and "www.".
func extractDomain(rawURL string) string {
	u, err := url.Parse(rawURL)
	if err != nil || u.Host == "" {
		return ""
	}
	host := u.Hostname()
	return strings.ToLower(host)
}

// isThirdParty returns true if requestDomain is not a subdomain of siteDomain.
func isThirdParty(requestDomain, siteDomain string) bool {
	requestDomain = strings.ToLower(requestDomain)
	siteDomain = strings.ToLower(siteDomain)
	if requestDomain == siteDomain {
		return false
	}
	// "cdn.example.com" is first-party for "example.com"
	return !strings.HasSuffix(requestDomain, "."+siteDomain)
}
