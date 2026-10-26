"use client";

import {Fragment, useCallback, useEffect, useState} from "react";
import {ChevronDown, ChevronLeft, ChevronRight, Flag, RefreshCw} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {extractErrorMessage, formatAdminDate, SEVERITY_STYLES} from "@/lib/utils";
import type {ReportedScanItem, ReportedScanPageResponse} from "@/lib/types";
import {ScanDetailPanel} from "@/components/admin/ScanDetailPanel";

const PAGE_SIZE = 20;

export default function AdminReportsPage() {
    const [response, setResponse] = useState<ReportedScanPageResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [page, setPage] = useState(0);
    const [resolvingId, setResolvingId] = useState<string | null>(null);
    const [expandedId, setExpandedId] = useState<string | null>(null);

    const load = useCallback(async (silent = false, targetPage = page) => {
        if (silent) setRefreshing(true);
        else setLoading(true);
        setError(null);
        try {
            const res = await apiClient(`/api/v1/admin/reports?page=${targetPage}&size=${PAGE_SIZE}`);
            if (!res.ok) throw new Error(await extractErrorMessage(res, `HTTP ${res.status}`));
            setResponse(await res.json());
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to load reports");
        } finally {
            setLoading(false);
            setRefreshing(false);
        }
    }, [page]);

    useEffect(() => {
        load(false, page);
    }, [page]);

    async function handleResolve(scanLogId: string) {
        setResolvingId(scanLogId);
        try {
            const res = await apiClient(`/api/v1/admin/reports/${scanLogId}/resolve`, {method: "POST"});
            if (!res.ok) throw new Error(`Failed to resolve (${res.status})`);
            setExpandedId((prev) => prev === scanLogId ? null : prev);
            setResponse((prev) => prev ? {
                ...prev,
                content: prev.content.filter((item) => item.scanLogId !== scanLogId),
                totalElements: prev.totalElements - 1,
            } : prev);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to resolve report");
        } finally {
            setResolvingId(null);
        }
    }

    const totalPages = response?.totalPages ?? 0;
    const items: ReportedScanItem[] = response?.content ?? [];

    return (
        <div className="space-y-6 py-6">
            {/* Header */}
            <div className="flex items-center justify-between">
                <div>
                    <h1 className="text-xl font-bold text-foreground">Reported Scan Results</h1>
                    <p className="mt-1 text-sm text-muted-foreground">
                        Monitor results flagged by users as incorrect or suspicious.
                    </p>
                </div>
                <button
                    onClick={() => load(true, page)}
                    disabled={refreshing}
                    className="flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground disabled:opacity-40"
                >
                    <RefreshCw className={`h-3.5 w-3.5 ${refreshing ? "animate-spin" : ""}`}/>
                    Refresh
                </button>
            </div>

            {/* Error */}
            {error && (
                <div className="rounded-xl bg-red-950/30 px-4 py-3 text-sm text-red-400 ring-1 ring-red-900/30">
                    {error}
                </div>
            )}

            {/* Loading */}
            {loading && (
                <div className="flex items-center justify-center py-16 text-sm text-muted-foreground">
                    Loading reports...
                </div>
            )}

            {/* Empty state */}
            {!loading && items.length === 0 && (
                <div className="flex flex-col items-center justify-center gap-3 py-16 text-muted-foreground">
                    <Flag className="h-8 w-8 opacity-30"/>
                    <p className="text-sm">No reported scan results</p>
                </div>
            )}

            {/* Table */}
            {!loading && items.length > 0 && (
                <div className="overflow-hidden rounded-xl border border-border bg-card">
                    <div className="overflow-x-auto">
                        <table className="w-full text-sm">
                            <thead>
                            <tr className="border-b border-border bg-muted/30">
                                <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Website</th>
                                <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Org</th>
                                <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Monitor</th>
                                <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Severity</th>
                                <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Reporter</th>
                                <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Notes</th>
                                <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Reported At</th>
                                <th className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">Action</th>
                            </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                            {items.map((item) => (
                                <Fragment key={item.scanLogId}>
                                <tr
                                    className="hover:bg-muted/20 transition-colors cursor-pointer"
                                    onClick={() => setExpandedId(expandedId === item.scanLogId ? null : item.scanLogId)}
                                >
                                    <td className="px-4 py-3">
                                        <div className="flex items-center gap-1.5">
                                            <ChevronDown className={`h-3.5 w-3.5 shrink-0 text-muted-foreground transition-transform ${expandedId === item.scanLogId ? "rotate-180" : ""}`}/>
                                            <a
                                                href={item.websiteUrl}
                                                target="_blank"
                                                rel="noopener noreferrer"
                                                className="max-w-[160px] truncate text-sky-400 hover:text-sky-300 hover:underline"
                                                title={item.websiteUrl}
                                                onClick={(e) => e.stopPropagation()}
                                            >
                                                {item.websiteUrl.replace(/^https?:\/\//, "")}
                                            </a>
                                        </div>
                                    </td>
                                    <td className="px-4 py-3 text-muted-foreground">{item.orgName}</td>
                                    <td className="px-4 py-3 text-foreground">
                                        <span className="block">{item.monitorType}</span>
                                        {item.subType && (
                                            <span className="text-xs text-muted-foreground">{item.subType}</span>
                                        )}
                                    </td>
                                    <td className="px-4 py-3">
                                        <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-medium ${
                                            SEVERITY_STYLES[item.severity] ?? "bg-muted text-muted-foreground border-border"
                                        }`}>
                                            {item.severity}
                                        </span>
                                    </td>
                                    <td className="px-4 py-3 text-muted-foreground text-xs">
                                        {item.reporterEmail ?? <span className="italic opacity-50">deleted user</span>}
                                    </td>
                                    <td className="px-4 py-3 max-w-[240px]">
                                        <p className="text-xs text-foreground line-clamp-2" title={item.notes}>
                                            {item.notes}
                                        </p>
                                    </td>
                                    <td className="px-4 py-3 text-xs text-muted-foreground whitespace-nowrap">
                                        {formatAdminDate(item.reportedAt)}
                                    </td>
                                    <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                                        <button
                                            onClick={() => handleResolve(item.scanLogId)}
                                            disabled={resolvingId === item.scanLogId}
                                            className="rounded-lg bg-emerald-600/20 px-3 py-1.5 text-xs font-medium text-emerald-400 transition-colors hover:bg-emerald-600/30 disabled:opacity-50"
                                        >
                                            {resolvingId === item.scanLogId ? "Resolving..." : "Resolve"}
                                        </button>
                                    </td>
                                </tr>
                                {expandedId === item.scanLogId && (
                                    <tr className="bg-muted/10">
                                        <td colSpan={8} className="px-6 py-4 border-t border-border">
                                            <div className="mb-2 text-xs font-medium text-muted-foreground uppercase tracking-wide">
                                                Scan Result at Time of Report
                                            </div>
                                            <ScanDetailPanel
                                                resultJson={item.resultJson}
                                                monitorType={item.monitorType}
                                                subType={item.subType ?? ""}
                                            />
                                        </td>
                                    </tr>
                                )}
                                </Fragment>
                            ))}
                            </tbody>
                        </table>
                    </div>

                    {/* Pagination */}
                    {totalPages > 1 && (
                        <div className="flex items-center justify-between border-t border-border px-4 py-3">
                            <p className="text-xs text-muted-foreground">
                                Page {page + 1} of {totalPages} · {response?.totalElements} total
                            </p>
                            <div className="flex items-center gap-2">
                                <button
                                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                                    disabled={page === 0}
                                    className="rounded-lg border border-border p-1.5 text-muted-foreground transition-colors hover:bg-secondary disabled:opacity-40"
                                >
                                    <ChevronLeft className="h-4 w-4"/>
                                </button>
                                <button
                                    onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                                    disabled={page >= totalPages - 1}
                                    className="rounded-lg border border-border p-1.5 text-muted-foreground transition-colors hover:bg-secondary disabled:opacity-40"
                                >
                                    <ChevronRight className="h-4 w-4"/>
                                </button>
                            </div>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
