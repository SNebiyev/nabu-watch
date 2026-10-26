package scanner

import "testing"

func TestContainsWholeWord(t *testing.T) {
	tests := []struct {
		name    string
		text    string
		keyword string
		want    bool
	}{
		// The exact bug: "AGB" must NOT match inside "Schlagbohrschrauber"
		{"AGB inside Schlagbohrschrauber", "Schlagbohrschrauber mit Akku", "AGB", false},
		{"AGB in URL slug schlagbohrschrauber", "schlagbohrschrauber", "agb", false},

		// Legitimate matches
		{"AGB standalone", "AGB", "AGB", true},
		{"AGB in link text", "Unsere AGB und Datenschutz", "AGB", true},
		{"AGB at end", "Zu unseren AGB", "AGB", true},
		{"AGB at start", "AGB gelten ab sofort", "AGB", true},
		{"AGB with punctuation", "Lesen Sie AGB.", "AGB", true},
		{"case insensitive", "Unsere agb", "AGB", true},

		// German keywords with umlauts
		{"Datenschutz standalone", "Datenschutzerklärung ist hier", "Datenschutzerklärung", true},
		{"Datenschutz not inside longer word", "XDatenschutzerklärungX", "Datenschutzerklärung", false},
		{"Impressum with pipe", "Impressum | Kontakt", "Impressum", true},
		{"Impressum inside longer word", "XmpressumImpressumX", "Impressum", false},
		{"Cookie not in Cookiebot", "Cookiebot settings", "Cookie", false},
		{"Cookie standalone", "Cookie Policy", "Cookie", true},

		// Normalized href scenarios (hyphens replaced by spaces)
		{"agb as url segment normalized", "https: example.de agb", "agb", true},
		{"agb not in schlagbohrschrauber normalized", "tests akku schlagbohrschrauber von bosch", "agb", false},

		// Multi-word keywords
		{"Allgemeine Geschäftsbedingungen", "Allgemeine Geschäftsbedingungen lesen", "Allgemeine Geschäftsbedingungen", true},
		{"Nutzungsbedingungen standalone", "Nutzungsbedingungen", "Nutzungsbedingungen", true},

		// Diacritic folding: accented keyword matching ASCII-folded text (and vice versa)
		// BBVA real-case: keyword "Política de Privacidad" must match href slug "politica-de-privacidad"
		{"Política matches politica (URL slug)", "https: www.bbva.es politica de privacidad", "Política de Privacidad", true},
		{"politica matches Política (accented body)", "Visite nuestra Política de Privacidad aquí", "politica de privacidad", true},
		{"Café matches cafe", "menu cafe americano", "Café", true},
		{"Señor matches senor", "contacto senor rodriguez", "Señor", true},
		{"Datenschutzerklärung matches ASCII form", "Datenschutzerklarung lesen", "Datenschutzerklärung", true},
		// NFD-decomposed "í" (i + U+0301 combining acute) — some CMSes emit this form
		{"NFD í matches NFC í", "poli\u0301tica de privacidad hier", "Política de Privacidad", true},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := containsWholeWord(tc.text, tc.keyword)
			if got != tc.want {
				t.Errorf("containsWholeWord(%q, %q) = %v, want %v", tc.text, tc.keyword, got, tc.want)
			}
		})
	}
}
