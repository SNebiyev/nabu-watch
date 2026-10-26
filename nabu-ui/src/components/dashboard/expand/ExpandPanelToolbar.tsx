import {
    Camera,
    Check,
    ChevronDown,
    ChevronUp,
    Clock,
    Copy,
    Download,
    ExternalLink,
    Loader2,
    RefreshCw,
    Settings,
    SlidersHorizontal,
    Trash2,
    TrendingUp,
    X,
    Zap,
} from "lucide-react";
import Link from "next/link";
import {useState} from "react";
import type {LatestScanItem, ScanBatch, SyncMonitorsResult, WebsiteHealth} from "@/lib/types";
import {safeExternalUrl} from "@/lib/utils";
import {GradeBadge} from "@/components/dashboard/GradeBadge";
import {HistoryNavigator} from "../drawer/HistoryNavigator";

export interface ExpandPanelToolbarProps {
    website: WebsiteHealth | null;
    manageMode: boolean;
    loading: boolean;
    scans: LatestScanItem[];
    history: ScanBatch[];
    historyIndex: number;
    setHistoryIndex: React.Dispatch<React.SetStateAction<number>>;
    syncing: boolean;
    rescanDone: boolean;
    saveResult: SyncMonitorsResult | null;
    rescanning: boolean;
    anyExpanded: boolean;
    isHistoryMode: boolean;
    isAdmin: boolean;
    failingCount: number;
    passingCount: number;
    warningCount: number;
    pendingCount: number;
    hasIncompleteScans: boolean;
    canDelete: boolean;
    /** Remaining batch rescan cooldown in seconds; 0 = no cooldown active. */
    batchCooldown: number;
    downloadingPdf: boolean;
    refreshing: boolean;
    onClose: () => void;
    onOpenSettings: () => void;
    onManageMode: () => void;
    onRefresh: () => void;
    onRescan: () => void;
    onForceRescan: () => void;
    onDelete: () => void;
    onExpandAll: () => void;
    onDownloadPdf: () => void;
}

function CopyUrlButton({url}: { url: string | undefined }) {
    const [copied, setCopied] = useState(false);
    if (!url) return null;
    const handleClick = async (e: React.MouseEvent) => {
        e.preventDefault();
        e.stopPropagation();
        try {
            await navigator.clipboard.writeText(url);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
        } catch {
            // Clipboard API unavailable (insecure context, denied permission) — silently no-op.
        }
    };
    return (
        <button
            type="button"
            onClick={handleClick}
            aria-label={copied ? "Copied" : "Copy URL"}
            title={copied ? "Copied" : "Copy URL"}
            className="shrink-0 inline-flex h-5 w-5 items-center justify-center rounded hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
        >
            {copied
                ? <Check className="h-3 w-3 text-emerald-500"/>
                : <Copy className="h-3 w-3"/>}
        </button>
    );
}

export function ExpandPanelToolbar({
                                       website,
                                       manageMode,
                                       loading,
                                       scans,
                                       history,
                                       historyIndex,
                                       setHistoryIndex,
                                       syncing,
                                       rescanDone,
                                       saveResult,
                                       rescanning,
                                       anyExpanded,
                                       isHistoryMode,
                                       isAdmin,
                                       failingCount,
                                       passingCount,
                                       warningCount,
                                       pendingCount,
                                       hasIncompleteScans,
                                       canDelete,
                                       batchCooldown,
                                       downloadingPdf,
                                       refreshing,
                                       onClose,
                                       onOpenSettings,
                                       onManageMode,
                                       onRefresh,
                                       onRescan,
                                       onForceRescan,
                                       onDelete,
                                       onExpandAll,
                                       onDownloadPdf,
                                   }: ExpandPanelToolbarProps) {
    const cooldownLabel = batchCooldown > 0
        ? batchCooldown >= 60 ? `${Math.ceil(batchCooldown / 60)}m` : `${batchCooldown}s`
        : null;

    // ── Shared: status badge (strict state machine) ─────────────────────────────
    // Priority 1: Any monitor still processing → muted "Scanning…" (never green)
    // Priority 2: Issues detected → red/amber error/warning badge
    // Priority 3: ALL completed with zero issues → green "All passing"
    const allCompleted = pendingCount === 0 && !hasIncompleteScans;
    const statusBadge =
        pendingCount > 0 || hasIncompleteScans ? (
            <span
                className="inline-flex shrink-0 items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-600 ring-1 ring-slate-200 dark:bg-slate-800/60 dark:text-slate-300 dark:ring-slate-600/40">
        <Loader2 className="h-2.5 w-2.5 animate-spin"/>Scanning…
      </span>
        ) : failingCount > 0 ? (
            <span
                className="shrink-0 rounded-full bg-red-100 px-2 py-0.5 text-[11px] font-medium text-red-700 ring-1 ring-red-200 dark:bg-red-950/60 dark:text-red-400 dark:ring-red-900/40">
        {failingCount} issue{failingCount > 1 ? "s" : ""}
      </span>
        ) : warningCount > 0 ? (
            <span
                className="shrink-0 rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-medium text-amber-700 ring-1 ring-amber-200 dark:bg-amber-950/60 dark:text-amber-400 dark:ring-amber-800/40">
        {warningCount} warn
      </span>
        ) : allCompleted && passingCount > 0 ? (
            <span
                className="shrink-0 rounded-full bg-green-100 px-2 py-0.5 text-[11px] font-medium text-green-700 ring-1 ring-green-200 dark:bg-green-950/60 dark:text-green-400 dark:ring-green-900/40">
        <span className="sm:hidden">✓</span>
        <span className="hidden sm:inline">All passing</span>
      </span>
        ) : null;

    // ── Shared: global chevron button ───────────────────────────────────────────
    const globalChevron = (size: "sm" | "md") =>
        !loading && scans.length > 0 ? (
            <button
                type="button"
                onClick={onExpandAll}
                title={anyExpanded ? "Collapse all monitors" : "Expand all monitors"}
                className={`flex items-center gap-1 rounded border border-border bg-card text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors ${
                    size === "sm" ? "h-6 px-1.5" : "h-5 px-1.5"
                }`}
            >
                {size === "md" && (
                    <span className="text-[10px]">
                        {anyExpanded ? "Collapse monitors" : "Expand monitors"}
                    </span>
                )}
                {anyExpanded ? (
                    <ChevronUp className={size === "sm" ? "h-3.5 w-3.5" : "h-3 w-3"}/>
                ) : (
                    <ChevronDown className={size === "sm" ? "h-3.5 w-3.5" : "h-3 w-3"}/>
                )}
            </button>
        ) : null;

    // Close action: in manage mode → exit manage mode only; otherwise → close panel
    const handleClose = manageMode ? onManageMode : onClose;

    return (
        <div className="sticky top-14 lg:top-0 z-20 border-b border-border bg-card/95 backdrop-blur-sm px-4 py-3 sm:px-5 sm:py-4">

            {/* ╔══════════════════════════════════════════════════════════════════╗
          ║  MOBILE BLOCK  (< 640 px)  ·  sm:hidden                        ║
          ║  3 rows: identity → grid buttons → history                     ║
          ╚══════════════════════════════════════════════════════════════════╝ */}
            <div className="flex w-full flex-col gap-2 sm:hidden">

                {/* ── Mobile Row 1: Identity ─────────────────────────────────────
            [Region badge]  [URL truncated]  [Grade]  [Status]  [✕]       */}
                <div className="flex items-center gap-2">
                    {!manageMode && (
                        <span
                            className="shrink-0 rounded-md bg-secondary/60 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground ring-1 ring-border">
              {website?.region}
            </span>
                    )}

                    <span className="min-w-0 flex-1 truncate text-sm font-semibold text-foreground">
                        {manageMode ? "Manage Monitors" : (
                            <span className="inline-flex max-w-full items-center gap-1">
                                <a
                                    href={safeExternalUrl(website?.url) ?? "#"}
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    className="inline-flex max-w-full items-center gap-1 hover:text-sky-400 transition-colors"
                                >
                                    <span className="truncate">{website?.url.replace(/^https?:\/\//, "") ?? ""}</span>
                                    <ExternalLink className="h-3 w-3 shrink-0 opacity-60"/>
                                </a>
                                <CopyUrlButton url={website?.url}/>
                            </span>
                        )}
                    </span>

                    {!manageMode && !loading && (
                        <GradeBadge grade={website?.complianceGrade} score={website?.complianceScore}/>
                    )}
                    {!manageMode && !loading && statusBadge}
                    {!manageMode && syncing && pendingCount === 0 && !hasIncompleteScans && (
                        <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-blue-500"/>
                    )}

                    <button
                        type="button"
                        onClick={handleClose}
                        className="shrink-0 rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
                        aria-label={manageMode ? "Close manage panel" : "Close panel"}
                    >
                        <X className="h-4 w-4"/>
                    </button>
                </div>

                {/* ── Mobile Row 2: Action button GRID (3-col, 44px min-height) ── */}
                {!manageMode && (
                    <div className="grid grid-cols-3 gap-2">
                        {/* Refresh */}
                        <button
                            type="button"
                            onClick={onRefresh}
                            disabled={refreshing || isHistoryMode}
                            title="Refresh data"
                            className="flex min-h-9 flex-row items-center justify-center gap-1.5 rounded-md border border-border bg-card px-2 py-1.5 text-[11px] text-muted-foreground hover:bg-secondary hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40 transition-colors"
                        >
                            <RefreshCw className={`h-4 w-4 shrink-0 ${refreshing ? "animate-spin" : ""}`}/>
                            <span>Refresh</span>
                        </button>

                        {/* Re-scan */}
                        <button
                            type="button"
                            onClick={onRescan}
                            disabled={rescanning || isHistoryMode || batchCooldown > 0}
                            title={cooldownLabel ? `Re-scan (${cooldownLabel})` : "Re-scan all monitors"}
                            className="flex min-h-9 flex-row items-center justify-center gap-1.5 rounded-md border border-border bg-card px-2 py-1.5 text-[11px] text-muted-foreground hover:bg-secondary hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40 transition-colors"
                        >
                            {cooldownLabel ? (
                                <>
                                    <Clock className="h-4 w-4 shrink-0 text-orange-500"/>
                                    <span className="text-orange-500">{cooldownLabel}</span>
                                </>
                            ) : (
                                <>
                                    <RefreshCw className={`h-4 w-4 shrink-0 ${rescanning ? "animate-spin" : ""}`}/>
                                    <span>{rescanning ? "Queuing…" : "Re-scan"}</span>
                                </>
                            )}
                        </button>

                        {/* Force Re-scan (admin only) */}
                        {isAdmin ? (
                            <button
                                type="button"
                                onClick={onForceRescan}
                                disabled={isHistoryMode}
                                title="Force scan (Admin override)"
                                className="flex min-h-9 flex-row items-center justify-center gap-1.5 rounded-md border border-border bg-card px-2 py-1.5 text-[11px] text-amber-500/80 hover:bg-amber-500/10 hover:text-amber-400 disabled:cursor-not-allowed disabled:opacity-40 transition-colors"
                            >
                                <Zap className="h-4 w-4 shrink-0"/>
                                <span>Force</span>
                            </button>
                        ) : (
                            /* Trend — occupies Force slot when not admin */
                            <Link
                                href={`/dashboard/websites/${website?.id}/trends`}
                                title="Compliance Trend"
                                className="flex min-h-9 flex-row items-center justify-center gap-1.5 rounded-md border border-border bg-card px-2 py-1.5 text-[11px] text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                            >
                                <TrendingUp className="h-4 w-4 shrink-0"/>
                                <span>Trend</span>
                            </Link>
                        )}

                        {/* Trend (only shown for admin, as they already have Force above) */}
                        {isAdmin && (
                            <Link
                                href={`/dashboard/websites/${website?.id}/trends`}
                                title="Compliance Trend"
                                className="flex min-h-9 flex-row items-center justify-center gap-1.5 rounded-md border border-border bg-card px-2 py-1.5 text-[11px] text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                            >
                                <TrendingUp className="h-4 w-4 shrink-0"/>
                                <span>Trend</span>
                            </Link>
                        )}

                        {/* PDF */}
                        <button
                            type="button"
                            onClick={onDownloadPdf}
                            disabled={downloadingPdf || loading || isHistoryMode}
                            title="Download PDF report"
                            className="flex min-h-9 flex-row items-center justify-center gap-1.5 rounded-md border border-border bg-card px-2 py-1.5 text-[11px] text-muted-foreground hover:bg-secondary hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40 transition-colors"
                        >
                            {downloadingPdf
                                ? <Loader2 className="h-4 w-4 shrink-0 animate-spin"/>
                                : <Download className="h-4 w-4 shrink-0"/>}
                            <span>{downloadingPdf ? "Wait…" : "PDF"}</span>
                        </button>

                        {/* Monitors */}
                        <button
                            type="button"
                            onClick={onManageMode}
                            title="Manage Monitors"
                            className="flex min-h-9 flex-row items-center justify-center gap-1.5 rounded-md border border-border bg-card px-2 py-1.5 text-[11px] text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                        >
                            <Settings className="h-4 w-4 shrink-0"/>
                            <span>Monitors</span>
                        </button>

                        {/* Settings */}
                        <button
                            type="button"
                            onClick={onOpenSettings}
                            title="Website Settings"
                            className="flex min-h-9 flex-row items-center justify-center gap-1.5 rounded-md border border-border bg-card px-2 py-1.5 text-[11px] text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                        >
                            <SlidersHorizontal className="h-4 w-4 shrink-0"/>
                            <span>Settings</span>
                        </button>
                    </div>
                )}

                {/* Manage mode subtitle */}
                {manageMode && (
                    <p className="truncate text-xs text-muted-foreground">
                        {website?.url.replace(/^https?:\/\//, "")}
                    </p>
                )}

                {/* ── Mobile Row 3: History Navigator + Global Chevron ───────────*/}
                {!manageMode && (
                    <div className="flex items-center justify-between gap-2">
                        {history.length > 0 ? (
                            <HistoryNavigator
                                history={history}
                                historyIndex={historyIndex}
                                onOlder={() => setHistoryIndex((i) => Math.min(i + 1, history.length))}
                                onNewer={() => setHistoryIndex((i) => Math.max(i - 1, 0))}
                                onLatest={() => setHistoryIndex(0)}
                                compact
                            />
                        ) : (
                            <span className="text-[10px] text-muted-foreground">
                <span className="font-medium text-foreground">Latest State</span>
              </span>
                        )}
                        {globalChevron("sm")}
                    </div>
                )}
            </div>

            {/* ╔══════════════════════════════════════════════════════════════════╗
          ║  DESKTOP / TABLET BLOCK  (≥ 640 px)  ·  hidden sm:flex         ║
          ║  3 rows: identity+meta → action buttons → history+chevron+del   ║
          ╚══════════════════════════════════════════════════════════════════╝ */}
            <div className="hidden w-full sm:flex sm:flex-col sm:gap-1.5">

                {/* ── Desktop Row 1: URL + Region + Screenshots + badges + Close ── */}
                <div className="flex min-w-0 items-center gap-2">
                    <span className="min-w-0 shrink truncate text-sm font-semibold text-foreground md:text-base">
                        {manageMode ? "Manage Monitors" : (
                            <span className="inline-flex max-w-full items-center gap-1">
                                <a
                                    href={safeExternalUrl(website?.url) ?? "#"}
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    className="inline-flex max-w-full items-center gap-1 hover:text-sky-400 transition-colors"
                                >
                                    <span className="truncate">{website?.url.replace(/^https?:\/\//, "") ?? ""}</span>
                                    <ExternalLink className="h-3 w-3 shrink-0 opacity-60"/>
                                </a>
                                <CopyUrlButton url={website?.url}/>
                            </span>
                        )}
                    </span>

                    {!manageMode && (
                        <span className="shrink-0 text-xs text-muted-foreground">
              Region: <span className="font-medium text-foreground">{website?.region}</span>
            </span>
                    )}
                    {!manageMode && website?.screenshotsEnabled && (
                        <button
                            onClick={onOpenSettings}
                            title="Screenshot settings"
                            className="inline-flex shrink-0 items-center gap-1 rounded-full bg-violet-100 px-1.5 py-0.5 text-[10px] font-medium text-violet-700 ring-1 ring-violet-200 hover:bg-violet-200 dark:bg-violet-950/60 dark:text-violet-300 dark:ring-violet-500/30 dark:hover:bg-violet-900/60 transition-colors">
                            <Camera className="h-2.5 w-2.5"/>
                            Screenshots
                        </button>
                    )}

                    {!manageMode && !loading && (
                        <GradeBadge grade={website?.complianceGrade} score={website?.complianceScore}/>
                    )}
                    {!manageMode && !loading && statusBadge}
                    {!manageMode && syncing && pendingCount === 0 && !hasIncompleteScans && (
                        <span
                            className="inline-flex shrink-0 items-center gap-1 rounded-full bg-blue-100 px-2 py-0.5 text-[11px] font-medium text-blue-700 ring-1 ring-blue-200 dark:bg-blue-950/60 dark:text-blue-400 dark:ring-blue-900/40">
              <Loader2 className="h-2.5 w-2.5 animate-spin"/>Syncing…
            </span>
                    )}
                    {!manageMode && rescanDone && !syncing && (
                        <span
                            className="shrink-0 rounded-full bg-blue-100 px-2 py-0.5 text-[11px] font-medium text-blue-700 ring-1 ring-blue-200 dark:bg-blue-950/60 dark:text-blue-400 dark:ring-blue-900/40">
              Queued
            </span>
                    )}
                    {!manageMode && saveResult && (
                        <span
                            className="shrink-0 rounded-full bg-green-100 px-2 py-0.5 text-[11px] font-medium text-green-700 ring-1 ring-green-200 dark:bg-green-950/60 dark:text-green-400 dark:ring-green-900/40">
              Updated
            </span>
                    )}

                    {manageMode && (
                        <div className="ml-auto">
                            <button
                                type="button"
                                onClick={handleClose}
                                className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
                                aria-label="Close manage panel"
                            >
                                <X className="h-4 w-4 md:h-5 md:w-5"/>
                            </button>
                        </div>
                    )}
                </div>

                {manageMode && (
                    <span className="min-w-0 truncate text-xs text-muted-foreground">
              {website?.url.replace(/^https?:\/\//, "")}
            </span>
                )}

                {/* ── Desktop Row 2: All action buttons (full-width) ─────────────── */}
                {!manageMode && (
                    <div
                        className="flex flex-1 divide-x divide-border overflow-hidden rounded-md border border-border shadow-sm">
                        <button
                            type="button"
                            onClick={onRefresh}
                            disabled={refreshing || isHistoryMode}
                            title="Refresh data"
                            className="flex items-center justify-center gap-1.5 bg-card px-2.5 py-1.5 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                        >
                            <RefreshCw className={`h-3.5 w-3.5 shrink-0 ${refreshing ? "animate-spin" : ""}`}/>
                        </button>
                        <button
                            type="button"
                            onClick={onRescan}
                            disabled={rescanning || isHistoryMode || batchCooldown > 0}
                            title={cooldownLabel ? `Re-scan (${cooldownLabel})` : "Re-scan all monitors"}
                            className="flex flex-1 items-center justify-center gap-1.5 bg-card px-2.5 py-1.5 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                        >
                            {cooldownLabel ? (
                                <>
                                    <Clock className="h-3.5 w-3.5 shrink-0 text-orange-500"/>
                                    <span className="text-orange-500">{cooldownLabel}</span>
                                </>
                            ) : (
                                <>
                                    <RefreshCw
                                        className={`h-3.5 w-3.5 shrink-0 ${rescanning ? "animate-spin" : ""}`}/>
                                    <span>{rescanning ? "Queuing…" : "Re-scan"}</span>
                                </>
                            )}
                        </button>
                        {isAdmin && (
                            <button
                                type="button"
                                onClick={onForceRescan}
                                disabled={isHistoryMode}
                                title="Force scan (Admin override — bypasses cooldown)"
                                className="flex flex-1 items-center justify-center gap-1.5 bg-card px-2.5 py-1.5 text-xs text-amber-500/80 hover:bg-amber-500/10 hover:text-amber-400 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                            >
                                <Zap className="h-3.5 w-3.5 shrink-0"/>
                                <span>Force Re-scan</span>
                            </button>
                        )}
                        <Link
                            href={`/dashboard/websites/${website?.id}/trends`}
                            title="Compliance Trend"
                            className="flex flex-1 items-center justify-center gap-1.5 bg-card px-2.5 py-1.5 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                        >
                            <TrendingUp className="h-3.5 w-3.5 shrink-0"/>
                            <span>Site Trend</span>
                        </Link>
                        <button
                            type="button"
                            onClick={onDownloadPdf}
                            disabled={downloadingPdf || loading || isHistoryMode}
                            title="Download PDF compliance report"
                            className="flex flex-1 items-center justify-center gap-1.5 bg-card px-2.5 py-1.5 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                        >
                            {downloadingPdf
                                ? <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin"/>
                                : <Download className="h-3.5 w-3.5 shrink-0"/>}
                            <span>{downloadingPdf ? "Generating…" : "PDF"}</span>
                        </button>
                        <button
                            type="button"
                            onClick={onManageMode}
                            title="Manage Monitors"
                            className="flex flex-1 items-center justify-center gap-1.5 bg-card px-2.5 py-1.5 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                        >
                            <Settings className="h-3.5 w-3.5 shrink-0"/>
                            <span>Monitors</span>
                        </button>
                        <button
                            type="button"
                            onClick={onOpenSettings}
                            title="Website Settings"
                            className="flex flex-1 items-center justify-center gap-1.5 bg-card px-2.5 py-1.5 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                        >
                            <SlidersHorizontal className="h-3.5 w-3.5 shrink-0"/>
                            <span>Settings</span>
                        </button>
                    </div>
                )}

                {/* ── Desktop Row 3: History + Chevron + Delete Site ────────────── */}
                {!manageMode && (
                    <div className="flex items-center gap-1.5">
                        {history.length > 0 ? (
                            <HistoryNavigator
                                history={history}
                                historyIndex={historyIndex}
                                onOlder={() => setHistoryIndex((i) => Math.min(i + 1, history.length))}
                                onNewer={() => setHistoryIndex((i) => Math.max(i - 1, 0))}
                                onLatest={() => setHistoryIndex(0)}
                                compact={false}
                            />
                        ) : (
                            <span className="text-xs text-muted-foreground">
                <span className="font-medium text-foreground">Latest State</span>
              </span>
                        )}
                        <div className="ml-auto flex items-center gap-6">
                            {globalChevron("md")}
                            {canDelete && (
                                <button
                                    type="button"
                                    onClick={onDelete}
                                    title="Delete site from monitoring"
                                    className="flex items-center gap-1.5 rounded-md px-2 py-1.5 text-xs text-red-500/70 hover:bg-red-500/10 hover:text-red-400 transition-colors"
                                >
                                    <Trash2 className="h-3.5 w-3.5 shrink-0"/>
                                    <span>Delete Site</span>
                                </button>
                            )}
                        </div>
                    </div>
                )}
            </div>

        </div>
    );
}
