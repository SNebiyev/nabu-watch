import {useEffect, useMemo, useState} from "react";
import {apiClient} from "@/lib/apiClient";
import {
    AlertTriangle,
    CheckCircle2,
    Clock,
    ExternalLink,
    Eye,
    Globe,
    Info,
    Link2,
    ShieldCheck,
    ShieldX,
    Sparkles,
    XCircle,
} from "lucide-react";
import {type AIEvidence, complianceGuidanceKey, type KeywordEvidence, parseArr, REMEDIATION_GUIDANCE} from "./utils";
import type {MonitorSubType} from "@/lib/types";
import {ScanFailedBanner} from "./SslDetails";

// ── HighlightedContext ────────────────────────────────────────────────────────

/** Renders keyword text with the matched term bolded. */
function HighlightedContext({
                                text,
                                keyword,
                            }: {
    text: string;
    keyword: string;
}) {
    const idx = text.toLowerCase().indexOf(keyword.toLowerCase());
    if (idx < 0) return <span>{text}</span>;
    return (
        <>
            {text.slice(0, idx)}
            <mark className="rounded bg-sky-500/25 px-0.5 font-bold text-sky-300 not-italic">
                {text.slice(idx, idx + keyword.length)}
            </mark>
            {text.slice(idx + keyword.length)}
        </>
    );
}

// ── EvidencePanel ─────────────────────────────────────────────────────────────

/** Expanded evidence panel shown below a found-keyword chip. */
function EvidencePanel({ev}: { ev: KeywordEvidence }) {
    const locationLabel: Record<string, string> = {
        link: "Homepage link",
        footer_link: "Footer link",
        text: "Page text",
        text_fallback: "Page text (fallback)",
        deep_link: "Sub-page (deep crawl)",
        ai_discovery: "AI-discovered page",
        ai_hint_verified: "AI hint verified",
    };

    return (
        <div
            className="mt-2 overflow-hidden rounded-xl border border-slate-200 dark:border-slate-700/50 bg-slate-50 dark:bg-slate-900 text-xs md:text-sm shadow-lg">
            {/* Header bar */}
            <div
                className="flex flex-wrap items-center gap-2 border-b border-slate-200 dark:border-slate-700/50 bg-slate-100 dark:bg-slate-200 dark:bg-slate-800/80 px-3 py-2">
                <span className="font-semibold text-sky-600 dark:text-sky-400">Evidence</span>
                <span
                    className="rounded-full bg-slate-200 dark:bg-slate-700 px-2 py-0.5 text-slate-600 dark:text-slate-300">
          {locationLabel[ev.location] ?? ev.location}
        </span>
                <span
                    className="ml-auto rounded-full bg-sky-100 dark:bg-sky-900/60 px-2 py-0.5 text-sky-600 dark:text-sky-300 ring-1 ring-sky-300/40 dark:ring-sky-700/40">
          score&nbsp;{ev.score}
        </span>
            </div>
            {/* Found-at URL */}
            {ev.found_at && (
                <div
                    className="flex items-center gap-1.5 border-b border-slate-200/60 dark:border-slate-700/30 px-3 py-1.5">
                    <ExternalLink className="h-3 w-3 shrink-0 text-slate-400 dark:text-slate-500"/>
                    <span
                        className="truncate font-mono text-[11px] text-sky-600/80 dark:text-sky-400/80">{ev.found_at}</span>
                </div>
            )}
            {/* Code snippet */}
            {ev.evidence_context && (
                <pre
                    className="overflow-x-auto whitespace-pre-wrap break-words px-3 py-2.5 font-mono text-[11px] leading-relaxed text-slate-600 dark:text-slate-300">
          <HighlightedContext text={ev.evidence_context} keyword={ev.keyword}/>
        </pre>
            )}
        </div>
    );
}

// ── AIEvidencePanel ──────────────────────────────────────────────────────────

/** Expanded evidence panel for AI-verified / AI-discovered keywords. */
function AIEvidencePanel({ev}: { ev: AIEvidence }) {
    const sourceLabel: Record<string, string> = {
        AI_SEMANTIC_VERIFIED: "AI Semantic Verification",
        AI_SEMANTIC_CACHED: "AI Semantic (cached)",
        AI_DISCOVERY: "AI Discovery",
    };

    return (
        <div
            className="mt-2 overflow-hidden rounded-xl border border-violet-200 dark:border-violet-700/50 bg-slate-50 dark:bg-slate-900 text-xs md:text-sm shadow-lg">
            {/* Header bar */}
            <div
                className="flex flex-wrap items-center gap-2 border-b border-violet-200 dark:border-violet-700/50 bg-violet-100 dark:bg-violet-900/40 px-3 py-2">
                <span className="flex items-center gap-1 font-semibold text-violet-600 dark:text-violet-400">
                    <Sparkles className="h-3 w-3 shrink-0"/>
                    AI Proof
                </span>
                <span
                    className="rounded-full bg-violet-200 dark:bg-violet-800/60 px-2 py-0.5 text-violet-600 dark:text-violet-300">
                    {sourceLabel[ev.source] ?? ev.source}
                </span>
                {ev.confidence && (
                    <span
                        className="ml-auto rounded-full bg-violet-100 dark:bg-violet-900/60 px-2 py-0.5 text-violet-600 dark:text-violet-300 ring-1 ring-violet-300/40 dark:ring-violet-700/40">
                        {ev.confidence}
                    </span>
                )}
            </div>
            {/* Verified link URL */}
            {ev.linkHref && (
                <div
                    className="flex items-center gap-1.5 border-b border-violet-200/60 dark:border-violet-700/30 px-3 py-1.5">
                    <ExternalLink className="h-3 w-3 shrink-0 text-violet-400 dark:text-violet-500"/>
                    <span
                        className="truncate font-mono text-[11px] text-violet-600/80 dark:text-violet-400/80">{ev.linkHref}</span>
                </div>
            )}
            {/* AI reasoning */}
            {ev.reasoning && (
                <div className="px-3 py-2.5">
                    <p className="text-[11px] leading-relaxed text-slate-600 dark:text-slate-300">
                        {ev.reasoning}
                    </p>
                </div>
            )}
        </div>
    );
}

// ── RemediationTip ───────────────────────────────────────────────────────────

/** Renders an actionable guidance box when issues are detected. */
export function RemediationTip({guidanceKey}: { guidanceKey: string }) {
    const guidance = REMEDIATION_GUIDANCE[guidanceKey];
    if (!guidance) return null;
    return (
        <div
            className="mt-2 rounded-lg border border-sky-200 dark:border-sky-800/30 bg-sky-50 dark:bg-sky-950/20 px-3 py-2.5">
            <p className="mb-1.5 flex items-center gap-1.5 text-[11px] font-semibold text-sky-600 dark:text-sky-400">
                <Info className="h-3 w-3 shrink-0"/>
                How to fix
            </p>
            <ul className="space-y-1">
                {guidance.tips.map((tip, i) => (
                    <li key={i} className="flex items-start gap-1.5 text-[11px] text-sky-700/80 dark:text-sky-300/80">
                        <span className="mt-0.5 shrink-0 text-sky-500 dark:text-sky-600">&#x2022;</span>
                        {tip}
                    </li>
                ))}
            </ul>
        </div>
    );
}

// ── MissingKeywordChips ───────────────────────────────────────────────────────

/** Missing-keyword badges: red XCircle + "Word Missing" sub-label. */
export function MissingKeywordChips({keywords}: { keywords: string[] }) {
    return (
        <div className="flex flex-wrap gap-2">
            {keywords.map((kw) => (
                <span key={kw} className="inline-flex flex-col items-start gap-0.5">
          <span
              className="inline-flex items-center gap-1.5 rounded-full bg-red-100 dark:bg-red-950/60 px-2.5 py-1 text-xs font-medium text-red-700 dark:text-red-300 ring-1 ring-red-300/40 dark:ring-red-700/40">
            <XCircle className="h-3 w-3 shrink-0 text-red-400"/>
              {kw}
          </span>
          <span className="ml-1 text-[10px] font-medium text-red-500/80">Word Missing</span>
        </span>
            ))}
        </div>
    );
}

// ── BrokenKeywordChips ────────────────────────────────────────────────────────

/** Maps an HTTP status code to a human-readable diagnostic label. */
function brokenDiagnosticLabel(status: number): string {
    if (status === 403 || status === 429) return "Scan Blocked (Potential WAF)";
    if (status === 401) return "Authentication Required";
    if (status === 404) return "Page Not Found";
    if (status === 0) return "Network Error";
    if (status >= 400) return `Link Error (HTTP ${status})`;
    return "Link Broken";
}

/**
 * Broken-link badges.
 * Displays the link's original page text (e.g. "Terms of Use") rather than
 * the internal keyword, and shows a specific diagnostic label derived from the
 * HTTP status code instead of the generic "Link Broken".  The raw URL is
 * intentionally omitted — it adds visual noise without actionable value.
 */
export function BrokenKeywordChips({
                                       keywords,
                                       brokenLinksMap,
                                   }: {
    keywords: string[];
    brokenLinksMap: Map<string, { linkText: string; status: number }>;
}) {
    return (
        <div className="flex flex-wrap gap-2">
            {keywords.map((kw) => {
                const info = brokenLinksMap.get(kw.toLowerCase());
                const displayLabel = info?.linkText || kw;
                const subLabel = brokenDiagnosticLabel(info?.status ?? 0);
                return (
                    <span key={kw} className="inline-flex flex-col items-start gap-0.5">
                        <span
                            className="inline-flex items-center gap-1.5 rounded-full bg-amber-950/60 px-2.5 py-1 text-xs font-medium text-amber-300 ring-1 ring-amber-700/40">
                            <AlertTriangle className="h-3 w-3 shrink-0 text-amber-400"/>
                            {displayLabel}
                        </span>
                        <span className="ml-1 text-[10px] font-medium text-amber-600/80">{subLabel}</span>
                    </span>
                );
            })}
        </div>
    );
}

// ── FoundKeywordChips ─────────────────────────────────────────────────────────

/** Found-keyword badge with CheckCircle, score, and collapsible Evidence panel. */
export function FoundKeywordChips({
                                      keywords,
                                      evidenceMap,
                                      aiEvidenceMap,
                                  }: {
    keywords: string[];
    evidenceMap: Map<string, KeywordEvidence>;
    aiEvidenceMap?: Map<string, AIEvidence>;
}) {
    const [open, setOpen] = useState<string | null>(null);

    return (
        <div className="space-y-2">
            <div className="flex flex-wrap gap-2">
                {keywords.map((kw) => {
                    const ev = evidenceMap.get(kw.toLowerCase());
                    const aiEv = aiEvidenceMap?.get(kw.toLowerCase());
                    const hasEvidence = ev || aiEv;
                    const score = ev?.score ?? null;
                    const isOpen = open === kw;
                    return (
                        <span key={kw} className="inline-flex items-center gap-1">
              {/* Main badge */}
                            <span
                                className="inline-flex items-center gap-1.5 rounded-full bg-green-950/60 px-2.5 py-1 text-xs font-medium text-green-300 ring-1 ring-green-700/40">
                <CheckCircle2 className="h-3 w-3 shrink-0 text-green-400"/>
                                {kw}
                                {score !== null && (
                                    <span
                                        className="rounded-full bg-green-800/50 px-1.5 py-0.5 text-[10px] text-green-400">
                    {score}
                  </span>
                                )}
              </span>
                            {/* Eye toggle — when rule-based or AI evidence exists */}
                            {hasEvidence && (
                                <button
                                    onClick={() => setOpen(isOpen ? null : kw)}
                                    title={isOpen ? "Hide evidence" : "Show evidence"}
                                    className={`rounded-full p-0.5 transition-colors focus:outline-none ${
                                        isOpen
                                            ? (aiEv && !ev ? "text-violet-400" : "text-sky-400")
                                            : (aiEv && !ev ? "text-slate-500 hover:text-violet-400" : "text-slate-500 hover:text-sky-400")
                                    }`}
                                >
                                    <Eye className="h-3.5 w-3.5"/>
                                </button>
                            )}
            </span>
                    );
                })}
            </div>
            {open && (() => {
                const ev = evidenceMap.get(open.toLowerCase());
                const aiEv = aiEvidenceMap?.get(open.toLowerCase());
                if (ev) return <EvidencePanel ev={ev}/>;
                if (aiEv) return <AIEvidencePanel ev={aiEv}/>;
                return null;
            })()}
        </div>
    );
}

// ── OptionalKeywordChips ──────────────────────────────────────────────────────

/** Optional-tier badges: sky InfoCircle + "Optional" sub-label. */
function OptionalKeywordChips({keywords, evidenceMap}: {
    keywords: string[];
    evidenceMap: Map<string, KeywordEvidence>;
}) {
    const [open, setOpen] = useState<string | null>(null);
    return (
        <div className="space-y-2">
            <div className="flex flex-wrap gap-2">
                {keywords.map((kw) => {
                    const ev = evidenceMap.get(kw.toLowerCase());
                    const isOpen = open === kw;
                    return (
                        <span key={kw} className="inline-flex items-center gap-1">
                            <span
                                className="inline-flex items-center gap-1.5 rounded-full bg-sky-950/60 px-2.5 py-1 text-xs font-medium text-sky-300 ring-1 ring-sky-700/40">
                                <Info className="h-3 w-3 shrink-0 text-sky-400"/>
                                {kw}
                                {ev?.score != null && (
                                    <span className="rounded-full bg-sky-800/50 px-1.5 py-0.5 text-[10px] text-sky-400">
                                        {ev.score}
                                    </span>
                                )}
                            </span>
                            {ev && (
                                <button
                                    onClick={() => setOpen(isOpen ? null : kw)}
                                    title={isOpen ? "Hide evidence" : "Show evidence snippet"}
                                    className={`rounded-full p-0.5 transition-colors focus:outline-none ${isOpen ? "text-sky-400" : "text-slate-500 hover:text-sky-400"}`}
                                >
                                    <Eye className="h-3.5 w-3.5"/>
                                </button>
                            )}
                        </span>
                    );
                })}
            </div>
            {open && evidenceMap.get(open.toLowerCase()) && (
                <EvidencePanel ev={evidenceMap.get(open.toLowerCase())!}/>
            )}
        </div>
    );
}

// ── ImprovementTips ───────────────────────────────────────────────────────────

/**
 * Renders missing mandatory keywords as informational improvement tips —
 * NOT as red error chips. Used when found_mandatory_native.length > 0 (threshold
 * met) but some mandatory keywords were still not found on the page.
 */
function ImprovementTips({keywords}: { keywords: string[] }) {
    return (
        <div className="flex flex-wrap gap-2">
            {keywords.map((kw) => (
                <span key={kw} className="inline-flex flex-col items-start gap-0.5">
                    <span
                        className="inline-flex items-center gap-1.5 rounded-full bg-slate-200 dark:bg-slate-800/80 px-2.5 py-1 text-xs font-medium text-slate-600 dark:text-slate-300 ring-1 ring-slate-300/40 dark:ring-slate-600/40">
                        <Info className="h-3 w-3 shrink-0 text-slate-400"/>
                        {kw}
                    </span>
                    <span className="ml-1 text-[10px] font-medium text-slate-500">Improvement tip</span>
                </span>
            ))}
        </div>
    );
}

// ── EnglishOptionalChips ──────────────────────────────────────────────────────

/**
 * Renders unfound English fallback keywords as neutral "Optional" chips when
 * the ENGLISH_FALLBACK tier is already satisfied (at least one synonym found).
 * Keeps them visible for audit transparency without implying a compliance error.
 */
function EnglishOptionalChips({keywords}: { keywords: string[] }) {
    return (
        <div className="flex flex-wrap gap-2">
            {keywords.map((kw) => (
                <span key={kw} className="inline-flex flex-col items-start gap-0.5">
                    <span
                        className="inline-flex items-center gap-1.5 rounded-full bg-slate-200 dark:bg-slate-800/80 px-2.5 py-1 text-xs font-medium text-slate-600 dark:text-slate-300 ring-1 ring-slate-300/40 dark:ring-slate-600/40">
                        <Info className="h-3 w-3 shrink-0 text-slate-400"/>
                        {kw}
                    </span>
                    <span className="ml-1 text-[10px] font-medium text-slate-500">Optional</span>
                </span>
            ))}
        </div>
    );
}

// ── ScreenshotEvidence ────────────────────────────────────────────────────────

/**
 * Loads a screenshot via authenticated fetch (apiClient) and displays it as a
 * blob URL.  Direct <img src> cannot send the JWT Authorization header, so we
 * fetch the bytes ourselves and create an object URL.
 */
function ScreenshotEvidence({fileId}: { fileId: string }) {
    const [blobUrl, setBlobUrl] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(false);

    // Reset state when fileId changes (navigating between scan history entries)
    useEffect(() => {
        setBlobUrl(prev => {
            if (prev) URL.revokeObjectURL(prev);
            return null;
        });
        setError(false);
        setLoading(false);
    }, [fileId]);

    const loadScreenshot = () => {
        if (blobUrl || loading) return;
        setLoading(true);
        apiClient(`/api/v1/files/${fileId}`)
            .then(res => {
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                return res.blob();
            })
            .then(blob => setBlobUrl(URL.createObjectURL(blob)))
            .catch(() => setError(true))
            .finally(() => setLoading(false));
    };

    // Clean up blob URL on unmount
    useEffect(() => {
        return () => {
            if (blobUrl) URL.revokeObjectURL(blobUrl);
        };
    }, [blobUrl]);

    return (
        <details
            className="rounded-xl bg-slate-100 dark:bg-slate-900/60 ring-1 ring-slate-200 dark:ring-slate-700/30"
            onToggle={(e) => {
                if ((e.target as HTMLDetailsElement).open) loadScreenshot();
            }}
        >
            <summary
                className="cursor-pointer px-3 py-2.5 text-[11px] font-medium text-slate-400 hover:text-slate-700 dark:hover:text-slate-300">
                <Eye className="inline h-3 w-3 mr-1"/>Visual Evidence
            </summary>
            <div className="border-t border-slate-200 dark:border-slate-800 p-3">
                {loading && (
                    <div className="flex items-center justify-center py-6">
                        <div
                            className="h-5 w-5 animate-spin rounded-full border-2 border-slate-400 border-t-transparent"/>
                    </div>
                )}
                {error && (
                    <p className="text-xs text-red-400 text-center py-4">Failed to load screenshot.</p>
                )}
                {blobUrl && (
                    <img
                        src={blobUrl}
                        alt="Cookie consent banner screenshot"
                        className="rounded-lg border border-slate-200 dark:border-slate-700 w-full"
                    />
                )}
            </div>
        </details>
    );
}

// ── BannerDetails ─────────────────────────────────────────────────────────────

/**
 * Renders the cookie consent banner detection result produced by BannerScanner.
 * Shows CMP name, accept/reject button presence, and any compliance issues.
 */
export function BannerDetails({result}: { result: Record<string, unknown> }) {
    if ((result.status as string) === "FAILED" || result.error != null) {
        const pageStatus = result.page_status ? ` (HTTP ${result.page_status})` : "";
        return (
            <ScanFailedBanner
                error={String(result.error ?? "Unknown error") + pageStatus}
            />
        );
    }

    const bannerDetected = result.banner_detected === "true" || result.banner_detected === true;
    const compliant = result.compliant === "true" || result.compliant === true;
    const cmpDetected = result.cmp_detected as string | undefined;
    const bannerSelector = result.banner_selector as string | undefined;
    const acceptFound = result.accept_button_found === "true" || result.accept_button_found === true;
    const acceptLabel = result.accept_button_label as string | undefined;
    const rejectFound = result.reject_button_found === "true" || result.reject_button_found === true;
    const rejectLabel = result.reject_button_label as string | undefined;
    const prefsFound = result.preferences_button_found === "true" || result.preferences_button_found === true;
    const scannedVia = result.scanned_via as string | undefined;
    const aiReasoning = result.ai_reasoning as string | undefined;

    let complianceIssues: string[] = [];
    const rawIssues = result.compliance_issues;
    if (Array.isArray(rawIssues)) {
        complianceIssues = rawIssues as string[];
    } else if (typeof rawIssues === "string" && rawIssues !== "[]") {
        try {
            complianceIssues = JSON.parse(rawIssues);
        } catch { /* ignore */
        }
    }

    if (!bannerDetected && complianceIssues.length === 0 && result.scanned_via === "UNAVAILABLE") {
        return (
            <div className="mt-3 flex items-start gap-2 rounded-xl border border-border bg-secondary/30 px-4 py-3">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-400"/>
                <p className="text-xs md:text-sm text-muted-foreground">
                    Banner detection requires a headless browser. Check worker configuration.
                </p>
            </div>
        );
    }

    return (
        <div className="mt-4 space-y-4">
            {/* ── Banner presence ───────────────────────────────────── */}
            <div
                className={`rounded-xl p-3 md:p-4 ring-1 ${bannerDetected ? "bg-slate-100 dark:bg-slate-900/60 ring-green-300/40 dark:ring-green-800/30" : "bg-slate-100 dark:bg-slate-900/60 ring-red-300/40 dark:ring-red-800/30"}`}>
                <div className="flex items-center gap-2">
                    {bannerDetected
                        ? <ShieldCheck className="h-4 w-4 shrink-0 text-green-400"/>
                        : <ShieldX className="h-4 w-4 shrink-0 text-red-400"/>
                    }
                    <p className={`text-xs font-semibold ${bannerDetected ? "text-green-400" : "text-red-400"}`}>
                        {bannerDetected ? "Consent Banner Detected" : "No Consent Banner Found"}
                    </p>
                    {cmpDetected && cmpDetected !== "Generic" && cmpDetected !== "" && (
                        <span
                            className="ml-auto rounded-full bg-slate-200 dark:bg-slate-700 px-2 py-0.5 text-[10px] font-medium text-slate-600 dark:text-slate-300">
                            {cmpDetected}
                        </span>
                    )}
                    {cmpDetected === "Generic" && (
                        <span
                            className="ml-auto rounded-full bg-slate-700 px-2 py-0.5 text-[10px] font-medium text-slate-400">
                            Generic CMP
                        </span>
                    )}
                </div>
                {bannerSelector && (
                    <p className="mt-1.5 font-mono text-[10px] text-slate-500 truncate">{bannerSelector}</p>
                )}
                {!bannerDetected && <RemediationTip guidanceKey="COOKIE_BANNER_MISSING"/>}
            </div>

            {/* ── Button analysis ───────────────────────────────────── */}
            {bannerDetected && (
                <div
                    className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-slate-200 dark:ring-slate-700/30">
                    <p className="mb-3 text-xs font-semibold text-slate-700 dark:text-slate-300">Consent Controls</p>
                    <div className="space-y-2">
                        {/* Accept */}
                        <div className="flex items-center gap-2">
                            {acceptFound
                                ? <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-green-400"/>
                                : <XCircle className="h-3.5 w-3.5 shrink-0 text-slate-500"/>
                            }
                            <span className={`text-xs ${acceptFound ? "text-green-300" : "text-slate-500"}`}>
                                Accept button
                            </span>
                            {acceptFound && acceptLabel && (
                                <span
                                    className="ml-auto truncate rounded bg-slate-200 dark:bg-slate-800 px-1.5 py-0.5 font-mono text-[10px] text-slate-500 dark:text-slate-400 max-w-[160px]">
                                    "{acceptLabel}"
                                </span>
                            )}
                        </div>
                        {/* Reject */}
                        <div className="flex items-center gap-2">
                            {rejectFound
                                ? <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-green-400"/>
                                : <XCircle className="h-3.5 w-3.5 shrink-0 text-red-400"/>
                            }
                            <span className={`text-xs ${rejectFound ? "text-green-300" : "text-red-400"}`}>
                                Reject / Decline button
                            </span>
                            {rejectFound && rejectLabel && (
                                <span
                                    className="ml-auto truncate rounded bg-slate-200 dark:bg-slate-800 px-1.5 py-0.5 font-mono text-[10px] text-slate-500 dark:text-slate-400 max-w-[160px]">
                                    "{rejectLabel}"
                                </span>
                            )}
                            {!rejectFound && (
                                <span className="ml-auto text-[10px] text-red-500/80">Required by GDPR</span>
                            )}
                        </div>
                        {/* Preferences */}
                        <div className="flex items-center gap-2">
                            {prefsFound
                                ? <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-sky-400"/>
                                : <Info className="h-3.5 w-3.5 shrink-0 text-slate-600"/>
                            }
                            <span className={`text-xs ${prefsFound ? "text-sky-300" : "text-slate-600"}`}>
                                Preferences / Settings
                            </span>
                            {prefsFound && (
                                <span className="ml-auto text-[10px] text-sky-400/60">Granular consent</span>
                            )}
                        </div>
                    </div>
                </div>
            )}

            {/* ── Compliance issues ─────────────────────────────────── */}
            {complianceIssues.length > 0 && (
                <div
                    className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-red-300/40 dark:ring-red-800/30">
                    <p className="mb-2.5 flex items-center gap-1.5 text-xs font-semibold text-red-400">
                        <AlertTriangle className="h-3.5 w-3.5"/>
                        Compliance Issues
                    </p>
                    <ul className="space-y-1">
                        {complianceIssues.map((issue, i) => (
                            <li key={i} className="flex items-start gap-1.5 text-xs text-red-300/80">
                                <XCircle className="mt-0.5 h-3 w-3 shrink-0 text-red-500"/>
                                {issue}
                            </li>
                        ))}
                    </ul>
                    {complianceIssues.some(i => i.toLowerCase().includes("reject") || i.toLowerCase().includes("decline")) && (
                        <RemediationTip guidanceKey="COOKIE_BANNER_NO_REJECT"/>
                    )}
                </div>
            )}

            {/* ── AI reasoning ──────────────────────────────────────── */}
            {scannedVia === "AI_ASSISTED" && aiReasoning && (
                <div
                    className="relative overflow-hidden rounded-xl bg-gradient-to-br from-amber-950/40 via-slate-900/60 to-orange-950/30 p-3 md:p-4 ring-1 ring-amber-500/20">
                    <div className="absolute -right-3 -top-3 h-16 w-16 rounded-full bg-amber-500/5 blur-xl"/>
                    <div className="relative">
                        <div className="mb-2.5 flex items-center gap-2">
                            <div
                                className="flex h-6 w-6 items-center justify-center rounded-md bg-amber-500/15 ring-1 ring-amber-500/25">
                                <Sparkles className="h-3.5 w-3.5 text-amber-400"/>
                            </div>
                            <p className="text-xs font-semibold text-amber-400">AI Analysis</p>
                        </div>
                        <p className="text-xs text-slate-600 dark:text-slate-300 leading-relaxed">
                            {aiReasoning}
                        </p>
                    </div>
                </div>
            )}

            {/* ── Screenshot evidence ───────────────────────────────── */}
            {!!result.screenshot_file_id && (
                <ScreenshotEvidence fileId={result.screenshot_file_id as string}/>
            )}

            {/* ── All passing ───────────────────────────────────────── */}
            {compliant && complianceIssues.length === 0 && (
                <div
                    className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-green-300/40 dark:ring-green-800/30">
                    <p className="flex items-center gap-1.5 text-xs font-semibold text-green-400">
                        <CheckCircle2 className="h-3.5 w-3.5"/>
                        Compliant — banner present with accept and reject options
                    </p>
                </div>
            )}
        </div>
    );
}

// ── ComplianceDetails ─────────────────────────────────────────────────────────

export function ComplianceDetails({result, subType}: { result: Record<string, unknown>; subType?: MonitorSubType }) {
    if ((result.status as string) === "FAILED" || result.error != null) {
        const pageStatus = result.page_status ? ` (HTTP ${result.page_status})` : "";
        return (
            <ScanFailedBanner
                error={String(result.error ?? "Unknown error") + pageStatus}
            />
        );
    }

    // Tiered matrix v2 fields (present in scans produced after the regional compliance matrix).
    // For older scan rows that lack these fields, fall back to found_keywords / missing_keywords.
    const hasTieredPayload = "found_mandatory_native" in result;
    const foundMandatoryNative = parseArr(result.found_mandatory_native);
    const foundOptionalNative = parseArr(result.found_optional_native);
    // Legacy path
    const found = hasTieredPayload ? foundMandatoryNative : parseArr(result.found_keywords);
    const missing = parseArr(result.missing_keywords); // = missingMandatory in v2 scans
    const broken = parseArr(result.broken_keywords);
    const contentChanged = result.content_changed as boolean | undefined;

    // AI content analysis fields (targeted mode, when AI analyzed page quality)
    const aiContentScore = result.ai_content_score != null ? Number(result.ai_content_score) : null;
    const aiContentReasoning = result.ai_content_reasoning as string | undefined;
    const aiMissingElements = parseArr(result.ai_missing_elements);
    const aiContentIssues = parseArr(result.ai_content_issues);
    const hasAIContent = aiContentScore !== null;

    // Targeted-mode accessibility fields (only present when mode === "targeted")
    const isTargetedMode = result.mode === "targeted";
    const accessibilityStatus = result.is_accessible_from_home as string | undefined;
    const accessibleViaHref = result.accessible_via_href as string | undefined;
    const homepageLinksChecked =
        result.homepage_links_checked != null ? Number(result.homepage_links_checked) : undefined;

    const langInconsistency =
        result.language_inconsistency === true || result.language_inconsistency === "true";
    const acceptedViaLangPref =
        result.accepted_via_language_preference === true || result.accepted_via_language_preference === "true";
    const englishFoundKws = parseArr(result.english_found_keywords);
    const englishMissingKws = parseArr(result.english_missing_keywords);
    const englishUrl = result.english_url as string | undefined;
    // English fallback uses OR-logic: finding any single keyword satisfies the tier.
    // When satisfied, unfound synonyms are informational — not errors.
    const englishFallbackSatisfied = englishFoundKws.length > 0;
    // threshold met = at least one mandatory native keyword was found
    const thresholdMet = hasTieredPayload ? foundMandatoryNative.length > 0 : found.length > 0;

    // Build a lowercase-keyed map for O(1) evidence lookup per keyword chip
    const evidenceMap = useMemo(() => {
        const raw = result.keyword_evidence;
        const list: KeywordEvidence[] = Array.isArray(raw) ? (raw as KeywordEvidence[]) : [];
        return new Map(list.map((ev) => [ev.keyword.toLowerCase(), ev]));
    }, [result.keyword_evidence]);

    const englishEvidenceMap = useMemo(() => {
        const raw = result.english_evidence;
        const list: KeywordEvidence[] = Array.isArray(raw) ? (raw as KeywordEvidence[]) : [];
        return new Map(list.map((ev) => [ev.keyword.toLowerCase(), ev]));
    }, [result.english_evidence]);

    // Build AI evidence map for keywords verified/discovered by AI that lack rule-based evidence
    const aiEvidenceMap = useMemo(() => {
        const map = new Map<string, AIEvidence>();
        const aiTerm = result.ai_verified_term as string | undefined;
        if (aiTerm) {
            // Determine source from scanned_via tag
            const scannedVia = (result.scanned_via as string) ?? "";
            let source = "AI_SEMANTIC_VERIFIED";
            if (scannedVia.includes("AI_SEMANTIC_CACHED")) source = "AI_SEMANTIC_CACHED";
            else if (scannedVia.includes("AI_DISCOVERY")) source = "AI_DISCOVERY";
            else if (scannedVia.includes("AI_SEMANTIC_VERIFIED")) source = "AI_SEMANTIC_VERIFIED";

            map.set(aiTerm.toLowerCase(), {
                keyword: aiTerm,
                linkHref: (result.ai_verified_link_href as string) ?? "",
                reasoning: (result.ai_verification_reasoning as string) ?? "",
                source,
                confidence: (result.path_confidence as string) ?? undefined,
            });
        }
        return map;
    }, [result.ai_verified_term, result.ai_verified_link_href, result.ai_verification_reasoning, result.scanned_via, result.path_confidence]);

    // Build keyword → { linkText, status } map for broken links.
    // found_links is a JSON array of FoundLink objects:
    //   { keyword, text, href, status, broken }
    // We use `text` (the actual anchor text from the page) as the display label
    // and `status` to select the specific diagnostic sub-label.
    const brokenLinksMap = useMemo(() => {
        let raw: unknown = result.found_links;
        if (typeof raw === "string") {
            try {
                raw = JSON.parse(raw);
            } catch {
                raw = [];
            }
        }
        const list = Array.isArray(raw)
            ? (raw as { keyword?: string; text?: string; href?: string; status?: number; broken?: boolean }[])
            : [];
        const map = new Map<string, { linkText: string; status: number }>();
        for (const link of list) {
            if (link.broken && link.keyword) {
                map.set(link.keyword.toLowerCase(), {
                    linkText: link.text ?? "",
                    status: link.status ?? 0,
                });
            }
        }
        return map;
    }, [result.found_links]);

    const hasAnyData =
        found.length > 0 ||
        foundOptionalNative.length > 0 ||
        missing.length > 0 ||
        broken.length > 0 ||
        (isTargetedMode && accessibilityStatus != null && accessibilityStatus !== "");
    if (!hasAnyData) {
        return (
            <div className="mt-3 flex items-start gap-2 rounded-xl border border-border bg-secondary/30 px-4 py-3">
                <Clock className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground"/>
                <p className="text-xs md:text-sm text-[--muted-foreground]">
                    No data available for this scan — the result may still be processing
                    or the page could not be reached.
                </p>
            </div>
        );
    }

    return (
        <div className="mt-4 space-y-4">
            {/* ── Homepage Accessibility (targeted mode only) ───────── */}
            {isTargetedMode && accessibilityStatus != null && accessibilityStatus !== "" && (() => {
                if (accessibilityStatus === "true") {
                    let hrefDisplay = accessibleViaHref ?? "";
                    try {
                        if (accessibleViaHref) hrefDisplay = new URL(accessibleViaHref).pathname;
                    } catch { /* keep raw value */
                    }
                    return (
                        <div
                            className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-green-300/40 dark:ring-green-800/30">
                            <p className="flex items-center gap-1.5 text-xs font-semibold text-green-400">
                                <Link2 className="h-3.5 w-3.5 shrink-0"/>
                                Linked from Homepage
                            </p>
                            {hrefDisplay && (
                                <p className="mt-1.5 flex items-center gap-1.5 truncate">
                                    <ExternalLink className="h-3 w-3 shrink-0 text-green-600/60"/>
                                    <span className="truncate font-mono text-[11px] text-green-400/70">
                                        {hrefDisplay}
                                    </span>
                                </p>
                            )}
                        </div>
                    );
                }
                if (accessibilityStatus === "false") {
                    return (
                        <div
                            className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-amber-300/40 dark:ring-amber-800/30">
                            <p className="flex items-center gap-1.5 text-xs font-semibold text-amber-400">
                                <AlertTriangle className="h-3.5 w-3.5 shrink-0"/>
                                Not Linked from Homepage
                            </p>
                            {homepageLinksChecked != null && (
                                <p className="mt-1 text-[11px] text-amber-400/60">
                                    {homepageLinksChecked} link{homepageLinksChecked !== 1 ? "s" : ""} checked on
                                    homepage
                                </p>
                            )}
                            <RemediationTip guidanceKey="NOT_LINKED_FROM_HOMEPAGE"/>
                        </div>
                    );
                }
                // "unknown" — homepage fetch failed
                return (
                    <div
                        className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-slate-200 dark:ring-slate-700/30">
                        <p className="flex items-center gap-1.5 text-xs font-semibold text-slate-400">
                            <Info className="h-3.5 w-3.5 shrink-0"/>
                            Homepage Accessibility Unavailable
                        </p>
                        <p className="mt-1 text-[11px] text-slate-500">
                            Homepage could not be fetched during this scan.
                        </p>
                    </div>
                );
            })()}

            {/* ── Found (mandatory native) ───────────────────────── */}
            {found.length > 0 && (
                <div
                    className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-green-300/40 dark:ring-green-800/30">
                    <p className="mb-2.5 flex items-center gap-1.5 text-xs font-semibold text-green-400">
                        <CheckCircle2 className="h-3.5 w-3.5"/>
                        Found ({found.length})
                    </p>
                    <FoundKeywordChips keywords={found} evidenceMap={evidenceMap} aiEvidenceMap={aiEvidenceMap}/>
                </div>
            )}

            {/* ── Optional native (bonus tier) ───────────────────── */}
            {hasTieredPayload && foundOptionalNative.length > 0 && (
                <div
                    className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-sky-300/40 dark:ring-sky-800/30">
                    <p className="mb-2.5 flex items-center gap-1.5 text-xs font-semibold text-sky-400">
                        <Info className="h-3.5 w-3.5"/>
                        Additional ({foundOptionalNative.length})
                    </p>
                    <OptionalKeywordChips keywords={foundOptionalNative} evidenceMap={evidenceMap}/>
                </div>
            )}

            {/* ── Missing / Improvement tips ─────────────────────── */}
            {missing.length > 0 && (
                thresholdMet ? (
                    // Threshold met → show missing as soft improvement tips, not red errors
                    <div
                        className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-slate-200 dark:ring-slate-700/30">
                        <p className="mb-2.5 flex items-center gap-1.5 text-xs font-semibold text-slate-400">
                            <Info className="h-3.5 w-3.5"/>
                            Improvement Tips ({missing.length})
                        </p>
                        <ImprovementTips keywords={missing}/>
                    </div>
                ) : (
                    // Threshold NOT met → show missing as red error chips
                    <div
                        className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-red-300/40 dark:ring-red-800/30">
                        <p className="mb-2.5 flex items-center gap-1.5 text-xs font-semibold text-red-400">
                            <XCircle className="h-3.5 w-3.5"/>
                            Missing ({missing.length})
                        </p>
                        <MissingKeywordChips keywords={missing}/>
                        {subType && <RemediationTip guidanceKey={complianceGuidanceKey(subType)}/>}
                    </div>
                )
            )}

            {broken.length > 0 && (
                <div
                    className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-amber-300/40 dark:ring-amber-800/30">
                    <p className="mb-2.5 flex items-center gap-1.5 text-xs font-semibold text-amber-400">
                        <AlertTriangle className="h-3.5 w-3.5"/>
                        Broken Links ({broken.length})
                    </p>
                    <BrokenKeywordChips keywords={broken} brokenLinksMap={brokenLinksMap}/>
                    <RemediationTip guidanceKey="BROKEN_LINKS"/>
                </div>
            )}
            {langInconsistency && (
                <div
                    className={`rounded-xl p-3 md:p-4 ring-1 ${
                        acceptedViaLangPref
                            ? "bg-green-50 dark:bg-green-950/20 ring-green-200 dark:ring-green-800/30"
                            : "bg-amber-50 dark:bg-amber-950/20 ring-amber-200 dark:ring-amber-800/30"
                    }`}>
                    <div className="mb-3 flex items-center gap-2">
                        <Globe
                            className={`h-4 w-4 shrink-0 ${acceptedViaLangPref ? "text-green-600 dark:text-green-400" : "text-amber-600 dark:text-amber-400"}`}/>
                        <p className={`text-xs md:text-sm font-semibold ${acceptedViaLangPref ? "text-green-700 dark:text-green-400" : "text-amber-700 dark:text-amber-400"}`}>
                            {acceptedViaLangPref
                                ? "English Accepted — Passed via Language Preference"
                                : "Language Inconsistency Detected"}
                        </p>
                    </div>
                    {acceptedViaLangPref && (
                        <p className="mb-3 text-xs text-green-700/80 dark:text-green-300/70">
                            This monitor passed because English keywords were found and your language
                            settings accept English as a valid result.
                        </p>
                    )}
                    <p className={`mb-3 text-xs ${acceptedViaLangPref ? "text-green-700/60 dark:text-green-300/50" : "text-amber-700/80 dark:text-amber-300/70"}`}>
                        Legal pages are only available in English, not in the required native language.
                    </p>
                    <RemediationTip guidanceKey="LANGUAGE_INCONSISTENCY"/>
                    <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
                        {/* Native side — missing */}
                        <div
                            className="rounded-lg bg-slate-100 dark:bg-slate-900/60 p-3 ring-1 ring-red-300/40 dark:ring-red-800/30">
                            <p className="mb-2 flex items-center gap-1 text-[11px] font-semibold uppercase tracking-wide text-red-400">
                                <XCircle className="h-3 w-3"/> Native — Missing
                            </p>
                            {missing.length > 0 ? (
                                <MissingKeywordChips keywords={missing}/>
                            ) : (
                                <p className="text-[11px] text-muted-foreground">No native keywords found</p>
                            )}
                        </div>
                        {/* English side — found */}
                        <div
                            className="rounded-lg bg-slate-100 dark:bg-slate-900/60 p-3 ring-1 ring-green-300/40 dark:ring-green-800/30">
                        <p className="mb-2 flex items-center gap-1 text-[11px] font-semibold uppercase tracking-wide text-green-400">
                                <CheckCircle2 className="h-3 w-3"/> English — Found
                            </p>
                            {englishFoundKws.length > 0 ? (
                                <FoundKeywordChips keywords={englishFoundKws} evidenceMap={englishEvidenceMap}/>
                            ) : (
                                <p className="text-[11px] text-muted-foreground">No English keywords found</p>
                            )}
                        </div>
                    </div>
                    {englishUrl && (
                        <div className="mt-2 flex items-center gap-1.5 truncate">
                            <ExternalLink className="h-3 w-3 shrink-0 text-amber-500/60"/>
                            <span className="truncate font-mono text-[11px] text-amber-400/60">{englishUrl}</span>
                        </div>
                    )}
                    {englishMissingKws.length > 0 && (
                        <div className="mt-2">
                            <p className="mb-1 text-[11px] text-amber-400/60">
                                {englishFallbackSatisfied
                                    ? "Other English synonyms (not required):"
                                    : "Also missing in English:"}
                            </p>
                            {englishFallbackSatisfied
                                ? <EnglishOptionalChips keywords={englishMissingKws}/>
                                : <MissingKeywordChips keywords={englishMissingKws}/>}
                        </div>
                    )}
                </div>
            )}
            {/* ── AI Content Analysis ────────────────────────────────── */}
            {hasAIContent && (
                <div
                    className="relative overflow-hidden rounded-xl bg-gradient-to-br from-amber-950/40 via-slate-900/60 to-orange-950/30 p-3 md:p-4 ring-1 ring-amber-500/20">
                    <div className="absolute -right-3 -top-3 h-16 w-16 rounded-full bg-amber-500/5 blur-xl"/>
                    <div className="relative space-y-3">
                        {/* Header with score */}
                        <div className="flex items-center justify-between">
                            <div className="flex items-center gap-2">
                                <div
                                    className="flex h-6 w-6 items-center justify-center rounded-md bg-amber-500/15 ring-1 ring-amber-500/25">
                                    <Sparkles className="h-3.5 w-3.5 text-amber-400"/>
                                </div>
                                <p className="text-xs font-semibold text-amber-400">AI Content Analysis</p>
                            </div>
                            <div
                                className={`flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-bold ring-1 ${
                                    aiContentScore! >= 80
                                        ? "bg-green-500/10 text-green-400 ring-green-500/25"
                                        : aiContentScore! >= 60
                                            ? "bg-sky-500/10 text-sky-400 ring-sky-500/25"
                                            : aiContentScore! >= 40
                                                ? "bg-amber-500/10 text-amber-400 ring-amber-500/25"
                                                : "bg-red-500/10 text-red-400 ring-red-500/25"
                                }`}>
                                {aiContentScore}/100
                            </div>
                        </div>

                        {/* Score bar */}
                        <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-800">
                            <div
                                className={`h-full rounded-full transition-all ${
                                    aiContentScore! >= 80
                                        ? "bg-green-500"
                                        : aiContentScore! >= 60
                                            ? "bg-sky-500"
                                            : aiContentScore! >= 40
                                                ? "bg-amber-500"
                                                : "bg-red-500"
                                }`}
                                style={{width: `${Math.min(100, Math.max(0, aiContentScore!))}%`}}
                            />
                        </div>

                        {/* Reasoning */}
                        {aiContentReasoning && (
                            <p className="text-xs text-slate-600 dark:text-slate-300 leading-relaxed">{aiContentReasoning}</p>
                        )}

                        {/* Missing elements */}
                        {aiMissingElements.length > 0 && (
                            <div>
                                <p className="mb-1.5 text-[11px] font-semibold text-red-400/80">
                                    Missing Required Elements
                                </p>
                                <ul className="space-y-1">
                                    {aiMissingElements.map((el, i) => (
                                        <li key={i}
                                            className="flex items-start gap-1.5 text-[11px] text-red-300/70">
                                            <XCircle className="mt-0.5 h-3 w-3 shrink-0 text-red-500/60"/>
                                            {el}
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        )}

                        {/* Issues */}
                        {aiContentIssues.length > 0 && (
                            <div>
                                <p className="mb-1.5 text-[11px] font-semibold text-amber-400/80">
                                    Issues Found
                                </p>
                                <ul className="space-y-1">
                                    {aiContentIssues.map((issue, i) => (
                                        <li key={i}
                                            className="flex items-start gap-1.5 text-[11px] text-amber-300/70">
                                            <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0 text-amber-500/60"/>
                                            {issue}
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        )}
                    </div>
                </div>
            )}

            {contentChanged != null && (
                <div className="divide-y divide-border rounded-xl bg-secondary/30 px-4 ring-1 ring-border">
                    <div className="flex items-start justify-between gap-3 py-1.5 md:py-2">
            <span className="shrink-0 text-xs md:text-sm text-[--muted-foreground]">
              Content Changed
            </span>
                        <span className="text-right text-xs md:text-sm text-[--foreground] break-all">
              <span
                  className={
                      contentChanged
                          ? "text-yellow-600 dark:text-yellow-400"
                          : "text-muted-foreground"
                  }
              >
                {contentChanged ? "Yes — page was modified" : "No"}
              </span>
            </span>
                    </div>
                </div>
            )}
        </div>
    );
}
