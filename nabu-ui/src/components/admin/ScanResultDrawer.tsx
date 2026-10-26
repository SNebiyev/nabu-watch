"use client";

import {useEffect, useState} from "react";
import {AlertTriangle, ExternalLink, Loader2, Shield, ShieldAlert, ShieldCheck} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {isFailing, severityColor} from "@/lib/monitor-labels";
import {formatRelativeTime} from "@/lib/utils";
import {ScanDetailPanel} from "@/components/admin/ScanDetailPanel";
import {subTypeLabel} from "@/components/dashboard/drawer/utils";
import {WebsiteOverviewLoader} from "@/components/dashboard/WebsiteOverviewLoader";
import {Sheet, SheetClose, SheetContent, SheetHeader, SheetTitle} from "@/components/ui/sheet";

// ── Types ──────────────────────────────────────────────────────────────────────

export interface GlobalScanItem {
    scanLogId: string;
    websiteUrl: string;
    monitorType: string;
    subType: string;
    severity: string;
    scannedAt: string;
}

interface AdminScanDetail {
    scanLogId: string;
    monitorId: string;
    websiteId: string;
    websiteUrl: string;
    monitorType: string;
    subType: string;
    severity: string;
    scannedAt: string;
    resultJson: Record<string, unknown> | null;
}

// ── Main Component ─────────────────────────────────────────────────────────────

interface ScanResultDrawerProps {
    scan: GlobalScanItem | null;
    onClose: () => void;
}

export function ScanResultDrawer({scan, onClose}: ScanResultDrawerProps) {
    const [detail, setDetail] = useState<AdminScanDetail | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(false);
    const [activeTab, setActiveTab] = useState<"scan" | "site">("scan");

    useEffect(() => {
        if (!scan) {
            setDetail(null);
            setError(false);
            setActiveTab("scan");
            return;
        }
        let cancelled = false;
        setLoading(true);
        setError(false);
        setDetail(null);

        apiClient(`/api/v1/admin/scans/${scan.scanLogId}`)
            .then(res => {
                if (!res.ok) throw new Error("not_ok");
                return res.json() as Promise<AdminScanDetail>;
            })
            .then(data => {
                if (!cancelled) setDetail(data);
            })
            .catch(() => {
                if (!cancelled) setError(true);
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });

        return () => {
            cancelled = true;
        };
    }, [scan?.scanLogId]);

    const isOpen = scan !== null;
    const label = scan ? subTypeLabel(scan.subType as Parameters<typeof subTypeLabel>[0]) : "";
    const isSSL = scan?.monitorType === "SSL";

    return (
        <Sheet open={isOpen} onOpenChange={(o) => !o && onClose()} panelClassName="lg:w-[50vw]">
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

                            {/* Severity badge */}
                            <span
                                className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ${severityColor(scan.severity)}`}
                            >
                                {isFailing(scan.severity)
                                    ? <ShieldAlert className="h-3 w-3"/>
                                    : <ShieldCheck className="h-3 w-3"/>
                                }
                                {scan.severity}
                            </span>

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

                {!loading && !error && detail && (
                    <>
                        <div className="mb-4 flex rounded-xl border border-border bg-secondary/30 p-0.5">
                            <button
                                onClick={() => setActiveTab("scan")}
                                className={`flex flex-1 items-center justify-center rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
                                    activeTab === "scan"
                                        ? "bg-card text-foreground shadow-sm"
                                        : "text-muted-foreground hover:text-foreground"
                                }`}
                            >
                                Scan detail
                            </button>
                            <button
                                onClick={() => setActiveTab("site")}
                                className={`flex flex-1 items-center justify-center rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
                                    activeTab === "site"
                                        ? "bg-card text-foreground shadow-sm"
                                        : "text-muted-foreground hover:text-foreground"
                                }`}
                            >
                                Website overview
                            </button>
                        </div>
                        {activeTab === "scan" ? (
                            <ScanDetailPanel
                                resultJson={detail.resultJson}
                                monitorType={detail.monitorType}
                                subType={detail.subType}
                            />
                        ) : (
                            <WebsiteOverviewLoader websiteId={detail.websiteId} onClose={onClose}/>
                        )}
                    </>
                )}
            </SheetContent>
        </Sheet>
    );
}
