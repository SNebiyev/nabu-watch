"use client";

import type {ErrorInfo, ReactNode} from "react";
import {Component} from "react";
import {AlertTriangle, Camera, CheckCircle2, ChevronDown, Clock, Info, Loader2, PauseCircle, ShieldAlert, XCircle, Zap} from "lucide-react";
import type {LatestScanItem, WebsiteHealth} from "@/lib/types";
import {useWebsiteDetails} from "./hooks/useWebsiteDetails";
import {ExpandPanelToolbar} from "./expand/ExpandPanelToolbar";
import {TotpChallengeModal} from "./TotpChallengeModal";
import {WebsiteSettingsModal} from "./WebsiteSettingsModal";
import {SecureDeleteModal} from "./SecureDeleteModal";
import {ManageMonitorsPanel} from "./drawer/ManageMonitorsPanel";
import {VerifyDomainCard} from "./VerifyDomainCard";
import {OwnerAbuseReportCard} from "./OwnerAbuseReportCard";
import {SecurityAuditGroup} from "./drawer/SecurityAuditGroup";
import {MonitorRow} from "./drawer/MonitorRow";
import {scanSortWeight, subTypeLabel} from "./drawer/utils";

interface WebsiteExpandPanelProps {
    website: WebsiteHealth;
    onClose: () => void;
    onMonitorsUpdated?: () => void;
}

// ── Skeleton loader ───────────────────────────────────────────────────────────

function SkeletonRow() {
    return (
        <div className="animate-pulse rounded-2xl border border-border bg-secondary/20 p-4 md:p-5">
            <div className="flex items-center gap-3">
                <div className="h-8 w-8 md:h-10 md:w-10 shrink-0 rounded-xl bg-muted"/>
                <div className="flex-1 space-y-2">
                    <div className="h-4 w-36 md:h-5 md:w-48 rounded bg-muted"/>
                    <div className="h-3 w-24 md:h-4 md:w-32 rounded bg-muted"/>
                </div>
                <div className="h-4 w-4 md:h-5 md:w-5 shrink-0 rounded-full bg-muted"/>
            </div>
        </div>
    );
}

// ── Error boundary ────────────────────────────────────────────────────────────

interface BoundaryState {
    hasError: boolean;
    error: Error | null;
}

class DrawerErrorBoundary extends Component<
    { children: ReactNode },
    BoundaryState
> {
    constructor(props: { children: ReactNode }) {
        super(props);
        this.state = {hasError: false, error: null};
    }

    static getDerivedStateFromError(error: Error): BoundaryState {
        return {hasError: true, error};
    }

    componentDidCatch(error: Error, info: ErrorInfo) {
        console.error("[DrawerErrorBoundary]", error, info.componentStack);
    }

    render() {
        if (this.state.hasError) {
            return (
                <div className="flex flex-col items-center justify-center py-16 text-center px-4">
                    <AlertTriangle className="mb-3 h-10 w-10 text-red-500"/>
                    <p className="text-sm font-semibold text-red-400">
                        Something went wrong
                    </p>
                    <p className="mt-1 text-xs text-muted-foreground break-words max-w-xs">
                        {this.state.error?.message ??
                            "An unexpected error occurred while rendering this scan."}
                    </p>
                    <button
                        className="mt-4 text-xs text-muted-foreground underline hover:text-foreground"
                        onClick={() => this.setState({hasError: false, error: null})}
                    >
                        Try again
                    </button>
                </div>
            );
        }
        return this.props.children;
    }
}

// ── ScanSummaryBanner ─────────────────────────────────────────────────────────

function ScanBadge({scan}: { scan: LatestScanItem }) {
    const label = subTypeLabel(scan.subType as Parameters<typeof subTypeLabel>[0]);
    const rj = scan.resultJson;
    const brokenArr = Array.isArray(rj?.broken_keywords) ? rj.broken_keywords : [];
    const isBlocked = scan.effectiveStatus === "FAILING" && brokenArr.length > 0;
    const isOk = scan.effectiveStatus === "PASSING";
    const isAmber = scan.effectiveStatus === "WARNING" || isBlocked;
    return (
        <span className="flex items-center gap-1 text-xs">
            {isOk ? <CheckCircle2 className="h-3 w-3 shrink-0 text-green-400"/>
                : isAmber ? <AlertTriangle className="h-3 w-3 shrink-0 text-amber-400"/>
                : <XCircle className="h-3 w-3 shrink-0 text-red-400"/>}
            <span className={isOk ? "text-slate-600 dark:text-slate-300"
                : isAmber ? "text-amber-600 dark:text-amber-300/90"
                : "text-red-600 dark:text-red-300/90"}>
                {label}
            </span>
            {isBlocked && <span className="text-[10px] text-amber-600/80">(Blocked)</span>}
        </span>
    );
}

function ScanSummaryBanner({scans, websiteUrl}: { scans: LatestScanItem[]; websiteUrl: string }) {
    const complianceScans = scans.filter(
        (s) => s.monitorType === "COMPLIANCE" && s.status !== "PENDING" && s.status !== "INACTIVE",
    );
    if (complianceScans.length === 0) return null;
    if (scans.some((s) => s.status === "PENDING")) return null;

    const domain = websiteUrl.replace(/^https?:\/\/(www\.)?/, "").split("/")[0];
    const failing = complianceScans.filter(s => s.effectiveStatus === "FAILING").length;
    const warning = complianceScans.filter(s => s.effectiveStatus === "WARNING").length;

    return (
        <>
            <details className="group sm:hidden rounded-xl border border-slate-200 dark:border-slate-700/40 bg-slate-50 dark:bg-slate-800/30 px-3.5 py-2.5">
                <summary className="flex cursor-pointer items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-slate-500 list-none">
                    <Info className="h-3 w-3 shrink-0"/>
                    <span className="flex-1 truncate">
                        {domain} — {complianceScans.length} check{complianceScans.length !== 1 ? "s" : ""}
                        {failing > 0 && <span className="ml-1 normal-case text-red-500">· {failing} fail</span>}
                        {warning > 0 && <span className="ml-1 normal-case text-amber-500">· {warning} warn</span>}
                    </span>
                    <ChevronDown className="h-3.5 w-3.5 shrink-0 opacity-60 transition-transform group-open:rotate-180"/>
                </summary>
                <div className="mt-2.5 flex flex-wrap gap-x-4 gap-y-1.5">
                    {complianceScans.map(s => <ScanBadge key={s.monitorId} scan={s}/>)}
                </div>
            </details>
            <div className="hidden sm:block rounded-xl border border-slate-200 dark:border-slate-700/40 bg-slate-50 dark:bg-slate-800/30 px-3.5 py-3">
                <p className="mb-2.5 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-500">
                    <Info className="h-3 w-3 shrink-0"/>
                    {domain} — {complianceScans.length} compliance check{complianceScans.length !== 1 ? "s" : ""}
                </p>
                <div className="flex flex-wrap gap-x-4 gap-y-1.5">
                    {complianceScans.map(s => <ScanBadge key={s.monitorId} scan={s}/>)}
                </div>
            </div>
        </>
    );
}

// ── Main expand panel ─────────────────────────────────────────────────────────

export function WebsiteExpandPanel({website, onClose, onMonitorsUpdated}: WebsiteExpandPanelProps) {
    const d = useWebsiteDetails(website, {onMonitorsUpdated, onClose});

    return (
        <div>
            <ExpandPanelToolbar
                website={website}
                manageMode={d.manageMode}
                loading={d.loading}
                scans={d.scans}
                history={d.history}
                historyIndex={d.historyIndex}
                setHistoryIndex={d.setHistoryIndex}
                syncing={d.syncing}
                rescanDone={d.rescanDone}
                saveResult={d.saveResult}
                rescanning={d.rescanning}
                anyExpanded={d.anyExpanded}
                isHistoryMode={d.isHistoryMode}
                isAdmin={d.isAdmin}
                failingCount={d.failingCount}
                passingCount={d.passingCount}
                warningCount={d.warningCount}
                pendingCount={d.pendingCount}
                hasIncompleteScans={d.hasIncompleteScans}
                canDelete={d.canDelete}
                batchCooldown={d.batchCooldown}
                downloadingPdf={d.downloadingPdf}
                refreshing={d.refreshing}
                onClose={onClose}
                onOpenSettings={d.handleSettingsOpen}
                onManageMode={d.handleManageMode}
                onRefresh={d.handleRefresh}
                onRescan={d.handleRescan}
                onForceRescan={d.handleForceRescan}
                onDelete={d.handleOpenDelete}
                onExpandAll={d.handleExpandAll}
                onDownloadPdf={d.handleDownloadPdf}
            />

            <div className="max-h-[70vh] overflow-y-auto md:max-h-none md:overflow-visible px-4 py-3 sm:px-5 sm:py-4">
                <DrawerErrorBoundary>
                    {!d.manageMode && d.localStatus === "PAUSED" && (
                        <div
                            className="mb-3 flex items-center gap-2.5 rounded-xl border border-slate-600/40 bg-slate-800/60 px-3.5 py-2.5">
                            <PauseCircle className="h-4 w-4 shrink-0 text-slate-400"/>
                            <div className="min-w-0 flex-1">
                                <p className="text-xs font-medium text-slate-300">Monitoring paused</p>
                                <p className="text-[11px] text-slate-500">
                                    Scheduled scans are suspended. Resume in Settings to restore automatic monitoring.
                                </p>
                            </div>
                        </div>
                    )}
                    {!d.manageMode && website && !d.localScreenshots && (
                        <button
                            onClick={d.handleSettingsOpen}
                            className="mb-3 flex w-full items-center gap-2 rounded-xl border border-sky-200 bg-sky-50 dark:border-sky-800/30 dark:bg-sky-950/20 px-3 py-1.5 text-left hover:bg-sky-100 dark:hover:bg-sky-950/40 transition-colors sm:items-start sm:gap-2.5 sm:px-3.5 sm:py-2.5"
                        >
                            <Camera className="h-4 w-4 shrink-0 text-sky-500 dark:text-sky-400 sm:mt-0.5"/>
                            <div className="min-w-0 flex-1">
                                <p className="text-xs font-medium text-sky-700 dark:text-sky-300">Screenshots disabled
                                    <span className="hidden sm:inline"> — Enable visual evidence capture</span>
                                </p>
                                <p className="hidden sm:block text-[11px] text-sky-600/80 dark:text-sky-500/80 underline">
                                    Open Settings
                                </p>
                            </div>
                        </button>
                    )}
                    {!d.manageMode && website && (
                        <div className="mb-3 space-y-3">
                            <VerifyDomainCard websiteId={website.id}/>
                            <OwnerAbuseReportCard websiteId={website.id}/>
                        </div>
                    )}
                    {d.manageMode && website ? (
                        <ManageMonitorsPanel
                            websiteId={website.id}
                            initialEnabled={d.activeComplianceSubTypes}
                            onSaved={d.handleMonitorsSaved}
                            onCancel={() => d.setManageMode(false)}
                        />
                    ) : d.loading ? (
                        <div className="flex items-center justify-center gap-2 py-8">
                            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground"/>
                            <span className="text-sm text-muted-foreground">Loading scan data...</span>
                        </div>
                    ) : d.scans.length === 0 ? (
                        <div className="flex flex-col items-center justify-center py-20 text-center">
                            <Clock className="mb-3 h-10 w-10 text-muted-foreground"/>
                            <p className="text-sm md:text-base text-[--muted-foreground]">
                                No scan data yet
                            </p>
                            <p className="mt-1 text-xs md:text-sm text-muted-foreground/70">
                                Hit Re-scan to queue the first check
                            </p>
                        </div>
                    ) : (
                        <div className="space-y-3">
                            {d.isHistoryMode && (
                                <div
                                    className="flex items-center gap-2 rounded-xl border border-amber-300 bg-amber-50 dark:border-amber-700/50 dark:bg-amber-950/30 px-3 py-2 text-xs text-amber-700 dark:text-amber-300">
                                    <Clock className="h-3.5 w-3.5 shrink-0"/>
                                    Viewing historical snapshot — live re-scan is disabled
                                </div>
                            )}
                            {d.rescanCooldownMsg && !d.isHistoryMode && (
                                <div
                                    className="flex items-center gap-2 rounded-xl border border-orange-300 bg-orange-50 dark:border-orange-700/50 dark:bg-orange-950/30 px-3 py-2 text-xs text-orange-700 dark:text-orange-300">
                                    <Clock className="h-3.5 w-3.5 shrink-0"/>
                                    {d.rescanCooldownMsg}
                                    {d.isAdmin && (
                                        <button
                                            type="button"
                                            onClick={d.handleForceRescan}
                                            className="ml-auto flex items-center gap-1 font-medium underline-offset-2 hover:underline"
                                        >
                                            <Zap className="h-3 w-3"/>Force override
                                        </button>
                                    )}
                                </div>
                            )}
                            {d.completionBanner && !d.syncing && !d.isHistoryMode && (
                                <div className={`flex items-start gap-2.5 rounded-xl border px-3.5 py-3 text-xs ${
                                    d.completionBanner === "issues"
                                        ? "border-amber-700/50 bg-amber-950/20 text-amber-300"
                                        : "border-green-700/50 bg-green-950/20 text-green-300"
                                }`}>
                                    {d.completionBanner === "issues" ? (
                                        <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-400"/>
                                    ) : (
                                        <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-green-400"/>
                                    )}
                                    <div className="min-w-0 flex-1">
                                        <p className="font-semibold">Scan Complete</p>
                                        <p className="mt-0.5 text-[11px] opacity-80">
                                            {d.completionBanner === "issues"
                                                ? "Action required: Compliance issues detected."
                                                : "Your site meets all required compliance guidelines."}
                                        </p>
                                    </div>
                                    <button
                                        type="button"
                                        onClick={d.handleDismissCompletionBanner}
                                        className="ml-auto shrink-0 rounded p-0.5 opacity-60 hover:opacity-100 transition-opacity"
                                        aria-label="Dismiss"
                                    >
                                        <XCircle className="h-3.5 w-3.5"/>
                                    </button>
                                </div>
                            )}
                            {website && (
                                <ScanSummaryBanner scans={d.displayedScans} websiteUrl={website.url}/>
                            )}

                            {d.displayedScans.some(s => s.wafBlocked) && (<>
                                <details className="group sm:hidden mb-3 rounded-xl border border-red-200 bg-red-50 px-3.5 py-2 text-xs text-red-800 dark:border-red-800/40 dark:bg-red-950/20 dark:text-red-200">
                                    <summary className="flex cursor-pointer items-center gap-2.5 list-none">
                                        <ShieldAlert className="h-4 w-4 shrink-0 text-red-500"/>
                                        <span className="flex-1 font-semibold text-sm">Scan Blocked by Firewall</span>
                                        <ChevronDown className="h-3.5 w-3.5 shrink-0 opacity-60 transition-transform group-open:rotate-180"/>
                                    </summary>
                                    <p className="mt-1 ml-6 text-red-700/80 dark:text-red-300/80">
                                        This site is protected by a web application firewall (WAF/CAPTCHA).
                                        The scanner could not access the homepage — compliance checks were skipped.
                                    </p>
                                </details>
                                <div className="hidden sm:flex mb-3 items-start gap-2.5 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-xs text-red-800 dark:border-red-800/40 dark:bg-red-950/20 dark:text-red-200">
                                    <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-red-500"/>
                                    <div>
                                        <p className="font-semibold text-sm">Scan Blocked by Firewall</p>
                                        <p className="mt-1 text-red-700/80 dark:text-red-300/80">
                                            This site is protected by a web application firewall (WAF/CAPTCHA).
                                            The scanner could not access the homepage — compliance checks were skipped.
                                        </p>
                                    </div>
                                </div>
                            </>)}

                            {!d.isHistoryMode && website && (
                                <div className="mb-3">
                                    <SecurityAuditGroup websiteId={website.id}/>
                                </div>
                            )}

                            {[...d.displayedScans]
                                .filter((s) => s.status !== "INACTIVE")
                                .sort(
                                    (a, b) =>
                                        scanSortWeight(a) - scanSortWeight(b) ||
                                        a.subType.localeCompare(b.subType)
                                )
                                .map((item) => (
                                    <MonitorRow
                                        key={item.monitorId}
                                        item={item}
                                        sslFailing={d.sslFailing}
                                        onRescan={d.isHistoryMode ? undefined : d.handleMonitorRescan}
                                        expandSignal={d.expandSignal}
                                        onExpand={d.handleRowExpand}
                                        domainCooldown={d.domainCooldown}
                                        isOwner={!!website && d.auth.email === website.ownerEmail}
                                        onOverrideSaved={d.handleRefresh}
                                    />
                                ))}
                        </div>
                    )}
                </DrawerErrorBoundary>
            </div>

            <TotpChallengeModal
                open={d.totpOpen}
                error={d.totpError}
                onConfirm={d.handleTotpConfirm}
                onCancel={d.handleTotpCancel}
            />

            {website && (
                <WebsiteSettingsModal
                    open={d.settingsOpen}
                    websiteId={website.id}
                    websiteUrl={website.url}
                    region={website.region}
                    websiteStatus={d.localStatus}
                    acceptedLanguages={website.acceptedLanguages ?? "NATIVE_ONLY"}
                    screenshotsEnabled={d.localScreenshots}
                    onClose={d.handleSettingsClose}
                    onStatusChanged={d.handleStatusChanged}
                    onLanguageChanged={d.handleLanguageChanged}
                    onScreenshotsChanged={d.handleScreenshotsChanged}
                />
            )}

            {website && (
                <SecureDeleteModal
                    open={d.deleteOpen}
                    siteName={(() => {
                        try {
                            return new URL(website.url).hostname;
                        } catch {
                            return website.url;
                        }
                    })()}
                    requiresOtp={d.deleteRequiresOtp}
                    onConfirm={d.handleDeleteConfirm}
                    onCancel={d.handleDeleteCancel}
                    loading={d.deleting}
                    error={d.deleteError}
                    onRequestOtp={d.handleRequestDeleteOtp}
                />
            )}
        </div>
    );
}
