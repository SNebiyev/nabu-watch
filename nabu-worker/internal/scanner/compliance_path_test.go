package scanner

import (
	"testing"
)

// TestSelectComplianceLinkPath covers the four priority branches of
// selectComplianceLinkPath plus the guard cases (not compliant, no evidence).
func TestSelectComplianceLinkPath(t *testing.T) {
	pageLinks := []pageLink{
		{Text: "Política de Privacidade", Href: "https://example.com/privacy"},
		{Text: "Termos de Uso", Href: "https://example.com/tos"},
		{Text: "About", Href: "https://example.com/about"},
	}
	foundLinks := []FoundLink{
		{Keyword: "Política de Privacidade", Text: "Política de Privacidade", Href: "https://example.com/privacy"},
		{Keyword: "Privacy Policy", Text: "Privacy Policy", Href: "https://example.com/en/privacy"},
	}

	tests := []struct {
		name            string
		compliant       bool
		aiVerifiedTerm  string
		aiVerifiedHref  string
		aiScannedVia    string
		aiDiscoveredURL string
		pageLinks       []pageLink
		foundLinks      []FoundLink
		foundMandatory  []string
		foundFallback   []string
		wantHref        string
		wantSource      string
		wantConfidence  string
	}{
		{
			name:      "not compliant → no path emitted",
			compliant: false,
			wantHref:  "",
		},
		{
			name:           "AI semantic verify wins over everything",
			compliant:      true,
			aiVerifiedTerm: "Diretiva de Privacidade",
			aiVerifiedHref: "https://bank.example.br/privacidade.shtm",
			aiScannedVia:   "HEADLESS+AI_SEMANTIC_VERIFIED",
			foundLinks:     foundLinks, // ignored
			foundMandatory: []string{"Política de Privacidade"},
			wantHref:       "https://bank.example.br/privacidade.shtm",
			wantSource:     "AI_SEMANTIC_VERIFIED",
			wantConfidence: "high",
		},
		{
			name:           "AI discovery high-confidence trust (non-semantic aiScannedVia)",
			compliant:      true,
			aiVerifiedTerm: "Conditions Generales",
			aiVerifiedHref: "https://example.fr/cgu",
			aiScannedVia:   "STATIC+AI_DISCOVERY",
			wantHref:       "https://example.fr/cgu",
			wantSource:     "AI_DISCOVERY",
			wantConfidence: "high",
		},
		{
			name:            "AI discovery medium-confidence body-match path",
			compliant:       true,
			aiDiscoveredURL: "https://example.com/privacy",
			pageLinks:       pageLinks,
			wantHref:        "https://example.com/privacy",
			wantSource:      "AI_DISCOVERY",
			wantConfidence:  "medium",
		},
		{
			name:            "AI discovery medium — fallback anchor text is URL when not in pageLinks",
			compliant:       true,
			aiDiscoveredURL: "https://example.com/deep/hidden-privacy",
			pageLinks:       pageLinks,
			wantHref:        "https://example.com/deep/hidden-privacy",
			wantSource:      "AI_DISCOVERY",
			wantConfidence:  "medium",
		},
		{
			name:           "rule-based mandatory-native keyword match",
			compliant:      true,
			foundLinks:     foundLinks,
			foundMandatory: []string{"Política de Privacidade"},
			wantHref:       "https://example.com/privacy",
			wantSource:     "RULE_BASED",
			wantConfidence: "high",
		},
		{
			name:           "rule-based fallback keyword match (no mandatory found)",
			compliant:      true,
			foundLinks:     foundLinks,
			foundMandatory: nil,
			foundFallback:  []string{"Privacy Policy"},
			wantHref:       "https://example.com/en/privacy",
			wantSource:     "RULE_BASED",
			wantConfidence: "high",
		},
		{
			name:           "compliant via text_fallback only (no FoundLinks) → no path",
			compliant:      true,
			foundLinks:     nil,
			foundMandatory: []string{"Termos de Uso"},
			wantHref:       "",
		},
		{
			name:           "compliant but keyword not in any FoundLink → no path",
			compliant:      true,
			foundLinks:     foundLinks,
			foundMandatory: []string{"Completely Different Keyword"},
			wantHref:       "",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			href, text, url, source, confidence := selectComplianceLinkPath(
				tc.compliant,
				tc.aiVerifiedTerm, tc.aiVerifiedHref, tc.aiScannedVia, tc.aiDiscoveredURL,
				tc.pageLinks, tc.foundLinks, tc.foundMandatory, tc.foundFallback,
			)
			if href != tc.wantHref {
				t.Errorf("href: got %q, want %q", href, tc.wantHref)
			}
			if tc.wantHref == "" {
				// Unselected path: all fields should be empty
				if text != "" || url != "" || source != "" || confidence != "" {
					t.Errorf("expected all empty on unselected path, got text=%q url=%q source=%q confidence=%q",
						text, url, source, confidence)
				}
				return
			}
			if source != tc.wantSource {
				t.Errorf("source: got %q, want %q", source, tc.wantSource)
			}
			if confidence != tc.wantConfidence {
				t.Errorf("confidence: got %q, want %q", confidence, tc.wantConfidence)
			}
			if url != href {
				t.Errorf("discovered_url should equal href for this branch: got url=%q href=%q", url, href)
			}
			if text == "" {
				t.Error("anchor_text should not be empty when a path is selected")
			}
		})
	}
}
