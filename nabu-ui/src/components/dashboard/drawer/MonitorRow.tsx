import {memo, useEffect, useState} from "react";
import {
    AlertCircle,
    AlertTriangle,
    CheckCircle,
    ChevronDown,
    ChevronRight,
    Clock,
    Flag,
    HelpCircle,
    Info,
    Link2,
    Loader2,
    RefreshCw,
    Shield,
    Sparkles,
    Timer,
    TrendingDown,
    TrendingUp,
    User,
    Zap,
} from "lucide-react";
import {formatRelativeTime, safeExternalUrl} from "@/lib/utils";
import {useCountdown} from "@/hooks/useCountdown";
import type {LatestScanItem, MonitorSubType} from "@/lib/types";
import {hasComplianceData, subTypeLabel} from "./utils";
import {MonitorIcon} from "./MonitorIcon";
import {SslDetails} from "./SslDetails";
import {BannerDetails, ComplianceDetails} from "./ComplianceDetails";
import {TrackerDetails} from "./TrackerDetails";
import {StatusOverrideModal} from "./StatusOverrideModal";
import {ReportIssueModal} from "./ReportIssueModal";

function formatCooldown(seconds: number): string {
    return seconds >= 60 ? `${Math.ceil(seconds / 60)}m` : `${seconds}s`;
}

function RobotsTxtSection({content}: { content: string }) {
    const [open, setOpen] = useState(false);
    return (
        <div className="mt-3 rounded-xl border border-slate-200 dark:border-slate-700/40 overflow-hidden">
            <button
                type="button"
                onClick={() => setOpen(!open)}
                className="flex w-full items-center gap-2 px-3.5 py-2.5 text-xs font-medium text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800/40 transition-colors"
            >
                <Shield className="h-3.5 w-3.5 shrink-0"/>
                <span>robots.txt</span>
                <ChevronDown className={`ml-auto h-3.5 w-3.5 transition-transform ${open ? "rotate-180" : ""}`}/>
            </button>
            {open && (
                <pre className="max-h-48 overflow-auto border-t border-slate-200 dark:border-slate-700/40 bg-slate-50 dark:bg-slate-900/40 px-3.5 py-2.5 text-[11px] leading-relaxed text-slate-700 dark:text-slate-300 font-mono whitespace-pre-wrap break-all">
                    {content}
                </pre>
            )}
        </div>
    );
}

export const MonitorRow = memo(function MonitorRow({
                                                       item,
                                                       sslFailing = false,
                                                       onRescan,
                                                       expandSignal = null,
                                                       onExpand,
                                                       domainCooldown = 0,
                                                       isOwner = false,
                                                       onOverrideSaved,
                                                   }: {
    item: LatestScanItem;
    sslFailing?: boolean;
    /** Called with the monitorId when the per-monitor refresh icon is clicked. */
    onRescan?: (monitorId: string) => void;
    /** When non-null, forces all accordion rows open (open=true) or closed (open=false).
     *  The `n` counter increments on each click so the effect fires even when `open` repeats. */
    expandSignal?: { open: boolean; n: number } | null;
    /** Fired whenever this row transitions to the open state (user click or expand-all). */
    onExpand?: () => void;
    /** Remaining domain cooldown in seconds for per-monitor rescan; 0 = no cooldown. */
    domainCooldown?: number;
    /** Whether the current user is an OWNER in the organization (can manage overrides). */
    isOwner?: boolean;
    /** Called after an override is created/updated/removed so the parent can refresh data. */
    onOverrideSaved?: () => void;
}) {
    const [expanded, setExpanded] = useState(false);
    const [showOverrideModal, setShowOverrideModal] = useState(false);
    const [showReportModal, setShowReportModal] = useState(false);
    const countdown = useCountdown(item.nextScanAt);
    const isOverdue = !countdown && !!item.nextScanAt && new Date(item.nextScanAt).getTime() < Date.now();

    useEffect(() => {
        if (expandSignal !== null) {
            setExpanded(expandSignal.open);
            if (expandSignal.open) onExpand?.();
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [expandSignal]);

    const hasResult = item.resultJson != null;
    const isCompliance = item.monitorType === "COMPLIANCE";

    // Detect robots.txt skip
    const rj = item.resultJson as Record<string, unknown> | null;
    const isRobotsSkipped = rj?.skip_reason === "robots_txt_disallowed";

    // Incomplete: scan technically passed but populated zero keyword arrays (no content retrieved)
    const isIncomplete =
        item.status === "PASSING" && isCompliance && hasResult && !isRobotsSkipped && !hasComplianceData(item.resultJson);

    const isWarning = item.effectiveStatus === "WARNING";

    const statusColor =
        isRobotsSkipped
            ? "text-slate-500 dark:text-slate-400"
            : item.effectiveStatus === "FAILING"
                ? "text-red-600 dark:text-red-400"
                : isWarning
                    ? "text-amber-600 dark:text-amber-400"
                    : isIncomplete
                        ? "text-muted-foreground"
                        : item.effectiveStatus === "PASSING"
                            ? "text-green-600 dark:text-green-400"
                            : "text-muted-foreground";

    const isPending = item.status === "PENDING";
    const StatusIcon =
        isRobotsSkipped
            ? Shield
            : item.effectiveStatus === "FAILING"
                ? AlertTriangle
                : isWarning
                    ? AlertCircle
                    : isIncomplete
                        ? HelpCircle
                        : item.effectiveStatus === "PASSING"
                            ? CheckCircle
                            : isPending
                                ? Loader2
                                : Clock;

    const incompleteTooltip = isIncomplete
        ? sslFailing
            ? "Scan completed but could not retrieve content — likely blocked due to an SSL failure on this site."
            : "Scan completed but no content was retrieved — the page may have access restrictions (e.g. 403, timeout)."
        : undefined;

    return (
        <div
            className={`overflow-hidden rounded-2xl border transition-colors ${
                item.effectiveStatus === "FAILING"
                    ? "border-red-200 bg-red-50/80 dark:border-red-900/40 dark:bg-red-950/10"
                    : isWarning
                        ? "border-amber-200 bg-amber-50/80 dark:border-amber-900/40 dark:bg-amber-950/10"
                        : "border-border bg-secondary/20"
            }`}
        >
            {/* ── Row — two sibling buttons inside a group div (valid HTML) ──────── */}
            <div className="group flex items-stretch">
                {/* Expand / collapse button — takes all remaining width */}
                <button
                    className="flex flex-1 items-center gap-3 px-3 py-3 md:px-4 md:py-3.5 text-left transition-colors hover:bg-secondary/40"
                    onClick={() => {
                        if (!hasResult) return;
                        const next = !expanded;
                        setExpanded(next);
                        if (next) onExpand?.();
                    }}
                    disabled={!hasResult}
                >
                    <div
                        className="flex h-8 w-8 md:h-10 md:w-10 shrink-0 items-center justify-center rounded-xl bg-secondary/50 ring-1 ring-border">
                        <MonitorIcon subType={item.subType as MonitorSubType}/>
                    </div>
                    <div className="min-w-0 flex-1">
                        <p className="text-sm md:text-base font-medium text-[--foreground]">
                            {subTypeLabel(item.subType as MonitorSubType)}
                        </p>
                        <p className="mt-0.5 flex flex-wrap items-center gap-1.5 text-xs md:text-sm text-[--muted-foreground]">
                            {isPending ? (
                                <span className="inline-flex items-center gap-1 text-sky-500 dark:text-sky-400">
                  <Loader2 className="h-3 w-3 animate-spin"/>
                  Processing…
                </span>
                            ) : (
                                <>
                  <span>
                    {item.scannedAt
                        ? `Scanned ${formatRelativeTime(item.scannedAt)}`
                        : "Not yet scanned"}
                  </span>
                                    {item.triggerType === "MANUAL" && (
                                        <span
                                            className="inline-flex items-center gap-0.5 rounded-full bg-sky-100 px-1.5 py-0.5 text-[10px] font-medium text-sky-700 ring-1 ring-inset ring-sky-300 dark:bg-sky-950/60 dark:text-sky-300 dark:ring-sky-500/30"
                                            title={item.triggeredByEmail ? `Triggered by ${item.triggeredByEmail}` : "Manual scan"}
                                        >
                                            <User className="h-2.5 w-2.5 shrink-0"/>
                                            {item.triggeredByEmail
                                                ? item.triggeredByEmail.split("@")[0]
                                                : "Manual"}
                                        </span>
                                    )}
                                    {item.triggerType === "SCHEDULED" && (
                                        <span
                                            className="inline-flex items-center gap-0.5 rounded-full bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-600 ring-1 ring-inset ring-slate-300 dark:bg-slate-800/60 dark:text-slate-400 dark:ring-slate-600/30"
                                        >
                                            <Zap className="h-2.5 w-2.5 shrink-0"/>
                                            Auto
                                        </span>
                                    )}
                                    {rj?.scanned_via === "HEADLESS" && (
                                        <span
                                            className="inline-flex items-center rounded-full bg-violet-100 px-1.5 py-0.5 text-[10px] font-medium text-violet-700 ring-1 ring-inset ring-violet-300 dark:bg-violet-950/60 dark:text-violet-300 dark:ring-violet-500/30">
                      JS Rendered
                    </span>
                                    )}
                                    {rj?.scanned_via === "AI_ASSISTED" && (
                                        <span
                                            className="inline-flex items-center gap-1 rounded-full bg-gradient-to-r from-amber-500/15 to-orange-500/15 px-2 py-0.5 text-[10px] font-semibold text-amber-500 ring-1 ring-inset ring-amber-500/25 dark:from-amber-500/10 dark:to-orange-500/10 dark:text-amber-400 dark:ring-amber-400/20">
                      <Sparkles className="h-2.5 w-2.5"/>AI Assisted
                    </span>
                                    )}
                                    {rj?.scanned_via === "AI_HINT_VERIFIED" && (
                                        <span
                                            className="inline-flex items-center gap-1 rounded-full bg-gradient-to-r from-emerald-500/15 to-teal-500/15 px-2 py-0.5 text-[10px] font-semibold text-emerald-500 ring-1 ring-inset ring-emerald-500/25 dark:from-emerald-500/10 dark:to-teal-500/10 dark:text-emerald-400 dark:ring-emerald-400/20">
                      <Sparkles className="h-2.5 w-2.5"/>AI Verified
                    </span>
                                    )}
                                    {item.targetUrl && safeExternalUrl(item.targetUrl) && (
                                        <a
                                            href={safeExternalUrl(item.targetUrl)!}
                                            target="_blank"
                                            rel="noopener noreferrer"
                                            onClick={e => e.stopPropagation()}
                                            title={item.targetUrl}
                                            className="inline-flex items-center gap-1 rounded-full bg-sky-100 px-1.5 py-0.5 text-[10px] font-medium text-sky-700 ring-1 ring-inset ring-sky-300 hover:bg-sky-200 dark:bg-sky-950/60 dark:text-sky-300 dark:ring-sky-500/30 dark:hover:bg-sky-900/60 max-w-[180px]"
                                        >
                                            <Link2 className="h-2.5 w-2.5 shrink-0"/>
                                            <span className="truncate">{new URL(item.targetUrl).pathname}</span>
                                        </a>
                                    )}
                                    {item.statusChange && item.statusChange !== "UNCHANGED" && item.statusChange !== "FIRST_SCAN" && (() => {
                                        const delta = item.previousSeverity
                                            ? `${item.previousSeverity} → ${item.severity ?? "INFO"}`
                                            : "";
                                        const cfg = {
                                            RECOVERED: {
                                                label: "Recovered",
                                                Icon: TrendingUp,
                                                cls: "bg-emerald-100 text-emerald-700 ring-emerald-300 dark:bg-emerald-950/60 dark:text-emerald-300 dark:ring-emerald-500/30",
                                            },
                                            IMPROVED: {
                                                label: "Improved",
                                                Icon: TrendingUp,
                                                cls: "bg-emerald-100 text-emerald-700 ring-emerald-300 dark:bg-emerald-950/60 dark:text-emerald-300 dark:ring-emerald-500/30",
                                            },
                                            REGRESSED_TO_FAILING: {
                                                label: "Regressed",
                                                Icon: TrendingDown,
                                                cls: "bg-rose-100 text-rose-700 ring-rose-300 dark:bg-rose-950/60 dark:text-rose-300 dark:ring-rose-500/30",
                                            },
                                            REGRESSED: {
                                                label: "Regressed",
                                                Icon: TrendingDown,
                                                cls: "bg-amber-100 text-amber-700 ring-amber-300 dark:bg-amber-950/60 dark:text-amber-300 dark:ring-amber-500/30",
                                            },
                                        }[item.statusChange];
                                        if (!cfg) return null;
                                        const {Icon} = cfg;
                                        return (
                                            <span
                                                className={`inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[10px] font-medium ring-1 ring-inset ${cfg.cls}`}
                                                title={`Status changed since last scan${delta ? `: ${delta}` : ""}`}
                                            >
                                                <Icon className="h-2.5 w-2.5 shrink-0"/>
                                                {cfg.label}
                                            </span>
                                        );
                                    })()}
                                    {item.aiVerifiedTerm && (
                                        <span
                                            className="inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[10px] font-medium ring-1 ring-inset bg-violet-100 text-violet-700 ring-violet-300 dark:bg-violet-950/60 dark:text-violet-300 dark:ring-violet-500/30"
                                            title={item.aiVerificationReasoning
                                                ? `${item.aiVerifiedTerm} — ${item.aiVerificationReasoning}`
                                                : item.aiVerifiedTerm}
                                        >
                                            <Sparkles className="h-2.5 w-2.5 shrink-0"/>
                                            AI-verified
                                        </span>
                                    )}
                                    {isRobotsSkipped && (
                                        <span
                                            className="inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[10px] font-medium ring-1 ring-inset bg-slate-100 text-slate-600 ring-slate-300 dark:bg-slate-800/60 dark:text-slate-400 dark:ring-slate-600/30"
                                            title="Scan skipped — site's robots.txt disallows access"
                                        >
                                            <Shield className="h-2.5 w-2.5 shrink-0"/>
                                            robots.txt
                                        </span>
                                    )}
                                    {item.fastPathVerified && (
                                        <span
                                            className="inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[10px] font-medium ring-1 ring-inset bg-sky-100 text-sky-700 ring-sky-300 dark:bg-sky-950/60 dark:text-sky-300 dark:ring-sky-500/30"
                                            title="Verified against a previously discovered compliance path"
                                        >
                                            <Zap className="h-2.5 w-2.5 shrink-0"/>
                                            Fast-path
                                        </span>
                                    )}
                                    {item.hasOverride && (
                                        <span
                                            className={`inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[10px] font-medium ring-1 ring-inset ${
                                                item.overrideMismatch
                                                    ? "bg-orange-100 text-orange-700 ring-orange-300 dark:bg-orange-950/60 dark:text-orange-300 dark:ring-orange-500/30"
                                                    : "bg-emerald-100 text-emerald-700 ring-emerald-300 dark:bg-emerald-950/60 dark:text-emerald-300 dark:ring-emerald-500/30"
                                            }`}
                                            title={
                                                item.overrideInfo
                                                    ? `Override: ${item.overrideInfo.baselineSeverity} → ${item.overrideInfo.overrideSeverity} by ${item.overrideInfo.overriddenByEmail}${item.overrideInfo.note ? ` — "${item.overrideInfo.note}"` : ""}`
                                                    : "Status override active"
                                            }
                                        >
                                            <Shield className="h-2.5 w-2.5 shrink-0"/>
                                            {item.overrideMismatch ? "Override mismatch" : "Overridden"}
                                        </span>
                                    )}
                                    {countdown ? (
                                        <span
                                            className="inline-flex items-center gap-1 rounded-full bg-indigo-100 px-1.5 py-0.5 text-[10px] font-medium text-indigo-600 ring-1 ring-inset ring-indigo-200 dark:bg-indigo-950/60 dark:text-indigo-400 dark:ring-indigo-500/30"
                                            title={`Next scan: ${new Date(item.nextScanAt!).toLocaleString()}`}
                                        >
                                            <Timer className="h-2.5 w-2.5 shrink-0"/>
                                            {countdown}
                                        </span>
                                    ) : isOverdue ? (
                                        <span
                                            className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-1.5 py-0.5 text-[10px] font-medium text-amber-600 ring-1 ring-inset ring-amber-200 dark:bg-amber-950/60 dark:text-amber-400 dark:ring-amber-500/30"
                                            title="Scan is queued and will run automatically when its turn comes."
                                        >
                                            <Clock className="h-2.5 w-2.5 shrink-0"/>
                                            Overdue
                                        </span>
                                    ) : null}
                                </>
                            )}
                        </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
            <span title={isPending ? "Scan in progress…" : incompleteTooltip}>
              <StatusIcon
                  className={`h-4 w-4 md:h-5 md:w-5 ${statusColor} ${isPending ? "animate-spin" : ""}`}
              />
            </span>
                        {hasResult &&
                            (expanded ? (
                                <ChevronDown className="h-4 w-4 text-[--muted-foreground]"/>
                            ) : (
                                <ChevronRight className="h-4 w-4 text-[--muted-foreground]"/>
                            ))}
                    </div>
                </button>


                {/* Per-monitor refresh button — visible on row hover, hidden by default */}
                {onRescan && (
                    <button
                        type="button"
                        onClick={() => onRescan(item.monitorId)}
                        disabled={isPending || domainCooldown > 0}
                        title={
                            isPending ? "Scan in progress…"
                                : domainCooldown > 0 ? `Cooldown: ${formatCooldown(domainCooldown)}`
                                    : "Re-scan this monitor"
                        }
                        className={`flex items-center gap-1 px-3 transition-opacity duration-150 disabled:cursor-not-allowed hover:bg-secondary/40 ${
                            domainCooldown > 0
                                ? "opacity-100 text-orange-500"
                                : "opacity-0 group-hover:opacity-100 text-slate-500 hover:text-sky-400 disabled:opacity-30"
                        }`}
                    >
                        {domainCooldown > 0 ? (
                            <>
                                <Clock className="h-3 w-3"/>
                                <span className="text-[10px] font-medium">{formatCooldown(domainCooldown)}</span>
                            </>
                        ) : (
                            <RefreshCw className={`h-3.5 w-3.5 ${isPending ? "animate-spin text-sky-400" : ""}`}/>
                        )}
                    </button>
                )}
            </div>

            {/* Override mismatch banner — shown when the scan result differs from the accepted baseline */}
            {item.hasOverride && item.overrideMismatch && item.overrideInfo && (
                <div
                    className="mx-3 mb-2 mt-1 flex items-start gap-2 rounded-xl border border-orange-200 bg-orange-50 px-3 py-2.5 text-xs text-orange-800 dark:border-orange-800/40 dark:bg-orange-950/20 dark:text-orange-200">
                    <Info className="mt-0.5 h-3.5 w-3.5 shrink-0"/>
                    <div>
                        <p className="font-medium">Scan result changed since last override</p>
                        <p className="mt-0.5 text-orange-600 dark:text-orange-300">
                            Previously accepted: <span
                            className="font-medium">{item.overrideInfo.baselineSeverity}</span> → <span
                            className="font-medium">{item.overrideInfo.overrideSeverity}</span>
                            {" "}by {item.overrideInfo.overriddenByEmail.split("@")[0]}.
                            {item.overrideInfo.note && (
                                <span className="italic"> &quot;{item.overrideInfo.note}&quot;</span>
                            )}
                        </p>
                        <p className="mt-0.5 text-orange-600 dark:text-orange-300">
                            Current result may need review — the override is not being applied.
                        </p>
                    </div>
                </div>
            )}

            {/* Override note — shown when override is active and matching (no mismatch) */}
            {item.hasOverride && !item.overrideMismatch && item.overrideInfo?.note && (
                <div
                    className="mx-3 mb-2 mt-1 flex items-start gap-2 rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-800 dark:border-emerald-800/40 dark:bg-emerald-950/20 dark:text-emerald-200">
                    <Shield className="mt-0.5 h-3.5 w-3.5 shrink-0"/>
                    <p>
                        <span className="font-medium">Override active</span>
                        {" — "}<span className="italic">&quot;{item.overrideInfo.note}&quot;</span>
                        <span
                            className="text-emerald-600 dark:text-emerald-400"> ({item.overrideInfo.overriddenByEmail.split("@")[0]})</span>
                    </p>
                </div>
            )}


            {/* robots.txt skip banner — shown below the row when scan was skipped */}
            {isRobotsSkipped && (
                <div
                    className="mx-3 mb-2 mt-1 flex items-start gap-2.5 rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-xs text-slate-700 dark:border-slate-700/40 dark:bg-slate-800/30 dark:text-slate-300">
                    <Shield className="mt-0.5 h-4 w-4 shrink-0 text-slate-500 dark:text-slate-400"/>
                    <div>
                        <p className="font-semibold">Scan skipped — blocked by robots.txt</p>
                        <p className="mt-0.5 text-slate-600 dark:text-slate-400">
                            This site&apos;s robots.txt disallows access to the scanned URL.
                            The scan was skipped to respect the site&apos;s crawling policy.
                        </p>
                    </div>
                </div>
            )}

            {expanded && hasResult && (
                <div className="border-t border-border px-4 pb-4 md:px-6 md:pb-6">
                    {/* Action buttons — override (owner only) + report issue (all members) */}
                    {item.scanLogId && (
                        <div className="flex items-center justify-end gap-2 pt-3 pb-1">
                            {isOwner && (
                                <button
                                    type="button"
                                    onClick={() => setShowOverrideModal(true)}
                                    className="inline-flex items-center gap-1.5 rounded-lg border border-amber-500/30 bg-amber-500/10 px-2.5 py-1.5 text-xs font-medium text-amber-600 transition-colors hover:bg-amber-500/20 dark:text-amber-400"
                                >
                                    <Shield className="h-3.5 w-3.5"/>
                                    {item.hasOverride ? "Edit accepted severity" : "Adjust severity manually"}
                                </button>
                            )}
                            <button
                                type="button"
                                onClick={() => setShowReportModal(true)}
                                className="inline-flex items-center gap-1.5 rounded-lg border border-red-500/30 bg-red-500/10 px-2.5 py-1.5 text-xs font-medium text-red-600 transition-colors hover:bg-red-500/20 dark:text-red-400"
                            >
                                <Flag className="h-3.5 w-3.5"/>
                                Report Issue
                            </button>
                        </div>
                    )}
                    {item.monitorType === "SSL" ? (
                        <SslDetails result={item.resultJson as Record<string, unknown>}/>
                    ) : item.subType === "COOKIE_BANNER" ? (
                        <BannerDetails result={item.resultJson as Record<string, unknown>}/>
                    ) : item.subType === "TRACKER_DETECTION" ? (
                        <TrackerDetails result={item.resultJson as Record<string, unknown>}/>
                    ) : (
                        <ComplianceDetails result={item.resultJson as Record<string, unknown>} subType={item.subType}/>
                    )}

                    {typeof rj?.robots_txt_content === "string" && rj.robots_txt_content !== "" && (
                        <RobotsTxtSection content={rj.robots_txt_content}/>
                    )}
                </div>
            )}

            {/* Override modal */}
            {showOverrideModal && (
                <StatusOverrideModal
                    item={item}
                    onClose={() => setShowOverrideModal(false)}
                    onSaved={() => onOverrideSaved?.()}
                />
            )}

            {/* Report issue modal */}
            {showReportModal && (
                <ReportIssueModal
                    item={item}
                    onClose={() => setShowReportModal(false)}
                    onReported={() => {}}
                />
            )}
        </div>
    );
});
