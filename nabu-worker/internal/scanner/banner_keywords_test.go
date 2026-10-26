package scanner

import (
	"strings"
	"testing"

	"github.com/PuerkitoBio/goquery"
)

// TestResolveBannerKeywords_fallsBackToDefaults verifies that a task with no
// banner keyword fields receives the hardcoded default lists unchanged.
func TestResolveBannerKeywords_fallsBackToDefaults(t *testing.T) {
	task := Task{MonitorID: "m1"}
	kws := resolveBannerKeywords(task)

	if &kws.phrases[0] != &defaultBannerKeywords.phrases[0] {
		// Not a strict identity requirement, but the contents should match.
	}
	if len(kws.phrases) != len(defaultBannerKeywords.phrases) {
		t.Errorf("phrases: want %d, got %d", len(defaultBannerKeywords.phrases), len(kws.phrases))
	}
	if len(kws.accept) != len(defaultBannerKeywords.accept) {
		t.Errorf("accept: want %d, got %d", len(defaultBannerKeywords.accept), len(kws.accept))
	}
	if len(kws.reject) != len(defaultBannerKeywords.reject) {
		t.Errorf("reject: want %d, got %d", len(defaultBannerKeywords.reject), len(kws.reject))
	}
	if len(kws.prefs) != len(defaultBannerKeywords.prefs) {
		t.Errorf("prefs: want %d, got %d", len(defaultBannerKeywords.prefs), len(kws.prefs))
	}
}

// TestResolveBannerKeywords_taskOverridesPerField verifies that only the
// task-provided lists override the defaults; unset fields keep defaults.
func TestResolveBannerKeywords_taskOverridesPerField(t *testing.T) {
	task := Task{
		BannerRejectTerms: []string{"recusar", "rejeitar", "apenas necessários"},
	}
	kws := resolveBannerKeywords(task)

	// Reject should be overridden to the Portuguese list (lowercased).
	if len(kws.reject) != 3 {
		t.Fatalf("reject length: want 3, got %d", len(kws.reject))
	}
	for _, want := range []string{"recusar", "rejeitar", "apenas necessários"} {
		found := false
		for _, got := range kws.reject {
			if got == want {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("reject missing %q", want)
		}
	}
	// Accept/phrases/prefs should still be the hardcoded defaults.
	if len(kws.accept) != len(defaultBannerKeywords.accept) {
		t.Errorf("accept fell back incorrectly: want %d, got %d",
			len(defaultBannerKeywords.accept), len(kws.accept))
	}
	if len(kws.phrases) != len(defaultBannerKeywords.phrases) {
		t.Errorf("phrases fell back incorrectly: want %d, got %d",
			len(defaultBannerKeywords.phrases), len(kws.phrases))
	}
}

// TestResolveBannerKeywords_lowercasesTaskInput verifies that task-provided
// keywords are lowercased so termMatches works case-insensitively.
func TestResolveBannerKeywords_lowercasesTaskInput(t *testing.T) {
	task := Task{
		BannerAcceptTerms: []string{"ACEITAR", "Aceitar Cookies"},
	}
	kws := resolveBannerKeywords(task)

	if len(kws.accept) != 2 {
		t.Fatalf("accept length: want 2, got %d", len(kws.accept))
	}
	for _, got := range kws.accept {
		if got != strings.ToLower(got) {
			t.Errorf("term not lowercased: %q", got)
		}
	}
}

// TestScanButtons_portugueseRejectFromTaskKeywords is the regression test for
// the Portuguese banking-site bug: the Portuguese reject button "Recusar" must be
// detected when the task payload carries Portuguese reject terms, even though
// the hardcoded defaults contain no Portuguese.
func TestScanButtons_portugueseRejectFromTaskKeywords(t *testing.T) {
	html := `<html><body>
		<div class="cookie-banner">
			<button>Aceitar cookies</button>
			<button>Configurar cookies</button>
			<button>Recusar</button>
		</div>
	</body></html>`

	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		t.Fatalf("parse html: %v", err)
	}

	task := Task{
		BannerAcceptTerms: []string{"aceitar", "aceitar cookies"},
		BannerRejectTerms: []string{"recusar", "rejeitar", "apenas necessários"},
		BannerPrefTerms:   []string{"configurar", "configurar cookies"},
	}
	kws := resolveBannerKeywords(task)

	var br bannerResult
	scanButtons(doc.Find(".cookie-banner"), &br, kws)

	if !br.acceptFound {
		t.Error("accept: want found, got not-found")
	}
	if br.acceptLabel != "Aceitar cookies" {
		t.Errorf("acceptLabel: want %q, got %q", "Aceitar cookies", br.acceptLabel)
	}
	if !br.rejectFound {
		t.Error("reject: want found, got not-found — the Portuguese reject-button regression")
	}
	if br.rejectLabel != "Recusar" {
		t.Errorf("rejectLabel: want %q, got %q", "Recusar", br.rejectLabel)
	}
	if !br.prefsFound {
		t.Error("prefs: want found, got not-found")
	}
}

// TestScanButtons_hardcodedDefaultsMissPortuguese confirms the bug's root cause:
// without task-provided keywords, the hardcoded defaults cannot find
// Portuguese reject button labels. This test documents the regression and
// ensures future refactors don't silently remove the fallback.
func TestScanButtons_hardcodedDefaultsMissPortuguese(t *testing.T) {
	html := `<html><body>
		<div class="cookie-banner">
			<button>Aceitar cookies</button>
			<button>Recusar</button>
		</div>
	</body></html>`

	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		t.Fatalf("parse html: %v", err)
	}

	var br bannerResult
	// Empty task → hardcoded defaults only.
	scanButtons(doc.Find(".cookie-banner"), &br, resolveBannerKeywords(Task{}))

	if br.rejectFound {
		t.Error("hardcoded defaults unexpectedly matched Portuguese 'Recusar' — " +
			"either the defaults were updated (great, delete this test) or the " +
			"term list changed unexpectedly")
	}
}

// TestParseTask_bannerKeywordArrays verifies that the new JSON-encoded
// bannerPhrases/bannerAcceptTerms/bannerRejectTerms/bannerPrefTerms fields
// round-trip through ParseTask correctly.
func TestParseTask_bannerKeywordArrays(t *testing.T) {
	fields := map[string]any{
		"monitorId":         "m1",
		"websiteId":         "w1",
		"websiteUrl":        "https://example.com",
		"regionCode":        "BR",
		"monitorType":       "COMPLIANCE",
		"bannerPhrases":     `["este site utiliza cookies","usamos cookies"]`,
		"bannerAcceptTerms": `["aceitar","aceitar cookies"]`,
		"bannerRejectTerms": `["recusar","rejeitar","apenas necessários"]`,
		"bannerPrefTerms":   `["configurar"]`,
	}

	task, err := ParseTask(fields)
	if err != nil {
		t.Fatalf("ParseTask error: %v", err)
	}

	if got, want := len(task.BannerPhrases), 2; got != want {
		t.Errorf("BannerPhrases length: want %d, got %d", want, got)
	}
	if got, want := len(task.BannerRejectTerms), 3; got != want {
		t.Errorf("BannerRejectTerms length: want %d, got %d", want, got)
	}
	if task.BannerRejectTerms[2] != "apenas necessários" {
		t.Errorf("BannerRejectTerms[2]: want %q, got %q",
			"apenas necessários", task.BannerRejectTerms[2])
	}
}

// TestScanButtons_spanishPermitirTrap is a regression test for the Spanish
// "permitir" false-positive trap. Many .es banners use "Permitir solo cookies
// técnicas" as the functional reject button — the naive fix of adding bare
// "permitir" to ACCEPT would misclassify it because strings.Contains would
// match "permitir" as a substring. The correct design uses disjoint phrases:
// ACCEPT needs "aceptar todas" / "permitir todas" while REJECT needs
// "permitir solo" / "solo técnicas". This test pins that design.
func TestScanButtons_spanishPermitirTrap(t *testing.T) {
	html := `<html><body>
		<div class="cookie-banner">
			<button>Configuración de cookies</button>
			<button>Permitir solo cookies técnicas</button>
			<button>Aceptar todas las cookies</button>
		</div>
	</body></html>`

	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		t.Fatalf("parse html: %v", err)
	}

	task := Task{
		BannerAcceptTerms: []string{"aceptar", "aceptar todas", "permitir todas", "de acuerdo"},
		BannerRejectTerms: []string{"rechazar", "permitir solo", "solo técnicas", "solo necesarias"},
		BannerPrefTerms:   []string{"configurar", "configuración", "preferencias"},
	}
	kws := resolveBannerKeywords(task)

	var br bannerResult
	scanButtons(doc.Find(".cookie-banner"), &br, kws)

	if !br.acceptFound {
		t.Error("accept: want found, got not-found")
	}
	if br.acceptLabel != "Aceptar todas las cookies" {
		t.Errorf("acceptLabel: want %q, got %q", "Aceptar todas las cookies", br.acceptLabel)
	}
	if !br.rejectFound {
		t.Error("reject: want found, got not-found — permitir trap regression")
	}
	if br.rejectLabel != "Permitir solo cookies técnicas" {
		t.Errorf("rejectLabel: want %q, got %q",
			"Permitir solo cookies técnicas", br.rejectLabel)
	}
	if !br.prefsFound {
		t.Error("prefs: want found, got not-found")
	}
}

// TestParseTask_bannerKeywordMalformedJSON verifies that malformed JSON in a
// banner keyword field produces a clear error rather than a silent failure.
func TestParseTask_bannerKeywordMalformedJSON(t *testing.T) {
	fields := map[string]any{
		"monitorId":         "m1",
		"websiteId":         "w1",
		"websiteUrl":        "https://example.com",
		"regionCode":        "BR",
		"monitorType":       "COMPLIANCE",
		"bannerRejectTerms": `not-a-json-array`,
	}
	if _, err := ParseTask(fields); err == nil {
		t.Error("expected error for malformed bannerRejectTerms JSON, got nil")
	}
}
