package scanner

import (
	"crypto/sha256"
	"encoding/hex"
	"net/url"
	"strings"
	"unicode"

	"golang.org/x/text/runes"
	"golang.org/x/text/transform"
	"golang.org/x/text/unicode/norm"
)

// diacriticFolder decomposes to NFD, strips combining marks (Unicode Mn category),
// and recomposes to NFC. "Política" → "Politica", "Datenschutzerklärung" →
// "Datenschutzerklarung". This is essential for matching accented keywords
// against URL slugs (which are typically ASCII-folded) and for handling NFC vs
// NFD variants of the same visual glyph produced by different CMSes.
var diacriticFolder = transform.Chain(norm.NFD, runes.Remove(runes.In(unicode.Mn)), norm.NFC)

// foldDiacritics removes combining marks from s (e.g. "í" → "i", "ñ" → "n").
// Non-letter codepoints and letters without combining marks pass through
// unchanged. Dotless-i (U+0131), eszett (ß), and similar standalone variant
// letters are NOT stripped — only combining marks are removed.
func foldDiacritics(s string) string {
	out, _, err := transform.String(diacriticFolder, s)
	if err != nil {
		return s
	}
	return out
}

const snippetWindow = 50 // chars to capture before and after a keyword match

// mergeUnique returns a new slice with all elements of a followed by any
// elements of b that are not already present in a (case-sensitive).
// Order within each input is preserved.
func mergeUnique(a, b []string) []string {
	if len(b) == 0 {
		return append([]string(nil), a...)
	}
	seen := make(map[string]bool, len(a)+len(b))
	out := make([]string, 0, len(a)+len(b))
	for _, s := range a {
		seen[s] = true
		out = append(out, s)
	}
	for _, s := range b {
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

// extractSnippet returns a ~100-char context window centred on the keyword
// match at byteOffset in text.  Ellipsis markers are added when the window is
// truncated.  The function walks back/forward to UTF-8 character boundaries so
// it never splits a multi-byte rune.
func extractSnippet(text, keyword string, byteOffset int) string {
	start := byteOffset - snippetWindow
	if start < 0 {
		start = 0
	}
	end := byteOffset + len(keyword) + snippetWindow
	if end > len(text) {
		end = len(text)
	}
	// Align to valid UTF-8 boundaries
	for start > 0 && text[start]&0xC0 == 0x80 {
		start--
	}
	for end < len(text) && text[end]&0xC0 == 0x80 {
		end++
	}
	prefix, suffix := "", ""
	if start > 0 {
		prefix = "…"
	}
	if end < len(text) {
		suffix = "…"
	}
	return prefix + text[start:end] + suffix
}

// normalizeForMatch lowercases s and replaces underscores and hyphens with
// spaces so that href slugs like "/privacy_policy" or "/datenschutz-erklaerung"
// match keyword phrases like "Privacy Policy" or "Datenschutz Erklaerung".
//
// Diacritic folding happens inside containsWholeWord, not here, so that every
// matching path (pre-normalised slugs and raw body text alike) gets the same
// treatment through a single code path.
func normalizeForMatch(s string) string {
	s = strings.ToLower(s)
	s = strings.ReplaceAll(s, "_", " ")
	s = strings.ReplaceAll(s, "-", " ")
	return s
}

// containsWholeWord reports whether keyword appears as a standalone word in text.
// Boundaries are unicode-aware (handles German umlauts, etc.), so "AGB" will not
// match inside "Schlagbohrschrauber". Both text and keyword are lowercased and
// diacritic-folded internally so "Política" matches "politica", "Café" matches
// "cafe", etc. — body-text scans don't need the caller to pre-normalise.
func containsWholeWord(text, keyword string) bool {
	rText := []rune(foldDiacritics(strings.ToLower(text)))
	rKW := []rune(foldDiacritics(strings.ToLower(keyword)))
	n, m := len(rText), len(rKW)
	if m == 0 || n < m {
		return false
	}
	for i := 0; i <= n-m; i++ {
		if string(rText[i:i+m]) != string(rKW) {
			continue
		}
		leftOK := i == 0 || (!unicode.IsLetter(rText[i-1]) && !unicode.IsDigit(rText[i-1]))
		rightOK := i+m >= n || (!unicode.IsLetter(rText[i+m]) && !unicode.IsDigit(rText[i+m]))
		if leftOK && rightOK {
			return true
		}
	}
	return false
}

// hashContent returns a "sha256:<hex>" fingerprint of the normalised page text.
// Normalisation: already whitespace-collapsed text is lower-cased before hashing
// so minor case-only edits do not falsely trigger change detection.
func hashContent(normalizedText string) string {
	sum := sha256.Sum256([]byte(strings.ToLower(normalizedText)))
	return "sha256:" + hex.EncodeToString(sum[:])
}

// resolveRef resolves href against base and returns the absolute URL string.
// Returns "" for non-http/https results or parse errors.
func resolveRef(base *url.URL, href string) string {
	ref, err := url.Parse(href)
	if err != nil {
		return ""
	}
	resolved := base.ResolveReference(ref)
	if resolved.Scheme != "http" && resolved.Scheme != "https" {
		return ""
	}
	return resolved.String()
}
