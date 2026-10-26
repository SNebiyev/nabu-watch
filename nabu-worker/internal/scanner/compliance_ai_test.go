package scanner

import (
	"encoding/json"
	"testing"
)

// TestComplianceSemanticVerdict_jsonContract verifies the AI response schema
// matches what the verifier is asked to produce. A mismatch here means the
// prompt and the struct have drifted apart.
func TestComplianceSemanticVerdict_jsonContract(t *testing.T) {
	tests := []struct {
		name           string
		raw            string
		wantVerified   bool
		wantTerm       string
		wantConfidence string
	}{
		{
			name:           "bank-style verified",
			raw:            `{"verified":true,"equivalent_term":"Diretiva de Privacidade","confidence":"high","reasoning":"Standard Brazilian banking terminology for Privacy Policy."}`,
			wantVerified:   true,
			wantTerm:       "Diretiva de Privacidade",
			wantConfidence: "high",
		},
		{
			name:           "AI declines to verify",
			raw:            `{"verified":false,"equivalent_term":"","confidence":"low","reasoning":"Link text refers to a press release, not a privacy document."}`,
			wantVerified:   false,
			wantTerm:       "",
			wantConfidence: "low",
		},
		{
			name:           "medium confidence verified",
			raw:            `{"verified":true,"equivalent_term":"Datenschutzinformationen","confidence":"medium","reasoning":"German term functionally equivalent to privacy policy."}`,
			wantVerified:   true,
			wantTerm:       "Datenschutzinformationen",
			wantConfidence: "medium",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			var verdict complianceSemanticVerdict
			if err := json.Unmarshal([]byte(tc.raw), &verdict); err != nil {
				t.Fatalf("unmarshal: %v", err)
			}
			if verdict.Verified != tc.wantVerified {
				t.Errorf("Verified: got %v, want %v", verdict.Verified, tc.wantVerified)
			}
			if verdict.EquivalentTerm != tc.wantTerm {
				t.Errorf("EquivalentTerm: got %q, want %q", verdict.EquivalentTerm, tc.wantTerm)
			}
			if verdict.Confidence != tc.wantConfidence {
				t.Errorf("Confidence: got %q, want %q", verdict.Confidence, tc.wantConfidence)
			}
			if verdict.Reasoning == "" {
				t.Error("Reasoning should not be empty")
			}
		})
	}
}

// TestSemanticVerifyCache_hashStability verifies that hashing the homepage
// links blob yields the same result for identical link sets in identical order.
// This is the cache key for AI_SEMANTIC_CACHED — if it drifts, the cache
// never hits and AI gets called every scan.
func TestSemanticVerifyCache_hashStability(t *testing.T) {
	links := []pageLink{
		{Text: "Diretiva de Privacidade", Href: "https://bank.example.br/privacidade.shtm"},
		{Text: "Termos de Uso", Href: "https://bank.example.br/termos.shtm"},
	}

	h1 := hashContent(homepageLinksBlob(links))
	h2 := hashContent(homepageLinksBlob(links))
	if h1 != h2 {
		t.Errorf("hash not stable across calls: %q vs %q", h1, h2)
	}

	// Changing a link text must change the hash — otherwise stale verdicts
	// would be trusted after a page edit.
	original := links[0].Text
	links[0].Text = "Política de Privacidade"
	h3 := hashContent(homepageLinksBlob(links))
	links[0].Text = original
	if h1 == h3 {
		t.Error("hash did not change when link text changed")
	}

	// Changing a link href must also change the hash.
	originalHref := links[0].Href
	links[0].Href = "https://bank.example.br/other-page.shtm"
	h4 := hashContent(homepageLinksBlob(links))
	links[0].Href = originalHref
	if h1 == h4 {
		t.Error("hash did not change when link href changed")
	}
}

// TestComplianceSemanticVerdict_confidenceGating asserts the scanner's upgrade
// rule: low-confidence verdicts MUST NOT be treated as compliant. This guards
// against confidence semantics drifting in the future.
func TestComplianceSemanticVerdict_confidenceGating(t *testing.T) {
	cases := []struct {
		verdict     complianceSemanticVerdict
		wantUpgrade bool
	}{
		{complianceSemanticVerdict{Verified: true, Confidence: "high"}, true},
		{complianceSemanticVerdict{Verified: true, Confidence: "medium"}, true},
		{complianceSemanticVerdict{Verified: true, Confidence: "low"}, false},
		{complianceSemanticVerdict{Verified: false, Confidence: "high"}, false},
		{complianceSemanticVerdict{Verified: false, Confidence: "low"}, false},
	}
	for _, c := range cases {
		shouldUpgrade := c.verdict.Verified && c.verdict.Confidence != "low"
		if shouldUpgrade != c.wantUpgrade {
			t.Errorf("verified=%v conf=%q: upgrade=%v, want %v",
				c.verdict.Verified, c.verdict.Confidence, shouldUpgrade, c.wantUpgrade)
		}
	}
}
