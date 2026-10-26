import type {LatestScanItem, MonitorSubType} from "@/lib/types";

// ── Retry-after formatter ─────────────────────────────────────────────────────

export function formatRetryAfter(seconds: number): string {
    if (seconds >= 60) {
        const minutes = Math.ceil(seconds / 60);
        return `Retry in ${minutes} minute${minutes === 1 ? "" : "s"}.`;
    }
    return `Retry in ${seconds} second${seconds === 1 ? "" : "s"}.`;
}

// ── All manageable compliance sub-types ──────────────────────────────────────

export const COMPLIANCE_SUBTYPES: {
    value: MonitorSubType;
    label: string;
    desc: string;
}[] = [
    {
        value: "PRIVACY_POLICY",
        label: "Privacy Policy",
        desc: "Checks that a privacy / data-protection page exists",
    },
    {
        value: "TERMS_OF_SERVICE",
        label: "Terms of Service",
        desc: "Checks that Terms of Service / Use are reachable",
    },
    {
        value: "IMPRESSUM",
        label: "Impressum",
        desc: "Required for DE / AT / CH websites",
    },
    {
        value: "COOKIE_POLICY",
        label: "Cookie Policy",
        desc: "Checks for a cookie-consent / cookie-policy page",
    },
    {
        value: "GDPR_REQUEST",
        label: "GDPR Request",
        desc: "Checks for a data-subject access request page",
    },
    {
        value: "COOKIE_BANNER",
        label: "Cookie Consent Banner",
        desc: "Detects the presence of a cookie consent banner",
    },
    {
        value: "TRACKER_DETECTION",
        label: "Tracker Detection",
        desc: "Detects undisclosed third-party trackers on your website",
    },
];

// ── Sub-type label map ────────────────────────────────────────────────────────

export const SUB_TYPE_LABELS: Record<MonitorSubType, string> = {
    SSL: "SSL Certificate",
    PRIVACY_POLICY: "Privacy Policy",
    TERMS_OF_SERVICE: "Terms of Service",
    IMPRESSUM: "Impressum",
    COOKIE_POLICY: "Cookie Policy",
    GDPR_REQUEST: "GDPR Request",
    COOKIE_BANNER: "Cookie Consent Banner",
    TRACKER_DETECTION: "Tracker Detection",
};

/** Shared score-to-colour mapping for the security-scan result panels. */
export function scoreTone(score: number): string {
    if (score >= 80) return "text-emerald-400";
    if (score >= 50) return "text-amber-300";
    return "text-red-400";
}

export function subTypeLabel(subType: MonitorSubType): string {
    return SUB_TYPE_LABELS[subType] ?? subType.replace(/_/g, " ");
}

/** Maps abbreviated badge labels (from notification payloads) back to full name + description. */
export const SHORT_LABEL_INFO: Record<string, { name: string; desc: string }> = {
    SSL: {name: "SSL Certificate", desc: "Validates the site's TLS/SSL certificate expiry and health"},
    PRIV: {name: "Privacy Policy", desc: "Checks that a privacy / data-protection page exists"},
    TOS: {name: "Terms of Service", desc: "Checks that Terms of Service / Use are reachable"},
    IMPR: {name: "Impressum", desc: "Legal disclosure page required for DE / AT / CH websites"},
    CKPL: {name: "Cookie Policy", desc: "Checks for a cookie-consent / cookie-policy page"},
    GDPR: {name: "GDPR Request", desc: "Checks for a data-subject access request page"},
    CKBNR: {name: "Cookie Consent Banner", desc: "Detects the presence of a cookie consent banner"},
    TRKR: {name: "Tracker Detection", desc: "Detects undisclosed third-party trackers on the website"},
};

// ── Keyword array helpers ─────────────────────────────────────────────────────

export function parseArr(val: unknown): string[] {
    if (val == null || val === "") return [];
    if (Array.isArray(val)) return val as string[];
    try {
        // Go's json.Marshal(nil) produces the string "null" which JSON.parse
        // returns as JS null — guard so callers never receive a non-array.
        const parsed = JSON.parse(String(val));
        return Array.isArray(parsed) ? parsed : [];
    } catch {
        return [];
    }
}

/**
 * Returns true only when the compliance result contains actual keyword data.
 * A scan that succeeded technically but never populated keyword arrays (e.g. because
 * no required_keywords were configured, or the page was unreachable) is INCOMPLETE.
 */
export function hasComplianceData(resultJson: Record<string, unknown> | null): boolean {
    if (!resultJson) return false;
    // Banner results use banner_detected instead of keyword arrays
    if ("banner_detected" in resultJson) return true;
    // Tracker results use tracker_count instead of keyword arrays
    if ("tracker_count" in resultJson) return true;
    return (
        parseArr(resultJson.found_keywords).length > 0 ||
        parseArr(resultJson.missing_keywords).length > 0 ||
        parseArr(resultJson.broken_keywords).length > 0
    );
}

// ── normalizeResultJson ───────────────────────────────────────────────────────

const ARRAY_FIELDS = [
    "found_keywords",
    "missing_keywords",
    "broken_keywords",
    "english_found_keywords",
    "english_missing_keywords",
] as const;

/**
 * Normalizes a raw resultJson from the API so that every keyword array field
 * is a real JS array, not a JSON-encoded string.
 *
 * The Go worker stores these as map[string]string (e.g. `"[\"Impressum\"]"`).
 * Parsing them once here means every subsequent parseArr() call hits the fast
 * Array.isArray() branch instead of calling JSON.parse() on every render.
 */
export function normalizeResultJson(
    raw: Record<string, unknown> | null
): Record<string, unknown> | null {
    if (!raw) return null;
    const out: Record<string, unknown> = {...raw};
    // String keyword arrays → real JS arrays (eliminates JSON.parse in render)
    for (const key of ARRAY_FIELDS) {
        if (typeof out[key] === "string") {
            out[key] = parseArr(out[key]);
        }
    }
    // keyword_evidence / english_evidence: JSON string → array of objects
    for (const evField of ["keyword_evidence", "english_evidence"] as const) {
        if (typeof out[evField] === "string") {
            try {
                out[evField] = JSON.parse(out[evField] as string) ?? [];
            } catch {
                out[evField] = [];
            }
        }
    }
    return out;
}

// ── KeywordEvidence type ──────────────────────────────────────────────────────

// Discovery v2: per-keyword scored evidence from the Go worker
export interface KeywordEvidence {
    keyword: string;
    score: number;
    /** "link" | "footer_link" | "text" | "deep_link" */
    location: string;
    evidence_context: string;
    /** Non-empty when found via Level-1 deep crawl */
    found_at?: string;
}

// ── AI Evidence type ─────────────────────────────────────────────────────────

/** Evidence for keywords discovered or verified by AI (no rule-based KeywordEvidence). */
export interface AIEvidence {
    keyword: string;
    linkHref: string;
    reasoning: string;
    source: string;
    confidence?: string;
}

// ── Remediation guidance ─────────────────────────────────────────────────────

export const REMEDIATION_GUIDANCE: Record<string, { title: string; tips: string[] }> = {
    // Compliance — missing keywords (per subType)
    PRIVACY_POLICY_MISSING: {
        title: "Privacy Policy Not Found",
        tips: [
            "Add a dedicated Privacy Policy page to your website.",
            "Include a clearly visible link in the footer (e.g. \"Privacy Policy\" or \"Datenschutzerklärung\").",
            "Cover: data controller identity, contact details, legal basis, retention periods, and data subject rights (GDPR Art. 13/14).",
        ],
    },
    TERMS_OF_SERVICE_MISSING: {
        title: "Terms of Service Not Found",
        tips: [
            "Create a Terms of Service (or Terms of Use) page.",
            "Link it from your website footer so it is accessible from every page.",
            "Include: scope of service, liability limitations, governing law, and dispute resolution.",
        ],
    },
    IMPRESSUM_MISSING: {
        title: "Impressum Not Found",
        tips: [
            "Add an Impressum page — required by law for DE/AT/CH websites (TMG §5, ECG §5).",
            "Must contain: company name, postal address, contact email/phone, managing director, and trade register number.",
            "Place the link in the footer with the label \"Impressum\".",
        ],
    },
    COOKIE_POLICY_MISSING: {
        title: "Cookie Policy Not Found",
        tips: [
            "Add a dedicated Cookie Policy page explaining which cookies your site uses.",
            "List each cookie's name, purpose, provider, and expiration period.",
            "Link it from your cookie consent banner and website footer.",
        ],
    },
    GDPR_REQUEST_MISSING: {
        title: "GDPR Data Request Page Not Found",
        tips: [
            "Provide a page or form where users can submit data access, rectification, or deletion requests.",
            "Include clear instructions on how requests are processed and expected response times (max 30 days under GDPR).",
            "Link it from your Privacy Policy.",
        ],
    },
    // Broken links
    BROKEN_LINKS: {
        title: "Broken Compliance Links",
        tips: [
            "Check that the linked pages are accessible and return HTTP 200.",
            "If you see \"Scan Blocked (WAF)\", whitelist the scanner's User-Agent or IP range.",
            "Verify the URLs have not been moved — update any outdated links in your footer or navigation.",
        ],
    },
    // Language inconsistency
    LANGUAGE_INCONSISTENCY: {
        title: "Language Inconsistency",
        tips: [
            "Legal pages must be available in the native language of your target market.",
            "If your site targets Germany, provide pages in German — an English-only version is not sufficient.",
            "Consider adding a language switcher or separate localized legal pages.",
        ],
    },
    // Cookie banner
    COOKIE_BANNER_MISSING: {
        title: "No Cookie Consent Banner",
        tips: [
            "Implement a cookie consent banner that loads before any non-essential cookies.",
            "Use a recognized CMP (Consent Management Platform) like Cookiebot, OneTrust, or Usercentrics.",
            "The banner must appear on every page for first-time visitors.",
        ],
    },
    COOKIE_BANNER_NO_REJECT: {
        title: "Missing Reject Option",
        tips: [
            "Add a clearly visible \"Reject\" or \"Decline\" button to your cookie banner.",
            "Under GDPR/ePrivacy, rejecting cookies must be as easy as accepting them.",
            "Do not use dark patterns — both options should have equal visual prominence.",
        ],
    },
    // SSL
    SSL_EXPIRING: {
        title: "SSL Certificate Expiring Soon",
        tips: [
            "Renew your SSL/TLS certificate before it expires to avoid browser warnings.",
            "Consider enabling auto-renewal with your certificate authority (e.g. Let's Encrypt).",
            "Verify renewal covers all subdomains (wildcard or SAN entries).",
        ],
    },
    SSL_EXPIRED: {
        title: "SSL Certificate Expired",
        tips: [
            "Your SSL certificate has expired — visitors see security warnings and browsers may block access.",
            "Renew immediately through your certificate authority or hosting provider.",
            "After renewal, verify the new certificate is correctly installed using an SSL checker tool.",
        ],
    },
    // Third-party trackers
    TRACKER_DETECTION_FOUND: {
        title: "Third-Party Trackers Detected",
        tips: [
            "Review detected trackers and remove any that are not essential for your site.",
            "Disclose all third-party trackers in your Privacy Policy (GDPR Art. 13 requires listing data recipients).",
            "Ensure your cookie consent banner covers tracker categories (analytics, advertising, social).",
            "Consider privacy-focused alternatives (e.g. Plausible or Fathom instead of Google Analytics).",
        ],
    },
    // Not linked from homepage
    NOT_LINKED_FROM_HOMEPAGE: {
        title: "Not Linked from Homepage",
        tips: [
            "Add a direct link to this page from your homepage footer.",
            "Legal pages should be reachable within one click from any page on the site.",
            "Use clear, standard labels (e.g. \"Privacy Policy\", \"Impressum\") so visitors and crawlers can find them.",
        ],
    },
};

/**
 * Resolves the appropriate remediation guidance key for a compliance subType
 * that has missing keywords.
 */
export function complianceGuidanceKey(subType: MonitorSubType): string {
    return `${subType}_MISSING`;
}

// ── Sort weight for monitor rows ──────────────────────────────────────────────

export function scanSortWeight(item: LatestScanItem): number {
    if (item.effectiveStatus === "PENDING") return 0;
    if (item.effectiveStatus === "FAILING") return 1;
    if (item.effectiveStatus === "WARNING") return 2;
    if (item.effectiveStatus === "PASSING") return 4;
    return 3; // INACTIVE or unknown
}
