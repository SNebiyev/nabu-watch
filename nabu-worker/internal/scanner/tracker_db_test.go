package scanner

import "testing"

func TestClassifyDomain_knownTracker(t *testing.T) {
	info, ok := classifyDomain("google-analytics.com")
	if !ok {
		t.Fatal("expected google-analytics.com to be classified")
	}
	if info.Name != "Google Analytics" {
		t.Errorf("name = %q, want %q", info.Name, "Google Analytics")
	}
	if info.Category != TrackerAnalytics {
		t.Errorf("category = %q, want %q", info.Category, TrackerAnalytics)
	}
}

func TestClassifyDomain_subdomain(t *testing.T) {
	info, ok := classifyDomain("ssl.google-analytics.com")
	if !ok {
		t.Fatal("expected ssl.google-analytics.com to be classified via suffix")
	}
	if info.Name != "Google Analytics" {
		t.Errorf("name = %q, want %q", info.Name, "Google Analytics")
	}
}

func TestClassifyDomain_deepSubdomain(t *testing.T) {
	info, ok := classifyDomain("cdn.eu.hotjar.com")
	if !ok {
		t.Fatal("expected cdn.eu.hotjar.com to be classified via suffix")
	}
	if info.Category != TrackerHeatmap {
		t.Errorf("category = %q, want %q", info.Category, TrackerHeatmap)
	}
}

func TestClassifyDomain_unknown(t *testing.T) {
	_, ok := classifyDomain("example.com")
	if ok {
		t.Error("expected example.com to be unclassified")
	}
}

func TestClassifyDomain_caseInsensitive(t *testing.T) {
	info, ok := classifyDomain("GoogleTagManager.COM")
	if !ok {
		t.Fatal("expected case-insensitive match for GoogleTagManager.COM")
	}
	if info.Name != "Google Tag Manager" {
		t.Errorf("name = %q, want %q", info.Name, "Google Tag Manager")
	}
}

func TestIsThirdParty(t *testing.T) {
	tests := []struct {
		request, site string
		want          bool
	}{
		{"example.com", "example.com", false},
		{"cdn.example.com", "example.com", false},
		{"google-analytics.com", "example.com", true},
		{"sub.google-analytics.com", "example.com", true},
		{"Example.COM", "example.com", false},
	}
	for _, tc := range tests {
		got := isThirdParty(tc.request, tc.site)
		if got != tc.want {
			t.Errorf("isThirdParty(%q, %q) = %v, want %v", tc.request, tc.site, got, tc.want)
		}
	}
}

func TestExtractDomain(t *testing.T) {
	tests := []struct {
		url  string
		want string
	}{
		{"https://www.google-analytics.com/analytics.js", "www.google-analytics.com"},
		{"https://cdn.example.com:8080/path", "cdn.example.com"},
		{"not-a-url", ""},
	}
	for _, tc := range tests {
		got := extractDomain(tc.url)
		if got != tc.want {
			t.Errorf("extractDomain(%q) = %q, want %q", tc.url, got, tc.want)
		}
	}
}
