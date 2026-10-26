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
)

// scanned_via tag suffixes shared with Java (TaskProducer.appendComplianceAIHints
// filters on these exact strings). Keep the Java mirror in sync.
const (
	ScannedViaAISemanticVerified = "AI_SEMANTIC_VERIFIED"
	ScannedViaAISemanticCached   = "AI_SEMANTIC_CACHED"
)

// confidenceLow is the semantic-verdict confidence tier that never upgrades
// compliance. Exported-case not required — only the gating check uses it.
const confidenceLow = "low"

// appendScanTag composes the scanned_via output string when an AI step
// augments the original scanner tag. On first AI augmentation it uses
// "original+TAG"; subsequent augmentations are appended as ",TAG".
func appendScanTag(current, original, tag string) string {
	if current != "" {
		return current + "," + tag
	}
	return original + "+" + tag
}

// homepageLinksBlob produces the canonical byte sequence hashed to form the
// semantic-verification cache key. Any change to this format invalidates every
// stored verdict on the next scan cycle — update with care.
func homepageLinksBlob(links []pageLink) string {
	var b strings.Builder
	for _, l := range links {
		b.WriteString(l.Text)
		b.WriteString("|")
		b.WriteString(l.Href)
		b.WriteString("\n")
	}
	return b.String()
}

// complianceSemanticVerdict is the structured JSON the AI returns when asked
// to judge whether a link's text is a semantically equivalent alternative name
// for a required compliance document type (e.g. "Diretiva de Privacidade" ≈
// "Política de Privacidade" in Brazilian Portuguese).
type complianceSemanticVerdict struct {
	Verified       bool   `json:"verified"`
	EquivalentTerm string `json:"equivalent_term"` // the non-standard term found, e.g. "Diretiva de Privacidade"
	Confidence     string `json:"confidence"`      // "high" | "medium" | "low"
	Reasoning      string `json:"reasoning"`       // one sentence explanation
}

// complianceContentAnalysis is the structured JSON the AI returns after
// evaluating the textual content of a legal/compliance page.
type complianceContentAnalysis struct {
	Score           int      `json:"score"`            // 0-100 content completeness
	MissingElements []string `json:"missing_elements"` // required items not found in the text
	Issues          []string `json:"issues"`           // specific problems or red flags
	Reasoning       string   `json:"reasoning"`        // one-sentence summary
}

// complianceContentAnalyzer calls AI to evaluate the quality and completeness
// of a legal page's content.  It reuses the same provider-selection logic as
// the banner AI (ANTHROPIC_API_KEY / GEMINI_API_KEY / AI_PROVIDER env var).
type complianceContentAnalyzer struct {
	provider string // "claude" or "gemini"
	apiKey   string
}

// newComplianceContentAnalyzer returns the configured AI backend for content
// analysis.  Selection mirrors newAIAnalyzer(): AI_PROVIDER env → auto-detect.
func newComplianceContentAnalyzer() *complianceContentAnalyzer {
	provider := strings.ToLower(strings.TrimSpace(os.Getenv("AI_PROVIDER")))
	switch provider {
	case "claude":
		if key := os.Getenv("ANTHROPIC_API_KEY"); key != "" {
			return &complianceContentAnalyzer{provider: "claude", apiKey: key}
		}
	case "gemini":
		if key := os.Getenv("GEMINI_API_KEY"); key != "" {
			return &complianceContentAnalyzer{provider: "gemini", apiKey: key}
		}
	default:
		if key := os.Getenv("ANTHROPIC_API_KEY"); key != "" {
			return &complianceContentAnalyzer{provider: "claude", apiKey: key}
		}
		if key := os.Getenv("GEMINI_API_KEY"); key != "" {
			return &complianceContentAnalyzer{provider: "gemini", apiKey: key}
		}
	}
	return nil
}

func (a *complianceContentAnalyzer) Available() bool {
	return a != nil && a.apiKey != ""
}

// maxPageTextLen caps the page text sent to the AI to control token usage.
const maxPageTextLen = 4000

// analyze sends the page text to the AI for content quality evaluation.
func (a *complianceContentAnalyzer) analyze(ctx context.Context, pageText, subType, regionCode string) (*complianceContentAnalysis, error) {
	// Truncate very long pages — first N chars usually contain the policy body.
	text := pageText
	if len(text) > maxPageTextLen {
		text = text[:maxPageTextLen] + "\n[... truncated]"
	}

	prompt := buildCompliancePrompt(text, subType, regionCode)

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
		return nil, err
	}

	var analysis complianceContentAnalysis
	if err := json.Unmarshal([]byte(rawJSON), &analysis); err != nil {
		return nil, fmt.Errorf("parse AI json (%q): %w", rawJSON, err)
	}

	slog.Info("ComplianceAI: content analysis complete",
		"provider", a.provider,
		"sub_type", subType,
		"region", regionCode,
		"score", analysis.Score,
		"missing_count", len(analysis.MissingElements),
		"issues_count", len(analysis.Issues),
	)
	return &analysis, nil
}

// ── Prompt builder ───────────────────────────────────────────────────────────

func buildCompliancePrompt(pageText, subType, regionCode string) string {
	checklist := complianceChecklist(subType, regionCode)
	return fmt.Sprintf(`You are a GDPR/legal compliance auditor. Analyze the following %s page content and evaluate its completeness.

Region: %s
Page type: %s

Required elements to check:
%s

Page content (may be truncated):
---
%s
---

Score the page 0-100 based on how many required elements are present and adequately covered.
- 80-100: Excellent — all or nearly all required elements present
- 60-79: Adequate — most elements present, minor gaps
- 40-59: Incomplete — significant elements missing
- 0-39: Poor — major required elements absent

List specific missing elements and any issues found (vague language, outdated info, missing contact details, etc.).

Respond with ONLY valid JSON, no markdown:
{"score":int,"missing_elements":["string"],"issues":["string"],"reasoning":"one sentence summary"}`,
		formatSubType(subType), regionCode, formatSubType(subType), checklist, pageText)
}

// complianceChecklist returns the jurisdiction-specific checklist for the given subType.
func complianceChecklist(subType, regionCode string) string {
	switch subType {
	case "PRIVACY_POLICY":
		return privacyPolicyChecklist(regionCode)
	case "IMPRESSUM":
		return impressumChecklist(regionCode)
	case "COOKIE_POLICY":
		return cookiePolicyChecklist()
	case "GDPR_REQUEST":
		return gdprRequestChecklist()
	case "TERMS_OF_SERVICE":
		return termsOfServiceChecklist()
	default:
		return "- Check for completeness and legal adequacy"
	}
}

func privacyPolicyChecklist(region string) string {
	base := `1. Identity of the data controller (company name, address)
2. Contact details (email, phone, or DPO contact)
3. Purposes of data processing (clearly listed)
4. Legal basis for each processing activity (consent, contract, legitimate interest, etc.)
5. Data retention periods or criteria
6. Data subject rights (access, rectification, erasure, portability, objection, restriction)
7. Third-party data sharing / processors disclosed
8. International data transfers (and safeguards if applicable)
9. Right to lodge complaint with supervisory authority
10. Automated decision-making / profiling disclosure (if applicable)
11. Date of last update`

	switch region {
	case "DE", "AT", "CH":
		return base + "\n12. Reference to DSGVO/DSG (applicable data protection law)"
	case "FR":
		return base + "\n12. Reference to CNIL and applicable French data protection law"
	case "GB":
		return base + "\n12. Reference to UK GDPR and ICO"
	case "BR":
		return base + "\n12. Reference to LGPD (Lei Geral de Proteção de Dados)"
	case "US":
		return base + "\n12. CCPA/CPRA disclosures (categories of personal information, sale/sharing opt-out)"
	default:
		return base
	}
}

func impressumChecklist(region string) string {
	base := `1. Full legal company/business name
2. Legal form (e.g., GmbH, AG, SAS, Ltd)
3. Complete postal address
4. Contact email address
5. Contact phone number
6. Managing director / legal representative name`

	switch region {
	case "DE", "AT":
		return base + `
7. Commercial register entry (Handelsregister) and number
8. VAT identification number (USt-IdNr.)
9. Responsible person for editorial content (§ 18 MStV) if applicable
10. Professional chamber and supervisory authority (for regulated professions)`
	case "CH":
		return base + `
7. UID (Unternehmens-Identifikationsnummer)
8. Commercial register entry`
	case "FR":
		return base + `
7. RCS registration number (Registre du Commerce)
8. Share capital (capital social)
9. Hosting provider name and address (hébergeur)
10. Publication director (directeur de la publication)`
	default:
		return base + `
7. Registration number (company register)
8. VAT/tax identification number`
	}
}

func cookiePolicyChecklist() string {
	return `1. Types of cookies used (essential, analytics, marketing, social media)
2. Purpose of each cookie category
3. Third-party cookies disclosed (names and providers)
4. Cookie retention periods / expiration
5. How to manage/delete cookies (browser settings or consent tool)
6. Link to or description of consent mechanism
7. Date of last update`
}

func gdprRequestChecklist() string {
	return `1. Clear explanation of data subject rights (access, rectification, erasure, portability, restriction, objection)
2. Contact method for submitting requests (form, email, or postal address)
3. Information about request processing timeline (typically 30 days under GDPR)
4. Identity verification requirements
5. DPO (Data Protection Officer) contact information if applicable
6. Right to complain to supervisory authority`
}

func termsOfServiceChecklist() string {
	return `1. Service description and scope
2. User obligations and acceptable use
3. Intellectual property rights
4. Limitation of liability
5. Termination conditions
6. Governing law and jurisdiction
7. Dispute resolution mechanism
8. Date of last update or effective date`
}

// ── AI link discovery (homepage mode fallback) ──────────────────────────────

// complianceLinkDiscovery is the structured JSON the AI returns when asked to
// identify which link on a homepage leads to a specific legal/compliance page.
type complianceLinkDiscovery struct {
	Found      bool     `json:"found"`      // true if the AI identified a matching link
	URL        string   `json:"url"`        // absolute URL of the discovered compliance page
	Confidence string   `json:"confidence"` // "high", "medium", "low"
	Keywords   []string `json:"keywords"`   // keywords the AI expects to find on that page
	Reasoning  string   `json:"reasoning"`  // one-sentence explanation
}

// discoverComplianceLink asks the AI to identify which link on the homepage
// likely leads to the specified compliance page type (e.g. GDPR_REQUEST,
// PRIVACY_POLICY).  The AI receives the full list of page links (text + href)
// and returns the best match.
func (a *complianceContentAnalyzer) discoverComplianceLink(ctx context.Context, links []linkSummary, subType, regionCode, websiteURL string) (*complianceLinkDiscovery, error) {
	if len(links) == 0 {
		return &complianceLinkDiscovery{Found: false, Reasoning: "no links on page"}, nil
	}

	prompt := buildLinkDiscoveryPrompt(links, subType, regionCode, websiteURL)

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
		return nil, err
	}

	var discovery complianceLinkDiscovery
	if err := json.Unmarshal([]byte(rawJSON), &discovery); err != nil {
		return nil, fmt.Errorf("parse AI json (%q): %w", rawJSON, err)
	}

	slog.Info("ComplianceAI: link discovery complete",
		"provider", a.provider,
		"sub_type", subType,
		"region", regionCode,
		"found", discovery.Found,
		"url", discovery.URL,
		"confidence", discovery.Confidence,
	)
	return &discovery, nil
}

// verifyLinkSemantics asks the AI whether a link's text is a semantically
// equivalent alternative name for the required compliance document in the
// region's language.  This is called when the Go rule-based scanner reported
// NON_COMPLIANT but candidate links were found whose text did not literally
// match any mandatory keyword — e.g. "Diretiva de Privacidade" for BR/PT where
// the mandatory term is "Política de Privacidade".
func (a *complianceContentAnalyzer) verifyLinkSemantics(
	ctx context.Context,
	linkText, linkHref, subType, regionCode string,
	mandatoryTerms []string,
) (*complianceSemanticVerdict, error) {
	docType := formatSubType(subType)
	termsJoined := strings.Join(mandatoryTerms, ", ")

	prompt := fmt.Sprintf(`You are a multilingual legal compliance expert.

Region: %s
Required document type: %s
Mandatory native-language term(s) for this region: %s

A link was found on the website homepage with the following text:
  Link text: %q
  Link URL:  %s

Question: Is this link's text a semantically equivalent alternative name for the required document type in the region's language or a closely related official language?

Consider: synonyms, dialectal variants, older terminology, regional naming conventions, and abbreviations that a native speaker would recognise as referring to the same legal document.

Respond with ONLY valid JSON, no markdown:
{"verified":bool,"equivalent_term":"the non-standard term found or empty","confidence":"high|medium|low","reasoning":"one sentence"}`,
		regionCode, docType, termsJoined, linkText, linkHref)

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
		return nil, err
	}

	var verdict complianceSemanticVerdict
	if err := json.Unmarshal([]byte(rawJSON), &verdict); err != nil {
		return nil, fmt.Errorf("parse AI json (%q): %w", rawJSON, err)
	}

	slog.Info("ComplianceAI: semantic link verification complete",
		"provider", a.provider,
		"sub_type", subType,
		"region", regionCode,
		"link_text", linkText,
		"verified", verdict.Verified,
		"equivalent_term", verdict.EquivalentTerm,
		"confidence", verdict.Confidence,
	)
	return &verdict, nil
}

// linkSummary is a simplified link representation sent to the AI.
type linkSummary struct {
	Text string `json:"text"`
	Href string `json:"href"`
}

// legalLinkPatterns are href/text substrings that indicate a link may lead to
// a legal or compliance page.  Used to pre-filter before sending to AI.
var legalLinkPatterns = []string{
	// Universal
	"cookie", "privacy", "legal", "terms", "gdpr", "conditions", "policy",
	"consent", "compliance", "data-protection", "data-request", "dpo",
	"rights", "opt-out", "notice", "disclaimer",
	// German
	"datenschutz", "impressum", "dsgvo", "datenschutzanfrage",
	"betroffenen", "meine-daten", "datenschutz-center", "nutzungsbedingungen",
	"datenschutzeinstellungen", "anfrage", "auskunft", "agb", "kontakt",
	// French
	"confidentialite", "donnees", "mentions", "rgpd", "cnil", "cgu",
	"juridique", "protection-des-donnees",
	// Portuguese / Brazilian
	"privacidade", "termos", "condicoes", "condições", "politica", "política",
	"seguranca", "segurança", "lgpd", "regulamento", "direito",
	// Generic footer-ish
	"contact", "about", "info", "footer", "rechtlich", "recht",
}

// filterLegalLinks returns only links whose text or href contain any of the
// known legal-page patterns.  This dramatically reduces token count sent to AI.
func filterLegalLinks(links []linkSummary) []linkSummary {
	var filtered []linkSummary
	for _, l := range links {
		lowerText := strings.ToLower(l.Text)
		lowerHref := strings.ToLower(l.Href)
		for _, pat := range legalLinkPatterns {
			if strings.Contains(lowerText, pat) || strings.Contains(lowerHref, pat) {
				// Truncate long link text to save tokens.
				if len(l.Text) > 80 {
					l.Text = l.Text[:80]
				}
				filtered = append(filtered, l)
				break
			}
		}
	}
	return filtered
}

// buildLinkDiscoveryPrompt creates the prompt for AI-based compliance link discovery.
func buildLinkDiscoveryPrompt(links []linkSummary, subType, regionCode, websiteURL string) string {
	// Pre-filter: only send links that match legal/compliance patterns.
	// This reduces a typical 200+ link list to ~10-30, cutting tokens by ~90%.
	candidates := filterLegalLinks(links)

	// If filtering removed everything, fall back to a capped raw list.
	if len(candidates) == 0 {
		maxLinks := 50
		if len(links) > maxLinks {
			candidates = links[:maxLinks]
		} else {
			candidates = links
		}
	}

	// Hard cap as safety net.
	if len(candidates) > 60 {
		candidates = candidates[:60]
	}

	linksJSON, _ := json.Marshal(candidates)

	pageLabel := formatSubType(subType)

	return fmt.Sprintf(`You are a legal compliance auditor. Identify which link leads to the "%s" page.

Website: %s | Region: %s

Links (filtered candidates):
%s

Return the absolute URL of the best match. If href is relative, prepend "%s".

JSON only:
{"found":bool,"url":"string or empty","confidence":"high|medium|low","keywords":["expected keywords"],"reasoning":"one sentence"}`,
		pageLabel, websiteURL, regionCode,
		string(linksJSON), websiteURL)
}

// ── Claude API call ──────────────────────────────────────────────────────────

func (a *complianceContentAnalyzer) callClaude(ctx context.Context, prompt string) (string, error) {
	reqBody := map[string]any{
		"model":      "claude-haiku-4-5-20251001",
		"max_tokens": 500,
		"messages":   []map[string]string{{"role": "user", "content": prompt}},
	}
	body, err := json.Marshal(reqBody)
	if err != nil {
		return "", fmt.Errorf("marshal: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, anthropicMessagesURL, bytes.NewReader(body))
	if err != nil {
		return "", fmt.Errorf("build request: %w", err)
	}
	httpReq.Header.Set("x-api-key", a.apiKey)
	httpReq.Header.Set("anthropic-version", "2023-06-01")
	httpReq.Header.Set("content-type", "application/json")

	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(httpReq)
	if err != nil {
		return "", fmt.Errorf("api call: %w", err)
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
		return "", fmt.Errorf("decode: %w", err)
	}
	if apiResp.Error != nil {
		return "", fmt.Errorf("api error: %s", apiResp.Error.Message)
	}
	if len(apiResp.Content) == 0 {
		return "", fmt.Errorf("empty response")
	}
	return strings.TrimSpace(apiResp.Content[0].Text), nil
}

// ── Gemini API call ──────────────────────────────────────────────────────────

func (a *complianceContentAnalyzer) callGemini(ctx context.Context, prompt string) (string, error) {
	reqBody := map[string]any{
		"contents": []map[string]any{
			{"parts": []map[string]string{{"text": prompt}}},
		},
		"generationConfig": map[string]any{
			"maxOutputTokens":  500,
			"temperature":      0,
			"responseMimeType": "application/json",
		},
	}
	body, err := json.Marshal(reqBody)
	if err != nil {
		return "", fmt.Errorf("marshal: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, geminiBaseURL, bytes.NewReader(body))
	if err != nil {
		return "", fmt.Errorf("build request: %w", err)
	}
	httpReq.Header.Set("content-type", "application/json")
	httpReq.Header.Set("x-goog-api-key", a.apiKey)

	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(httpReq)
	if err != nil {
		return "", fmt.Errorf("api call: %w", err)
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
		return "", fmt.Errorf("decode: %w", err)
	}
	if apiResp.Error != nil {
		return "", fmt.Errorf("api error: %s", apiResp.Error.Message)
	}
	if len(apiResp.Candidates) == 0 || len(apiResp.Candidates[0].Content.Parts) == 0 {
		return "", fmt.Errorf("empty response")
	}
	return strings.TrimSpace(apiResp.Candidates[0].Content.Parts[0].Text), nil
}
