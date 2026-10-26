package scanner

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
)

// pageAssessment is the AI's verdict on whether a homepage is scannable.
type pageAssessment struct {
	Scannable       bool             `json:"scannable"`
	Reason          string           `json:"reason"` // WAF_BLOCK, CAPTCHA, EMPTY_PAGE, CONTENT_OK
	DiscoveredLinks []discoveredLink `json:"discovered_links"`
	Reasoning       string           `json:"reasoning"`
}

// discoveredLink is a compliance document link found by the AI.
type discoveredLink struct {
	SubType string `json:"sub_type"` // PRIVACY_POLICY, TERMS_OF_SERVICE, etc.
	URL     string `json:"url"`
	Text    string `json:"text"` // anchor text
}

// assessPage asks AI whether this homepage is scannable and discovers compliance links.
// Called only by the scout monitor when the page looks suspicious (no links, small DOM, WAF detected).
func (a *complianceContentAnalyzer) assessPage(
	ctx context.Context, pageText string, url string, regionCode string,
) (*pageAssessment, error) {
	if !a.Available() {
		return nil, fmt.Errorf("AI provider not available")
	}

	text := pageText
	if len(text) > maxPageTextLen {
		text = text[:maxPageTextLen] + "\n[... truncated]"
	}

	prompt := buildPageAssessmentPrompt(text, url, regionCode)

	var rawJSON string
	var err error
	switch a.provider {
	case "claude":
		rawJSON, err = a.callClaude(ctx, prompt)
	case "gemini":
		rawJSON, err = a.callGemini(ctx, prompt)
	default:
		return nil, fmt.Errorf("unknown AI provider: %s", a.provider)
	}
	if err != nil {
		return nil, fmt.Errorf("AI assessPage call failed: %w", err)
	}

	rawJSON = strings.TrimSpace(rawJSON)
	// Strip markdown code fences (```json ... ```) that some AI models add
	if strings.HasPrefix(rawJSON, "```") {
		if i := strings.Index(rawJSON[3:], "\n"); i >= 0 {
			rawJSON = rawJSON[3+i+1:]
		}
		if j := strings.LastIndex(rawJSON, "```"); j >= 0 {
			rawJSON = rawJSON[:j]
		}
		rawJSON = strings.TrimSpace(rawJSON)
	}

	var result pageAssessment
	if err := json.Unmarshal([]byte(rawJSON), &result); err != nil {
		slog.Warn("AI assessPage: failed to parse response",
			"url", url,
			"raw", rawJSON,
			"error", err,
		)
		return nil, fmt.Errorf("parse AI response: %w", err)
	}

	slog.Info("AI page assessment complete",
		"url", url,
		"scannable", result.Scannable,
		"reason", result.Reason,
		"discovered_links", len(result.DiscoveredLinks),
		"reasoning", result.Reasoning,
	)

	return &result, nil
}

func buildPageAssessmentPrompt(pageText, url, regionCode string) string {
	return fmt.Sprintf(`You are a web compliance scanner assistant. Analyze this homepage and determine:

1. Is this a real, functional website homepage that can be scanned for compliance documents?
   - A WAF/CAPTCHA block page, empty page, or error page is NOT scannable.
   - A real homepage with navigation, content, and links IS scannable.

2. If scannable, find links to these compliance documents (for region %s):
   - PRIVACY_POLICY (privacy policy, data protection policy)
   - TERMS_OF_SERVICE (terms of use, terms and conditions, AGB, CGU)
   - IMPRESSUM (legal notice, site notice — mainly DE/AT/CH)
   - COOKIE_POLICY (cookie policy, cookie notice)
   - GDPR_REQUEST (data subject access request, DSAR, exercise your rights)

Website URL: %s

Page text (first %d chars):
---
%s
---

Respond in JSON only:
{
  "scannable": true/false,
  "reason": "WAF_BLOCK" | "CAPTCHA" | "EMPTY_PAGE" | "CONTENT_OK",
  "discovered_links": [
    {"sub_type": "PRIVACY_POLICY", "url": "https://...", "text": "Privacy Policy"},
    ...
  ],
  "reasoning": "One sentence explaining your assessment"
}

If the page is not scannable, discovered_links should be empty.
If scannable but no compliance links found, return scannable=true with empty discovered_links.`, regionCode, url, maxPageTextLen, pageText)
}
