"use client";

import {useCallback, useEffect, useMemo, useState} from "react";
import {
    AlertTriangle,
    ArrowDownRight,
    ArrowUpRight,
    ChevronRight,
    GitCompareArrows,
    RotateCcw,
    ShieldOff,
} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {formatRelativeTime} from "@/lib/utils";
import {StatusChangeDrawer, type StatusChangeScan} from "@/components/admin/StatusChangeDrawer";
import {SUBTYPE_SHORT} from "@/lib/monitor-labels";

// ── Types ────────────────────────────────────────────────────────────────────

interface StatusChangeEntry {
    scanLogId: string;
    monitorId: string;
    websiteId: string;
    websiteUrl: string;
    monitorType: string;
    subType: string;
    severity: string;
    previousSeverity: string;
    statusChange: string;
    scannedAt: string;
    orgName?: string;
    previousScanLogId?: string | null;
}

type StatusChangeType = "RECOVERED" | "IMPROVED" | "REGRESSED_TO_FAILING" | "REGRESSED";

// ── Helpers ──────────────────────────────────────────────────────────────────

const STATUS_CFG: Record<StatusChangeType, {
    label: string;
    Icon: typeof ArrowUpRight;
    cls: string;
    ringCls: string;
}> = {
    RECOVERED: {
        label: "Recovered",
        Icon: ArrowUpRight,
        cls: "bg-emerald-100 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-300",
        ringCls: "ring-emerald-300/40 dark:ring-emerald-500/30",
    },
    IMPROVED: {
        label: "Improved",
        Icon: ArrowUpRight,
        cls: "bg-sky-100 text-sky-700 dark:bg-sky-950/60 dark:text-sky-300",
        ringCls: "ring-sky-300/40 dark:ring-sky-500/30",
    },
    REGRESSED_TO_FAILING: {
        label: "Degraded",
        Icon: ArrowDownRight,
        cls: "bg-rose-100 text-rose-700 dark:bg-rose-950/60 dark:text-rose-300",
        ringCls: "ring-rose-300/40 dark:ring-rose-500/30",
    },
    REGRESSED: {
        label: "Regressed",
        Icon: ArrowDownRight,
        cls: "bg-amber-100 text-amber-700 dark:bg-amber-950/60 dark:text-amber-300",
        ringCls: "ring-amber-300/40 dark:ring-amber-500/30",
    },
};

function isPositive(sc: string) {
    return sc === "RECOVERED" || sc === "IMPROVED";
}

// ── Sub-components ───────────────────────────────────────────────────────────

function StatusBadge({statusChange}: { statusChange: string }) {
    const cfg = STATUS_CFG[statusChange as StatusChangeType];
    if (!cfg) return null;
    const {Icon} = cfg;
    return (
        <span
            className={`inline-flex shrink-0 items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[10px] font-semibold ring-1 ring-inset ${cfg.cls} ${cfg.ringCls}`}
        >
            <Icon className="h-2.5 w-2.5"/>
            {cfg.label}
        </span>
    );
}

function SeverityTransition({from, to}: { from: string; to: string }) {
    return (
        <span className="text-[10px] tabular-nums text-muted-foreground">
            {from} <span className="text-muted-foreground/50">-&gt;</span> {to}
        </span>
    );
}

function TypePill({subType}: { subType: string }) {
    const label = SUBTYPE_SHORT[subType] ?? subType.slice(0, 6);
    return (
        <span
            className="shrink-0 rounded-full bg-secondary px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
            {label}
        </span>
    );
}

function FeedRow({item, onClick}: { item: StatusChangeEntry; onClick: () => void }) {
    return (
        <button
            onClick={onClick}
            className="flex w-full items-center gap-2.5 border-b border-border last:border-0 px-4 py-2.5 text-left transition-colors hover:bg-secondary/60 active:bg-secondary/80 group"
        >
            <StatusBadge statusChange={item.statusChange}/>
            <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                    <span className="truncate text-xs font-medium text-foreground">
                        {item.websiteUrl.replace(/^https?:\/\//, "")}
                    </span>
                    <TypePill subType={item.subType}/>
                </div>
                <div className="mt-0.5 flex items-center gap-2">
                    <SeverityTransition from={item.previousSeverity} to={item.severity}/>
                    {item.orgName && (
                        <span className="truncate text-[10px] text-muted-foreground/60">
                            {item.orgName}
                        </span>
                    )}
                </div>
            </div>
            <span className="shrink-0 text-[11px] tabular-nums text-muted-foreground">
                {formatRelativeTime(item.scannedAt)}
            </span>
            <ChevronRight
                className="h-3.5 w-3.5 shrink-0 text-muted-foreground/40 transition-colors group-hover:text-muted-foreground"/>
        </button>
    );
}

function FeedSkeleton() {
    return (
        <div className="animate-pulse px-3 py-1">
            {Array.from({length: 6}).map((_, i) => (
                <div key={i} className="flex items-center gap-2.5 py-2.5">
                    <div className="h-4 w-16 shrink-0 rounded-full bg-secondary"/>
                    <div className="h-3 flex-1 rounded-lg bg-secondary"/>
                    <div className="h-2.5 w-8 shrink-0 rounded-lg bg-secondary"/>
                </div>
            ))}
        </div>
    );
}

// ── Main component ───────────────────────────────────────────────────────────

type LoadState = "loading" | "ok" | "empty" | "denied" | "error";

export function StatusChangeFeed() {
    const [items, setItems] = useState<StatusChangeEntry[]>([]);
    const [state, setState] = useState<LoadState>("loading");
    const [refreshing, setRefreshing] = useState(false);
    const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
    const [selectedScan, setSelectedScan] = useState<StatusChangeScan | null>(null);

    const load = useCallback(async (silent = false) => {
        if (silent) setRefreshing(true);
        else setState("loading");

        try {
            const res = await apiClient("/api/v1/admin/scans/status-changes", {
                skipAuthRedirect: true,
            });

            if (res.status === 403) {
                setState("denied");
                return;
            }
            if (!res.ok) {
                setState("error");
                return;
            }

            const data: StatusChangeEntry[] = await res.json();
            setItems(data);
            setState(data.length === 0 ? "empty" : "ok");
            setLastUpdated(new Date());
        } catch {
            if (!silent) setState("error");
        } finally {
            setRefreshing(false);
        }
    }, []);

    useEffect(() => {
        load();
        const interval = setInterval(() => load(true), 60_000);
        return () => clearInterval(interval);
    }, [load]);

    const positiveCount = useMemo(() => items.filter(i => isPositive(i.statusChange)).length, [items]);
    const negativeCount = items.length - positiveCount;

    return (
        <>
            <div className="glass-card flex h-full flex-col overflow-hidden">
                {/* Header */}
                <div className="flex shrink-0 items-center gap-2 border-b border-border bg-secondary/30 px-4 py-3">
                    <GitCompareArrows className="h-4 w-4 text-primary"/>
                    <h3 className="text-sm font-semibold tracking-tight">Status Changes</h3>

                    {state === "ok" && items.length > 0 && (
                        <div className="flex items-center gap-1">
                            {positiveCount > 0 && (
                                <span
                                    className="rounded-full bg-emerald-100 dark:bg-emerald-950/80 px-1.5 py-0.5 text-[10px] font-bold text-emerald-600 dark:text-emerald-400 ring-1 ring-emerald-300/40 dark:ring-emerald-900/60">
                                    {positiveCount}
                                </span>
                            )}
                            {negativeCount > 0 && (
                                <span
                                    className="rounded-full bg-rose-100 dark:bg-rose-950/80 px-1.5 py-0.5 text-[10px] font-bold text-rose-600 dark:text-rose-400 ring-1 ring-rose-300/40 dark:ring-rose-900/60">
                                    {negativeCount}
                                </span>
                            )}
                        </div>
                    )}

                    <div className="ml-auto flex items-center gap-2">
                        {lastUpdated && state === "ok" && (
                            <span className="text-[11px] tabular-nums text-muted-foreground">
                                {lastUpdated.toLocaleTimeString()}
                            </span>
                        )}
                        <button
                            onClick={() => load(true)}
                            disabled={state === "loading" || refreshing || state === "denied"}
                            className="flex items-center gap-1.5 rounded-lg border border-border bg-card px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground disabled:opacity-40"
                        >
                            <RotateCcw className={`h-3 w-3 ${refreshing ? "animate-spin" : ""}`}/>
                            Refresh
                        </button>
                    </div>
                </div>

                {/* Body */}
                <div className="flex-1 overflow-y-auto">
                    {state === "loading" ? (
                        <FeedSkeleton/>
                    ) : state === "denied" ? (
                        <div className="flex flex-col items-center justify-center gap-4 px-4 py-12 text-center">
                            <div className="relative">
                                <div className="absolute inset-0 rounded-full bg-red-500/10 blur-xl"/>
                                <div
                                    className="relative flex h-12 w-12 items-center justify-center rounded-2xl border border-red-200 bg-red-50 dark:border-red-900/40 dark:bg-red-950/20">
                                    <ShieldOff className="h-5 w-5 text-red-400"/>
                                </div>
                            </div>
                            <div className="space-y-1">
                                <p className="text-sm font-semibold text-foreground">Access Denied</p>
                                <p className="max-w-xs text-xs text-muted-foreground">
                                    Your account does not have permission to view this data.
                                </p>
                            </div>
                        </div>
                    ) : state === "empty" ? (
                        <div className="flex flex-col items-center justify-center gap-4 px-4 py-12 text-center">
                            <div className="relative">
                                <div className="absolute inset-0 rounded-full bg-sky-500/10 blur-xl"/>
                                <div
                                    className="relative flex h-12 w-12 items-center justify-center rounded-2xl border border-dashed border-border bg-secondary/30">
                                    <GitCompareArrows className="h-5 w-5 text-muted-foreground"/>
                                </div>
                            </div>
                            <div className="space-y-1">
                                <p className="text-sm font-semibold text-foreground">No changes yet</p>
                                <p className="text-xs text-muted-foreground">
                                    Monitor status transitions will appear here
                                </p>
                            </div>
                        </div>
                    ) : state === "error" ? (
                        <div className="flex flex-col items-center justify-center gap-3 px-4 py-12 text-center">
                            <AlertTriangle className="h-5 w-5 text-muted-foreground"/>
                            <p className="text-xs text-muted-foreground">Failed to load status changes</p>
                            <button
                                onClick={() => load()}
                                className="rounded-lg border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
                            >
                                Try again
                            </button>
                        </div>
                    ) : (
                        items.map((item) => (
                            <FeedRow
                                key={item.scanLogId}
                                item={item}
                                onClick={() => setSelectedScan({
                                    scanLogId: item.scanLogId,
                                    previousScanLogId: item.previousScanLogId,
                                    websiteUrl: item.websiteUrl,
                                    monitorType: item.monitorType,
                                    subType: item.subType,
                                    severity: item.severity,
                                    previousSeverity: item.previousSeverity,
                                    statusChange: item.statusChange,
                                    scannedAt: item.scannedAt,
                                })}
                            />
                        ))
                    )}
                </div>

                {/* Footer */}
                {state === "ok" && items.length > 0 && (
                    <div
                        className="shrink-0 border-t border-border bg-secondary/20 px-4 py-1.5 text-center text-[10px] text-muted-foreground">
                        Click any row to view scan details · Auto-refreshes every 60s
                    </div>
                )}
            </div>

            <StatusChangeDrawer
                scan={selectedScan}
                onClose={() => setSelectedScan(null)}
            />
        </>
    );
}
