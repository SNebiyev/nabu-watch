package scanner

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

const anthropicMessagesURL = "https://api.anthropic.com/v1/messages"

// aiBannerAnalysis is the structured JSON the AI returns for banner analysis.
type aiBannerAnalysis struct {
	Detected       bool     `json:"detected"`
	BannerSelector string   `json:"banner_selector"`
	AcceptFound    bool     `json:"accept_found"`
	AcceptLabel    string   `json:"accept_label"`
	RejectFound    bool     `json:"reject_found"`
	RejectLabel    string   `json:"reject_label"`
	Compliant      bool     `json:"compliant"`
	Issues         []string `json:"issues"`
	Reasoning      string   `json:"reasoning"`
}

// aiAnalyzer is the interface implemented by every AI backend.
type aiAnalyzer interface {
	Available() bool
	analyze(ctx context.Context, pageURL, cmpName string, doc *goquery.Document) (*aiBannerAnalysis, error)
}

// newAIAnalyzer returns the configured AI backend.
//
// Selection order:
//  1. AI_PROVIDER env var — "claude" | "gemini" forces a specific backend.
//  2. Auto-detect: if ANTHROPIC_API_KEY is set → Claude; else GEMINI_API_KEY → Gemini.
//  3. Returns nil (disabled) when no key is available.
func newAIAnalyzer() aiAnalyzer {
	provider := strings.ToLower(strings.TrimSpace(os.Getenv("AI_PROVIDER")))
	switch provider {
	case "claude":
		return newClaudeAnalyzer()
	case "gemini":
		return newGeminiAnalyzer()
	default:
		// Auto: prefer Claude, fall back to Gemini
		if a := newClaudeAnalyzer(); a.Available() {
			return a
		}
		return newGeminiAnalyzer()
	}
}

// buildPrompt returns the shared prompt sent to every AI backend.
func buildPrompt(pageURL, cmpName, domSnippet, labelBlock string) string {
	return fmt.Sprintf(`You are a GDPR cookie consent banner compliance analyzer.

Website URL: %s
Detected CMP: %s
%s
Interactive elements found on the page (buttons and links, max 150):
%s

Determine GDPR compliance:
1. Is a cookie consent banner present on the page?
2. What is the CSS selector of the banner's root element? Use the most specific selector you can identify from the DOM snippet — prefer #id selectors, then [class] selectors. If no banner is found, return "".
3. Is there an accept/consent button? (e.g. "Accept", "Agree", "Consent and continue", "Akzeptieren", "Zustimmen")
4. Is there a reject/decline option? Important rules:
   - "Apply settings" or "Save choices" inside a preferences panel counts as reject (user can uncheck categories)
   - "Zustimmung widerrufen" / "Withdraw consent" counts as a reject path
   - A "Preferences" or "Settings" button that leads to granular controls counts if there is no direct reject
5. Is the banner GDPR compliant? Both accept AND reject (or functional equivalent) must be available.

Respond with ONLY valid JSON, no markdown, no explanation outside the JSON:
{"detected":bool,"banner_selector":"CSS selector of banner root element","accept_found":bool,"accept_label":"string","reject_found":bool,"reject_label":"string","compliant":bool,"issues":["string"],"reasoning":"one sentence"}`,
		pageURL, cmpName, domSnippet, labelBlock)
}

// collectBannerDOMSnippet extracts a compact representation of candidate banner
// elements so the AI can identify the root selector.  Only elements that
// structurally resemble consent banners are included (dialogs, overlays,
// fixed/sticky containers, GDPR/consent-named elements).
func collectBannerDOMSnippet(doc *goquery.Document) string {
	var parts []string
	doc.Find(bannerCandidateSelector).Each(func(_ int, s *goquery.Selection) {
		if len(parts) >= 10 {
			return
		}
		tag := goquery.NodeName(s)
		id, _ := s.Attr("id")
		cls, _ := s.Attr("class")
		role, _ := s.Attr("role")

		line := "<" + tag
		if id != "" {
			line += ` id="` + id + `"`
		}
		if cls != "" {
			// Truncate very long class lists
			if len(cls) > 120 {
				cls = cls[:120] + "…"
			}
			line += ` class="` + cls + `"`
		}
		if role != "" {
			line += ` role="` + role + `"`
		}
		line += ">"

		// Count interactive children as a relevance signal
		buttons := s.Find("button, [role=button], a[href]").Length()
		line += fmt.Sprintf(" [%d interactive elements inside]", buttons)

		parts = append(parts, line)
	})
	if len(parts) == 0 {
		return ""
	}
	return "Candidate banner DOM elements:\n" + strings.Join(parts, "\n")
}

// collectLabels extracts unique short interactive element texts from the page.
func collectLabels(doc *goquery.Document) string {
	var labels []string
	seen := map[string]bool{}
	doc.Find("button, [role=button], a").Each(func(_ int, s *goquery.Selection) {
		if len(labels) >= 150 {
			return
		}
		text := strings.TrimSpace(s.Text())
		if text == "" || len(text) > 80 || seen[text] {
			return
		}
		seen[text] = true
		labels = append(labels, text)
	})
	if len(labels) == 0 {
		return "(none found)"
	}
	return "- " + strings.Join(labels, "\n- ")
}

// logResult emits a structured INFO log after a successful AI analysis.
func logResult(provider, pageURL string, a *aiBannerAnalysis) {
	slog.Info("BannerScanner: AI analysis complete",
		"provider", provider,
		"url", pageURL,
		"detected", a.Detected,
		"banner_selector", a.BannerSelector,
		"accept_found", a.AcceptFound,
		"reject_found", a.RejectFound,
		"compliant", a.Compliant,
		"reasoning", a.Reasoning,
	)
}

// ── Claude (Anthropic) ────────────────────────────────────────────────────────

// claudeAnalyzer calls Claude Haiku via the Anthropic Messages API.
type claudeAnalyzer struct{ apiKey string }

func newClaudeAnalyzer() *claudeAnalyzer {
	return &claudeAnalyzer{apiKey: os.Getenv("ANTHROPIC_API_KEY")}
}

func (a *claudeAnalyzer) Available() bool { return a != nil && a.apiKey != "" }

func (a *claudeAnalyzer) analyze(ctx context.Context, pageURL, cmpName string, doc *goquery.Document) (*aiBannerAnalysis, error) {
	prompt := buildPrompt(pageURL, cmpName, collectBannerDOMSnippet(doc), collectLabels(doc))

	reqBody := map[string]any{
		"model":      "claude-haiku-4-5-20251001",
		"max_tokens": 400,
		"messages":   []map[string]string{{"role": "user", "content": prompt}},
	}
	body, err := json.Marshal(reqBody)
	if err != nil {
		return nil, fmt.Errorf("marshal request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, anthropicMessagesURL, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
	}
	httpReq.Header.Set("x-api-key", a.apiKey)
	httpReq.Header.Set("anthropic-version", "2023-06-01")
	httpReq.Header.Set("content-type", "application/json")

	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("api call: %w", err)
	}
	defer resp.Body.Close()

	var apiResp struct {
		Content []struct {
			Text string `json:"text"`
		} `json:"content"`
		Error *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&apiResp); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	if apiResp.Error != nil {
		return nil, fmt.Errorf("api error: %s", apiResp.Error.Message)
	}
	if len(apiResp.Content) == 0 {
		return nil, fmt.Errorf("empty response from claude")
	}

	var analysis aiBannerAnalysis
	rawText := strings.TrimSpace(apiResp.Content[0].Text)
	if err := json.Unmarshal([]byte(rawText), &analysis); err != nil {
		return nil, fmt.Errorf("parse claude json (%q): %w", rawText, err)
	}
	logResult("claude", pageURL, &analysis)
	return &analysis, nil
}
