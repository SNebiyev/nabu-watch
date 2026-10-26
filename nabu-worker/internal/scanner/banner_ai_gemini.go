package scanner

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// Gemini Flash Lite API endpoint
const geminiBaseURL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent"

// geminiAnalyzer calls Google Gemini via the Generative Language REST API.
type geminiAnalyzer struct{ apiKey string }

func newGeminiAnalyzer() *geminiAnalyzer {
	return &geminiAnalyzer{apiKey: os.Getenv("GEMINI_API_KEY")}
}

func (a *geminiAnalyzer) Available() bool { return a != nil && a.apiKey != "" }

func (a *geminiAnalyzer) analyze(ctx context.Context, pageURL, cmpName string, doc *goquery.Document) (*aiBannerAnalysis, error) {
	prompt := buildPrompt(pageURL, cmpName, collectBannerDOMSnippet(doc), collectLabels(doc))

	reqBody := map[string]any{
		"contents": []map[string]any{
			{"parts": []map[string]string{{"text": prompt}}},
		},
		"generationConfig": map[string]any{
			"maxOutputTokens": 400,
			"temperature":     0,
			// Force API to return only well-formed JSON (no Markdown code-block wrapper)
			"responseMimeType": "application/json",
		},
	}

	body, err := json.Marshal(reqBody)
	if err != nil {
		return nil, fmt.Errorf("marshal request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, geminiBaseURL, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
	}
	httpReq.Header.Set("content-type", "application/json")
	httpReq.Header.Set("x-goog-api-key", a.apiKey)

	// Deterministic 30 s timeout
	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("api call: %w", err)
	}
	defer resp.Body.Close()

	var apiResp struct {
		Candidates []struct {
			Content struct {
				Parts []struct {
					Text string `json:"text"`
				} `json:"parts"`
			} `json:"content"`
		} `json:"candidates"`
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
	if len(apiResp.Candidates) == 0 || len(apiResp.Candidates[0].Content.Parts) == 0 {
		return nil, fmt.Errorf("empty response from gemini")
	}

	rawText := strings.TrimSpace(apiResp.Candidates[0].Content.Parts[0].Text)

	// JSON unmarshal works directly because responseMimeType guarantees well-formed JSON.
	var analysis aiBannerAnalysis
	if err := json.Unmarshal([]byte(rawText), &analysis); err != nil {
		return nil, fmt.Errorf("parse gemini json (%q): %w", rawText, err)
	}

	logResult("gemini", pageURL, &analysis)
	return &analysis, nil
}
