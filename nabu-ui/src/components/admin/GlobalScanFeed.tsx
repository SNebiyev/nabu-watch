"use client";

import {useCallback, useEffect, useState} from "react";
import {Activity, ChevronRight, RotateCcw, ShieldAlert, ShieldCheck} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {formatRelativeTime} from "@/lib/utils";
import {type GlobalScanItem, ScanResultDrawer} from "@/components/admin/ScanResultDrawer";
import {isFailing, SUBTYPE_SHORT} from "@/lib/monitor-labels";

// ── Sub-components ────────────────────────────────────────────────────────────

function StatusBadge({severity}: { severity: string }) {
    if (isFailing(severity)) {
        return (
            <span
                className="inline-flex shrink-0 items-center gap-1 rounded-full bg-red-100 dark:bg-red-950/60 px-1.5 py-0.5 text-[10px] font-semibold text-red-600 dark:text-red-400 ring-1 ring-red-300/40 dark:ring-red-900/40">
                <ShieldAlert className="h-2.5 w-2.5"/>
                FAIL
            </span>
        );
    }
    return (
        <span
            className="inline-flex shrink-0 items-center gap-1 rounded-full bg-green-100 dark:bg-green-950/60 px-1.5 py-0.5 text-[10px] font-semibold text-green-600 dark:text-green-400 ring-1 ring-green-300/40 dark:ring-green-900/40">
            <ShieldCheck className="h-2.5 w-2.5"/>
            OK
        </span>
    );
}

function TypePill({monitorType, subType}: { monitorType: string; subType: string }) {
    const isSSL = monitorType === "SSL";
    const label = SUBTYPE_SHORT[subType] ?? subType.slice(0, 6);
    return (
        <span
            className={`shrink-0 rounded-full px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${
                isSSL
                    ? "bg-blue-100 dark:bg-blue-950/70 text-blue-600 dark:text-blue-400 ring-1 ring-blue-300/40 dark:ring-blue-900/40"
                    : "bg-violet-100 dark:bg-violet-950/70 text-violet-600 dark:text-violet-400 ring-1 ring-violet-300/40 dark:ring-violet-900/40"
            }`}
        >
            {label}
        </span>
    );
}

function FeedRow({item, onClick}: { item: GlobalScanItem; onClick: () => void }) {
    return (
        <button
            onClick={onClick}
            className="flex w-full items-center gap-2.5 border-b border-border last:border-0 px-4 py-2.5 text-left transition-colors hover:bg-secondary/60 active:bg-secondary/80 group"
        >
            <TypePill monitorType={item.monitorType} subType={item.subType}/>
            <span className="min-w-0 flex-1 truncate text-xs font-medium text-foreground">
                {item.websiteUrl.replace(/^https?:\/\//, "")}
            </span>
            <StatusBadge severity={item.severity}/>
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
            {Array.from({length: 8}).map((_, i) => (
                <div key={i} className="flex items-center gap-2.5 py-2.5">
                    <div className="h-4 w-10 shrink-0 rounded-full bg-secondary"/>
                    <div className="h-3 flex-1 rounded-lg bg-secondary"/>
                    <div className="h-4 w-10 shrink-0 rounded-full bg-secondary"/>
                    <div className="h-2.5 w-8 shrink-0 rounded-lg bg-secondary"/>
                </div>
            ))}
        </div>
    );
}

function EmptyFeed() {
    return (
        <div className="flex flex-col items-center justify-center gap-4 py-12 text-center">
            <div className="relative">
                <div className="absolute inset-0 rounded-full bg-sky-500/10 blur-xl"/>
                <div
                    className="relative flex h-12 w-12 items-center justify-center rounded-2xl border border-dashed border-border bg-secondary/30">
                    <Activity className="h-5 w-5 text-muted-foreground"/>
                </div>
            </div>
            <div>
                <p className="text-sm font-medium text-foreground">Waiting for scans…</p>
                <p className="mt-0.5 text-xs text-muted-foreground">
                    Events from all users will appear here
                </p>
            </div>
        </div>
    );
}

// ── Main component ────────────────────────────────────────────────────────────

export function GlobalScanFeed() {
    const [items, setItems] = useState<GlobalScanItem[]>([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
    const [selectedScan, setSelectedScan] = useState<GlobalScanItem | null>(null);

    const fetchFeed = useCallback(async (silent = false) => {
        if (silent) setRefreshing(true);
        else setLoading(true);
        try {
            const res = await apiClient("/api/v1/admin/scans/global");
            if (!res.ok) return;
            const data: GlobalScanItem[] = await res.json();
            setItems(data);
            setLastUpdated(new Date());
        } catch {
            // keep stale data on network error
        } finally {
            setLoading(false);
            setRefreshing(false);
        }
    }, []);

    // Initial load + auto-refresh every 30 seconds
    useEffect(() => {
        fetchFeed();
        const interval = setInterval(() => fetchFeed(true), 30_000);
        return () => clearInterval(interval);
    }, [fetchFeed]);

    const failingCount = items.filter((i) => isFailing(i.severity)).length;

    return (
        <>
            <div className="glass-card flex h-full flex-col overflow-hidden">
                {/* Header */}
                <div
                    className="flex shrink-0 items-center justify-between border-b border-border bg-secondary/30 px-4 py-3">
                    <div className="flex items-center gap-2">
                        <Activity className="h-4 w-4 text-primary"/>
                        <h3 className="text-sm font-semibold tracking-tight">Global Scan Feed</h3>
                        {failingCount > 0 && (
                            <span
                                className="rounded-full bg-red-100 dark:bg-red-950/80 px-1.5 py-0.5 text-[10px] font-bold text-red-600 dark:text-red-400 ring-1 ring-red-300/40 dark:ring-red-900/60">
                                {failingCount} failing
                            </span>
                        )}
                    </div>

                    <div className="flex items-center gap-2">
                        {lastUpdated && !loading && (
                            <span className="text-[11px] tabular-nums text-muted-foreground">
                                {lastUpdated.toLocaleTimeString()}
                            </span>
                        )}
                        <button
                            onClick={() => fetchFeed(true)}
                            disabled={loading || refreshing}
                            className="flex items-center gap-1.5 rounded-lg border border-border bg-card px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground disabled:opacity-40"
                        >
                            <RotateCcw className={`h-3 w-3 ${refreshing ? "animate-spin" : ""}`}/>
                            Refresh
                        </button>
                    </div>
                </div>

                {/* Feed body */}
                <div className="flex-1 overflow-y-auto">
                    {loading ? (
                        <FeedSkeleton/>
                    ) : items.length === 0 ? (
                        <EmptyFeed/>
                    ) : (
                        items.map((item) => (
                            <FeedRow
                                key={item.scanLogId}
                                item={item}
                                onClick={() => setSelectedScan(item)}
                            />
                        ))
                    )}
                </div>

                {/* Footer hint */}
                {!loading && items.length > 0 && (
                    <div
                        className="shrink-0 border-t border-border bg-secondary/20 px-4 py-1.5 text-center text-[10px] text-muted-foreground">
                        Click any row to view scan details · Auto-refreshes every 30s
                    </div>
                )}
            </div>

            {/* Scan detail drawer */}
            <ScanResultDrawer
                scan={selectedScan}
                onClose={() => setSelectedScan(null)}
            />
        </>
    );
}
