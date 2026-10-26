"use client";

import {useCallback, useEffect, useMemo, useState} from "react";
import {
    Activity,
    ChevronDown,
    ChevronLeft,
    ChevronRight,
    ChevronUp,
    Download,
    Loader2,
    RefreshCw,
    Search,
    X
} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {formatRelativeTime} from "@/lib/utils";
import {type GlobalScanItem, ScanResultDrawer} from "@/components/admin/ScanResultDrawer";

// ── Types ──────────────────────────────────────────────────────────────────────

interface HealthRow {
    websiteId: string;
    websiteUrl: string;
    region: string;
    monitorId: string;
    subType: string;
    severity: string;
    scannedAt: string;
    scanLogId: string;
    status: string;
    summary: string;
}

interface HealthPage {
    content: HealthRow[];
    totalElements: number;
    totalPages: number;
    page: number;
    size: number;
}

// ── Sub-type short labels ────────────────────────────────────────────────────

const SUB_TYPE_SHORT: Record<string, string> = {
    SSL: "SSL",
    PRIVACY_POLICY: "PRIV",
    TERMS_OF_SERVICE: "TOS",
    IMPRESSUM: "IMPR",
    COOKIE_POLICY: "CKPL",
    GDPR_REQUEST: "GDPR",
    COOKIE_BANNER: "CKBNR",
    TRACKER_DETECTION: "TRKR",
};

// ── Helpers ──────────────────────────────────────────────────────────────────

function severityBadge(severity: string) {
    const cls =
        severity === "CRITICAL" ? "bg-red-100 dark:bg-red-950/60 text-red-700 dark:text-red-400 ring-red-300/40 dark:ring-red-900/40"
            : severity === "HIGH" ? "bg-orange-100 dark:bg-orange-950/60 text-orange-700 dark:text-orange-400 ring-orange-300/40 dark:ring-orange-900/40"
                : severity === "MEDIUM" ? "bg-amber-100 dark:bg-amber-950/60 text-amber-700 dark:text-amber-400 ring-amber-300/40 dark:ring-amber-900/40"
                    : "bg-green-100 dark:bg-green-950/60 text-green-700 dark:text-green-400 ring-green-300/40 dark:ring-green-900/40";
    return (
        <span className={`inline-flex items-center rounded-full px-1.5 py-0.5 text-[10px] font-semibold ring-1 ${cls}`}>
            {severity}
        </span>
    );
}

function typePill(subType: string) {
    const label = SUB_TYPE_SHORT[subType] ?? subType.slice(0, 5);
    const isSSL = subType === "SSL";
    return (
        <span className={`inline-flex items-center rounded-full px-1.5 py-0.5 text-[10px] font-semibold ring-1 ${
            isSSL
                ? "bg-blue-100 dark:bg-blue-950/60 text-blue-700 dark:text-blue-400 ring-blue-300/40 dark:ring-blue-900/40"
                : "bg-violet-100 dark:bg-violet-950/60 text-violet-700 dark:text-violet-400 ring-violet-300/40 dark:ring-violet-900/40"
        }`}>
            {label}
        </span>
    );
}

function exportCSV(rows: HealthRow[]) {
    const header = "Website,Region,Monitor,Severity,Status,Summary,Scanned At\n";
    const body = rows.map(r =>
        [r.websiteUrl, r.region, r.subType, r.severity, r.status, `"${r.summary}"`, r.scannedAt].join(",")
    ).join("\n");
    const blob = new Blob([header + body], {type: "text/csv"});
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `scan-health-report-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
}

// ── Main component ──────────────────────────────────────────────────────────

const PAGE_SIZE = 30;

export function ScanHealthReport() {
    const [rows, setRows] = useState<HealthRow[]>([]);
    const [totalElements, setTotalElements] = useState(0);
    const [totalPages, setTotalPages] = useState(1);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [search, setSearch] = useState("");
    const [sortAsc, setSortAsc] = useState(false);
    const [selectedScan, setSelectedScan] = useState<GlobalScanItem | null>(null);

    const load = useCallback(async (targetPage: number, silent = false) => {
        if (silent) setRefreshing(true);
        else setLoading(true);
        try {
            const res = await apiClient(`/api/v1/admin/scans/health-report?page=${targetPage}&size=${PAGE_SIZE}`);
            if (!res.ok) return;
            const data: HealthPage = await res.json();
            setRows(data.content);
            setTotalElements(data.totalElements);
            setTotalPages(data.totalPages);
        } catch { /* keep stale */
        } finally {
            setLoading(false);
            setRefreshing(false);
        }
    }, []);

    useEffect(() => {
        load(page);
    }, [page, load]);

    // Client-side filter (within current page)
    const filtered = useMemo(() => {
        let result = rows;
        if (search.trim()) {
            const q = search.toLowerCase();
            result = result.filter(r =>
                r.websiteUrl.toLowerCase().includes(q) ||
                r.subType.toLowerCase().includes(q) ||
                r.summary.toLowerCase().includes(q)
            );
        }
        if (sortAsc) result = [...result].reverse();
        return result;
    }, [rows, search, sortAsc]);

    // Group by website
    const websites = useMemo(() => {
        const map = new Map<string, HealthRow[]>();
        for (const r of filtered) {
            if (!map.has(r.websiteId)) map.set(r.websiteId, []);
            map.get(r.websiteId)!.push(r);
        }
        return map;
    }, [filtered]);

    function handleRowClick(row: HealthRow) {
        setSelectedScan({
            scanLogId: row.scanLogId,
            websiteUrl: row.websiteUrl,
            monitorType: row.subType === "SSL" ? "SSL" : "COMPLIANCE",
            subType: row.subType,
            severity: row.severity,
            scannedAt: row.scannedAt,
        });
    }

    return (
        <>
            <div className="glass-card flex h-full flex-col overflow-hidden">
                {/* Header */}
                <div
                    className="flex shrink-0 flex-col gap-2 border-b border-border bg-secondary/30 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
                    <div className="flex items-center gap-2">
                        <Activity className="h-4 w-4 text-primary"/>
                        <h3 className="text-sm font-semibold tracking-tight">Scan Health Report</h3>
                        {!loading && (
                            <span className="text-xs text-muted-foreground tabular-nums">
                                {totalElements} monitors
                            </span>
                        )}
                    </div>
                    <div className="flex items-center gap-2">
                        <button
                            onClick={() => exportCSV(filtered)}
                            disabled={loading || filtered.length === 0}
                            className="flex items-center gap-1.5 rounded-lg border border-border bg-card px-2.5 py-1 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40"
                        >
                            <Download className="h-3 w-3"/>
                            CSV
                        </button>
                        <button
                            onClick={() => load(page, true)}
                            disabled={loading || refreshing}
                            className="flex items-center gap-1.5 rounded-lg border border-border bg-card px-2.5 py-1 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40"
                        >
                            <RefreshCw className={`h-3 w-3 ${refreshing ? "animate-spin" : ""}`}/>
                            Refresh
                        </button>
                    </div>
                </div>

                {/* Search bar */}
                <div className="shrink-0 border-b border-border px-4 py-2">
                    <div className="relative">
                        <Search
                            className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground"/>
                        <input
                            type="text"
                            value={search}
                            onChange={(e) => setSearch(e.target.value)}
                            placeholder="Filter current page by URL, monitor, or summary…"
                            className={`h-8 w-full rounded-lg border border-border bg-secondary/50 pl-8 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary/40 ${search ? "pr-8" : "pr-3"}`}
                        />
                        {search && (
                            <button
                                onClick={() => setSearch("")}
                                className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground hover:text-foreground hover:bg-secondary"
                            >
                                <X className="h-3.5 w-3.5"/>
                            </button>
                        )}
                    </div>
                </div>

                {/* Table — scrolls within fixed height */}
                <div className="flex-1 overflow-y-auto min-h-0">
                    {loading ? (
                        <div className="flex items-center justify-center py-16">
                            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground"/>
                        </div>
                    ) : filtered.length === 0 ? (
                        <div className="flex flex-col items-center justify-center gap-2 py-16 text-center">
                            <Search className="h-5 w-5 text-muted-foreground"/>
                            <p className="text-sm text-muted-foreground">No scans match your filter</p>
                        </div>
                    ) : (
                        <table className="w-full text-xs">
                            <thead>
                            <tr className="sticky top-0 z-10 border-b border-border bg-card text-left text-[10px] uppercase tracking-wider text-muted-foreground">
                                <th className="px-3 py-2 font-medium">Website</th>
                                <th className="px-3 py-2 font-medium hidden sm:table-cell">Region</th>
                                <th className="px-3 py-2 font-medium">Monitor</th>
                                <th className="px-3 py-2 font-medium">
                                    <button onClick={() => setSortAsc(!sortAsc)}
                                            className="flex items-center gap-1 hover:text-foreground">
                                        Severity
                                        {sortAsc ? <ChevronUp className="h-3 w-3"/> :
                                            <ChevronDown className="h-3 w-3"/>}
                                    </button>
                                </th>
                                <th className="px-3 py-2 font-medium hidden md:table-cell">Finding</th>
                                <th className="px-3 py-2 font-medium hidden sm:table-cell">Scanned</th>
                            </tr>
                            </thead>
                            <tbody>
                            {Array.from(websites.entries()).map(([wsId, wsRows]) =>
                                wsRows.map((row, i) => (
                                    <tr
                                        key={`${wsId}-${row.monitorId}`}
                                        onClick={() => handleRowClick(row)}
                                        className="border-b border-border transition-colors hover:bg-secondary/40 cursor-pointer"
                                    >
                                        <td className="px-3 py-2">
                                            {i === 0 ? (
                                                <span
                                                    className="font-medium text-foreground truncate block max-w-[200px]">
                                                    {row.websiteUrl.replace(/^https?:\/\//, "")}
                                                </span>
                                            ) : (
                                                <span className="text-muted-foreground/30">↳</span>
                                            )}
                                        </td>
                                        <td className="px-3 py-2 hidden sm:table-cell text-muted-foreground">{i === 0 ? row.region : ""}</td>
                                        <td className="px-3 py-2">{typePill(row.subType)}</td>
                                        <td className="px-3 py-2">{severityBadge(row.severity)}</td>
                                        <td className="px-3 py-2 hidden md:table-cell">
                                            <span
                                                className="truncate block max-w-[300px] text-muted-foreground">{row.summary}</span>
                                        </td>
                                        <td className="px-3 py-2 hidden sm:table-cell text-muted-foreground tabular-nums">
                                            {formatRelativeTime(row.scannedAt)}
                                        </td>
                                    </tr>
                                ))
                            )}
                            </tbody>
                        </table>
                    )}
                </div>

                {/* Footer with pagination */}
                <div
                    className="shrink-0 flex items-center justify-between border-t border-border bg-secondary/20 px-4 py-2">
                    <span className="text-[11px] text-muted-foreground tabular-nums">
                        Page {page + 1} of {totalPages} · {totalElements} monitors
                    </span>
                    <div className="flex items-center gap-1">
                        <button
                            onClick={() => setPage(p => Math.max(0, p - 1))}
                            disabled={page === 0 || loading}
                            className="flex items-center gap-1 rounded-lg border border-border bg-card px-2 py-1 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40"
                        >
                            <ChevronLeft className="h-3 w-3"/>
                            Prev
                        </button>
                        <button
                            onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                            disabled={page >= totalPages - 1 || loading}
                            className="flex items-center gap-1 rounded-lg border border-border bg-card px-2 py-1 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40"
                        >
                            Next
                            <ChevronRight className="h-3 w-3"/>
                        </button>
                    </div>
                </div>
            </div>

            <ScanResultDrawer scan={selectedScan} onClose={() => setSelectedScan(null)}/>
        </>
    );
}
