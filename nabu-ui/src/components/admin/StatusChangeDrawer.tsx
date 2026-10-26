"use client";

import {useEffect, useState} from "react";
import {AlertTriangle, ArrowRight, ExternalLink, Loader2, Shield, ShieldAlert, ShieldCheck,} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {isFailing, severityColor} from "@/lib/monitor-labels";
import {formatRelativeTime} from "@/lib/utils";
import {ScanDetailPanel} from "@/components/admin/ScanDetailPanel";
import {subTypeLabel} from "@/components/dashboard/drawer/utils";
import {WebsiteOverviewLoader} from "@/components/dashboard/WebsiteOverviewLoader";
import {Sheet, SheetClose, SheetContent, SheetHeader, SheetTitle} from "@/components/ui/sheet";

// ── Types ────────────────────────────────────────────────────────────────────

export interface StatusChangeScan {
    scanLogId: string;
    previousScanLogId?: string | null;
    websiteUrl: string;
    monitorType: string;
    subType: string;
    severity: string;
    previousSeverity: string;
    statusChange: string;
    scannedAt: string;
}

interface ScanDetail {
    scanLogId: string;
    websiteId: string;
    severity: string;
    scannedAt: string;
    resultJson: Record<string, unknown> | null;
}

// ── Helpers ──────────────────────────────────────────────────────────────────

function statusChangeLabel(sc: string) {
    const map: Record<string, { label: string; cls: string }> = {
        RECOVERED: {
            label: "Recovered",
            cls: "text-emerald-700 bg-emerald-100 ring-emerald-300 dark:text-emerald-400 dark:bg-emerald-950/60 dark:ring-emerald-500/30"
        },
        IMPROVED: {
            label: "Improved",
            cls: "text-sky-700 bg-sky-100 ring-sky-300 dark:text-sky-400 dark:bg-sky-950/60 dark:ring-sky-500/30"
        },
        REGRESSED_TO_FAILING: {
            label: "Degraded",
            cls: "text-rose-700 bg-rose-100 ring-rose-300 dark:text-rose-400 dark:bg-rose-950/60 dark:ring-rose-500/30"
        },
        REGRESSED: {
            label: "Regressed",
            cls: "text-amber-700 bg-amber-100 ring-amber-300 dark:text-amber-400 dark:bg-amber-950/60 dark:ring-amber-500/30"
        },
    };
    return map[sc] ?? {label: sc, cls: "text-muted-foreground bg-secondary ring-border"};
}

// ── Main component ──────────────────────────────────────────────────────────

interface StatusChangeDrawerProps {
    scan: StatusChangeScan | null;
    onClose: () => void;
}

export function StatusChangeDrawer({scan, onClose}: StatusChangeDrawerProps) {
    const [currentDetail, setCurrentDetail] = useState<ScanDetail | null>(null);
    const [previousDetail, setPreviousDetail] = useState<ScanDetail | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(false);
    const [activeTab, setActiveTab] = useState<"current" | "previous">("current");
    const [viewMode, setViewMode] = useState<"scan" | "site">("scan");

    useEffect(() => {
        if (!scan) {
            setCurrentDetail(null);
            setPreviousDetail(null);
            setError(false);
            setActiveTab("current");
            setViewMode("scan");
            return;
        }
        let cancelled = false;
        setLoading(true);
        setError(false);
        setCurrentDetail(null);
        setPreviousDetail(null);

        const fetchScan = (id: string) =>
            apiClient(`/api/v1/admin/scans/${id}`)
                .then(res => {
                    if (!res.ok) throw new Error("not_ok");
                    return res.json() as Promise<ScanDetail>;
                });

        const promises: Promise<void>[] = [
            fetchScan(scan.scanLogId).then(d => {
                if (!cancelled) setCurrentDetail(d);
            }),
        ];

        if (scan.previousScanLogId) {
            promises.push(
                fetchScan(scan.previousScanLogId).then(d => {
                    if (!cancelled) setPreviousDetail(d);
                }),
            );
        }

        Promise.all(promises)
            .catch(() => {
                if (!cancelled) setError(true);
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });

        return () => {
            cancelled = true;
        };
    }, [scan?.scanLogId, scan?.previousScanLogId]);

    const isOpen = scan !== null;
    const label = scan ? subTypeLabel(scan.subType as Parameters<typeof subTypeLabel>[0]) : "";
    const isSSL = scan?.monitorType === "SSL";
    const scLabel = scan ? statusChangeLabel(scan.statusChange) : null;

    return (
        <Sheet open={isOpen} onOpenChange={(o) => !o && onClose()} panelClassName="lg:w-[52vw]">
            <SheetHeader>
                <div className="flex min-w-0 flex-1 flex-col gap-1">
                    {/* Website URL */}
                    <div className="flex items-center gap-2">
                        <SheetTitle className="truncate">
                            {scan?.websiteUrl.replace(/^https?:\/\//, "") ?? ""}
                        </SheetTitle>
                        {scan && (
                            <a
                                href={scan.websiteUrl}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="shrink-0 text-muted-foreground hover:text-foreground transition-colors"
                            >
                                <ExternalLink className="h-3.5 w-3.5"/>
                            </a>
                        )}
                    </div>

                    {/* Meta row */}
                    {scan && (
                        <div className="flex flex-wrap items-center gap-2">
                            {/* Type badge */}
                            <span
                                className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ${
                                    isSSL
                                        ? "bg-blue-950/70 text-blue-400 ring-blue-900/40"
                                        : "bg-violet-950/70 text-violet-400 ring-violet-900/40"
                                }`}
                            >
                                <Shield className="h-3 w-3"/>
                                {label}
                            </span>

                            {/* Status change badge */}
                            {scLabel && (
                                <span
                                    className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ${scLabel.cls}`}
                                >
                                    {scan.previousSeverity}
                                    <ArrowRight className="h-3 w-3"/>
                                    {scan.severity}
                                    <span className="ml-0.5 opacity-70">{scLabel.label}</span>
                                </span>
                            )}

                            {/* Timestamp */}
                            <span className="text-[11px] tabular-nums text-muted-foreground">
                                {formatRelativeTime(scan.scannedAt)}
                            </span>
                        </div>
                    )}
                </div>

                <SheetClose onClickAction={onClose}/>
            </SheetHeader>

            <SheetContent>
                {loading && (
                    <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
                        <Loader2 className="h-5 w-5 animate-spin"/>
                        <span className="text-sm">Loading scan details…</span>
                    </div>
                )}

                {!loading && error && (
                    <div className="flex flex-col items-center gap-3 py-16 text-center">
                        <AlertTriangle className="h-6 w-6 text-muted-foreground"/>
                        <p className="text-sm text-muted-foreground">Failed to load scan details</p>
                    </div>
                )}

                {!loading && !error && currentDetail && scan && (
                    <>
                        <div className="mb-4 flex rounded-xl border border-border bg-secondary/30 p-0.5">
                            <button
                                onClick={() => setViewMode("scan")}
                                className={`flex flex-1 items-center justify-center rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
                                    viewMode === "scan"
                                        ? "bg-card text-foreground shadow-sm"
                                        : "text-muted-foreground hover:text-foreground"
                                }`}
                            >
                                Scan diff
                            </button>
                            <button
                                onClick={() => setViewMode("site")}
                                className={`flex flex-1 items-center justify-center rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
                                    viewMode === "site"
                                        ? "bg-card text-foreground shadow-sm"
                                        : "text-muted-foreground hover:text-foreground"
                                }`}
                            >
                                Website overview
                            </button>
                        </div>

                        {viewMode === "site" ? (
                            <WebsiteOverviewLoader websiteId={currentDetail.websiteId} onClose={onClose}/>
                        ) : (<>
                        {/* Tab switcher — only show when previous scan exists */}
                        {previousDetail && (
                            <div className="mb-4 flex rounded-xl border border-border bg-secondary/30 p-0.5">
                                <button
                                    onClick={() => setActiveTab("current")}
                                    className={`flex flex-1 items-center justify-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
                                        activeTab === "current"
                                            ? "bg-card text-foreground shadow-sm"
                                            : "text-muted-foreground hover:text-foreground"
                                    }`}
                                >
                                    {isFailing(scan.severity)
                                        ? <ShieldAlert className="h-3 w-3"/>
                                        : <ShieldCheck className="h-3 w-3"/>
                                    }
                                    Current
                                    <span
                                        className={`rounded-full px-1.5 py-0.5 text-[10px] font-bold ring-1 ${severityColor(scan.severity)}`}>
                                        {scan.severity}
                                    </span>
                                    <span className="text-[10px] tabular-nums text-muted-foreground">
                                        {formatRelativeTime(currentDetail.scannedAt)}
                                    </span>
                                </button>
                                <button
                                    onClick={() => setActiveTab("previous")}
                                    className={`flex flex-1 items-center justify-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
                                        activeTab === "previous"
                                            ? "bg-card text-foreground shadow-sm"
                                            : "text-muted-foreground hover:text-foreground"
                                    }`}
                                >
                                    {isFailing(scan.previousSeverity)
                                        ? <ShieldAlert className="h-3 w-3"/>
                                        : <ShieldCheck className="h-3 w-3"/>
                                    }
                                    Previous
                                    <span
                                        className={`rounded-full px-1.5 py-0.5 text-[10px] font-bold ring-1 ${severityColor(scan.previousSeverity)}`}>
                                        {scan.previousSeverity}
                                    </span>
                                    <span className="text-[10px] tabular-nums text-muted-foreground">
                                        {formatRelativeTime(previousDetail.scannedAt)}
                                    </span>
                                </button>
                            </div>
                        )}

                        {/* Active panel */}
                        {activeTab === "current" ? (
                            <ScanDetailPanel
                                resultJson={currentDetail.resultJson}
                                monitorType={scan.monitorType}
                                subType={scan.subType}
                            />
                        ) : previousDetail ? (
                            <ScanDetailPanel
                                resultJson={previousDetail.resultJson}
                                monitorType={scan.monitorType}
                                subType={scan.subType}
                            />
                        ) : null}
                        </>)}
                    </>
                )}
            </SheetContent>
        </Sheet>
    );
}
