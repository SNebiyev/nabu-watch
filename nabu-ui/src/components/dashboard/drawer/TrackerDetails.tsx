import {CheckCircle2, ExternalLink, Globe, Radar, XCircle,} from "lucide-react";
import {parseArr} from "./utils";
import {RemediationTip} from "./ComplianceDetails";
import {ScanFailedBanner} from "./SslDetails";

// ── Types ────────────────────────────────────────────────────────────────────

interface DetectedTracker {
    name: string;
    domain: string;
    category: string;
    request_count: number;
    sample_urls?: string[];
}

const CATEGORY_LABELS: Record<string, { label: string; color: string }> = {
    analytics: {label: "Analytics", color: "bg-blue-500/15 text-blue-400 ring-blue-500/25"},
    advertising: {label: "Advertising", color: "bg-red-500/15 text-red-400 ring-red-500/25"},
    social: {label: "Social", color: "bg-violet-500/15 text-violet-400 ring-violet-500/25"},
    tag_manager: {label: "Tag Manager", color: "bg-sky-500/15 text-sky-400 ring-sky-500/25"},
    heatmap: {label: "Heatmap", color: "bg-amber-500/15 text-amber-400 ring-amber-500/25"},
    fingerprinting: {label: "Fingerprinting", color: "bg-rose-500/15 text-rose-400 ring-rose-500/25"},
    customer_data_platform: {
        label: "Customer Data Platform",
        color: "bg-emerald-500/15 text-emerald-400 ring-emerald-500/25"
    },
};

function CategoryBadge({category}: { category: string }) {
    const cat = CATEGORY_LABELS[category] ?? {
        label: category,
        color: "bg-slate-500/15 text-slate-400 ring-slate-500/25"
    };
    return (
        <span className={`inline-flex rounded-full px-2 py-0.5 text-[10px] font-medium ring-1 ${cat.color}`}>
            {cat.label}
        </span>
    );
}

// ── TrackerDetails ───────────────────────────────────────────────────────────

export function TrackerDetails({result}: { result: Record<string, unknown> }) {
    if ((result.status as string) === "FAILED" || result.error != null) {
        return <ScanFailedBanner error={String(result.error ?? "Unknown error")}/>;
    }

    const status = result.status as string;
    const trackerCount = Number(result.tracker_count ?? 0);
    const thirdPartyCount = Number(result.third_party_count ?? 0);
    const totalRequests = Number(result.total_requests ?? 0);
    const categories = parseArr(result.categories);

    let trackers: DetectedTracker[] = [];
    const rawTrackers = result.trackers;
    if (Array.isArray(rawTrackers)) {
        trackers = rawTrackers as DetectedTracker[];
    } else if (typeof rawTrackers === "string" && rawTrackers !== "[]") {
        try {
            trackers = JSON.parse(rawTrackers);
        } catch { /* ignore */
        }
    }

    const isClean = status === "CLEAN" || trackerCount === 0;

    return (
        <div className="mt-4 space-y-4">
            {/* ── Status summary ──────────────────────────────────── */}
            <div className={`rounded-xl p-3 md:p-4 ring-1 ${
                isClean ? "bg-slate-100 dark:bg-slate-900/60 ring-green-300/40 dark:ring-green-800/30" : "bg-slate-100 dark:bg-slate-900/60 ring-amber-300/40 dark:ring-amber-800/30"
            }`}>
                <div className="flex items-center gap-2">
                    {isClean
                        ? <CheckCircle2 className="h-4 w-4 shrink-0 text-green-400"/>
                        : <Radar className="h-4 w-4 shrink-0 text-amber-400"/>
                    }
                    <p className={`text-xs font-semibold ${isClean ? "text-green-400" : "text-amber-400"}`}>
                        {isClean
                            ? "No Third-Party Trackers Detected"
                            : `${trackerCount} Tracker${trackerCount !== 1 ? "s" : ""} Detected`
                        }
                    </p>
                </div>
                <div className="mt-2 flex gap-4 text-[10px] text-slate-500 dark:text-slate-500">
                    <span>{totalRequests} total requests</span>
                    <span>{thirdPartyCount} third-party</span>
                </div>
            </div>

            {/* ── Category breakdown ──────────────────────────────── */}
            {categories.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                    {categories.map((cat) => (
                        <CategoryBadge key={cat} category={cat}/>
                    ))}
                </div>
            )}

            {/* ── Tracker list ────────────────────────────────────── */}
            {trackers.length > 0 && (
                <div
                    className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-slate-200 dark:ring-slate-700/30">
                    <p className="mb-3 text-xs font-semibold text-slate-700 dark:text-slate-300">Detected Trackers</p>
                    <div className="space-y-2">
                        {trackers.map((tracker, i) => (
                            <div key={i}
                                 className="flex items-center gap-2 rounded-lg bg-slate-50 dark:bg-slate-800/50 px-3 py-2 ring-1 ring-slate-200 dark:ring-slate-700/20">
                                <XCircle className="h-3.5 w-3.5 shrink-0 text-amber-500"/>
                                <div className="min-w-0 flex-1">
                                    <div className="flex items-center gap-2">
                                        <span
                                            className="text-xs font-medium text-slate-800 dark:text-slate-200 truncate">
                                            {tracker.name}
                                        </span>
                                        <CategoryBadge category={tracker.category}/>
                                    </div>
                                    <div className="mt-0.5 flex items-center gap-1.5">
                                        <Globe className="h-2.5 w-2.5 text-slate-600"/>
                                        <span
                                            className="font-mono text-[10px] text-slate-500 dark:text-slate-500 truncate">
                                            {tracker.domain}
                                        </span>
                                    </div>
                                </div>
                                <span
                                    className="shrink-0 rounded bg-slate-200 dark:bg-slate-700 px-1.5 py-0.5 text-[10px] font-medium text-slate-600 dark:text-slate-400">
                                    {tracker.request_count} req{tracker.request_count !== 1 ? "s" : ""}
                                </span>
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {/* ── Sample URLs (collapsible, for first tracker) ──── */}
            {trackers.length > 0 && trackers[0].sample_urls && trackers[0].sample_urls.length > 0 && (
                <details
                    className="rounded-xl bg-slate-100 dark:bg-slate-900/60 ring-1 ring-slate-200 dark:ring-slate-700/30">
                    <summary
                        className="cursor-pointer px-3 py-2.5 text-[11px] font-medium text-slate-400 hover:text-slate-700 dark:text-slate-300">
                        Sample request URLs
                    </summary>
                    <div className="border-t border-slate-200 dark:border-slate-800 px-3 py-2 space-y-1">
                        {trackers.flatMap(t => (t.sample_urls ?? []).slice(0, 1)).slice(0, 5).map((url, i) => (
                            <div key={i} className="flex items-center gap-1.5">
                                <ExternalLink className="h-2.5 w-2.5 shrink-0 text-slate-600"/>
                                <span
                                    className="font-mono text-[10px] text-slate-500 dark:text-slate-500 truncate">{url}</span>
                            </div>
                        ))}
                    </div>
                </details>
            )}

            {/* ── Remediation guidance ─────────────────────────────── */}
            {!isClean && <RemediationTip guidanceKey="TRACKER_DETECTION_FOUND"/>}

            {/* ── All clean ────────────────────────────────────────── */}
            {isClean && (
                <div
                    className="rounded-xl bg-slate-100 dark:bg-slate-900/60 p-3 md:p-4 ring-1 ring-green-300/40 dark:ring-green-800/30">
                    <p className="flex items-center gap-1.5 text-xs font-semibold text-green-400">
                        <CheckCircle2 className="h-3.5 w-3.5"/>
                        Clean — no known third-party trackers detected
                    </p>
                </div>
            )}
        </div>
    );
}
