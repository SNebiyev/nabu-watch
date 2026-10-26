"use client";

import { useCallback, useEffect, useState } from "react";
import { AlertTriangle, CheckCircle2, Clock, RefreshCw, ShieldCheck, XCircle } from "lucide-react";
import { apiClient } from "@/lib/apiClient";
import { type AbuseStatus, ABUSE_STATUS_CHIP } from "@/lib/abuseStatus";

type Entry = {
    id: string;
    websiteId: string;
    websiteUrl: string;
    websiteStatus: string;
    reporterEmail: string;
    message: string;
    status: AbuseStatus;
    createdAt: string;
    proofDeadlineAt: string;
    resolvedAt: string | null;
};

type PageResp = {
    items: Entry[];
    totalElements: number;
    page: number;
    totalPages: number;
};

const FILTERS: { value: AbuseStatus | ""; label: string }[] = [
    { value: "", label: "All" },
    { value: "PENDING", label: "Pending" },
    { value: "UPHELD", label: "Upheld" },
    { value: "OWNERSHIP_PROVEN", label: "Owner proved" },
    { value: "SITE_REMOVED", label: "Site removed" },
    { value: "DISMISSED", label: "Dismissed" },
];

export default function AdminAbusePage() {
    const [data, setData] = useState<PageResp | null>(null);
    const [filter, setFilter] = useState<AbuseStatus | "">("PENDING");
    const [loading, setLoading] = useState(true);
    const [actionId, setActionId] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const qs = filter ? `?status=${filter}` : "";
            const res = await apiClient(`/api/v1/admin/abuse/reports${qs}`);
            if (!res.ok) throw new Error(`load failed (${res.status})`);
            setData(await res.json());
        } catch (e) {
            setError(e instanceof Error ? e.message : "load failed");
        } finally {
            setLoading(false);
        }
    }, [filter]);

    useEffect(() => {
        load();
    }, [load]);

    async function act(id: string, verb: "uphold" | "dismiss" | "ownership-proven") {
        setActionId(id);
        try {
            const res = await apiClient(`/api/v1/admin/abuse/reports/${id}/${verb}`, {
                method: "POST",
            });
            if (!res.ok) {
                const detail = (await res.json().catch(() => null))?.detail ?? `action failed (${res.status})`;
                throw new Error(detail);
            }
            await load();
        } catch (e) {
            setError(e instanceof Error ? e.message : "action failed");
        } finally {
            setActionId(null);
        }
    }

    return (
        <div className="space-y-6 py-6">
            <div className="flex items-center justify-between">
                <div>
                    <h1 className="text-xl font-bold text-foreground">Abuse reports</h1>
                    <p className="mt-1 text-sm text-muted-foreground">
                        Triage third-party complaints about monitored sites. Uphold pauses the
                        site and starts a 48-hour ownership-proof clock; unresolved reports are
                        auto-removed when the deadline passes.
                    </p>
                </div>
                <button
                    onClick={load}
                    disabled={loading}
                    className="flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40"
                >
                    <RefreshCw className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} />
                    Refresh
                </button>
            </div>

            <div className="flex flex-wrap gap-2">
                {FILTERS.map((f) => (
                    <button
                        key={f.value || "all"}
                        onClick={() => setFilter(f.value)}
                        className={`rounded-full px-3 py-1 text-xs font-medium ring-1 transition-colors ${
                            filter === f.value
                                ? "bg-primary text-primary-foreground ring-primary"
                                : "bg-card text-muted-foreground ring-border hover:text-foreground"
                        }`}
                    >
                        {f.label}
                    </button>
                ))}
            </div>

            {error && (
                <p className="flex items-center gap-2 text-sm text-destructive">
                    <AlertTriangle className="h-4 w-4" /> {error}
                </p>
            )}

            {!data || data.items.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                    {loading ? "Loading…" : "No reports in this view."}
                </p>
            ) : (
                <ul className="space-y-3">
                    {data.items.map((r) => (
                        <li
                            key={r.id}
                            className="rounded-xl border border-border bg-card p-5 space-y-3"
                        >
                            <div className="flex flex-wrap items-start gap-3">
                                <div className="min-w-0 flex-1">
                                    <div className="flex items-center gap-2">
                                        <span
                                            className={`rounded-full px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wider ring-1 ${ABUSE_STATUS_CHIP[r.status]}`}
                                        >
                                            {r.status}
                                        </span>
                                        <span className="text-xs text-muted-foreground">
                                            Site: {r.websiteStatus}
                                        </span>
                                    </div>
                                    <p className="mt-1 font-mono text-sm text-foreground break-all">
                                        {r.websiteUrl}
                                    </p>
                                    <p className="mt-0.5 text-xs text-muted-foreground">
                                        Filed by {r.reporterEmail} on{" "}
                                        {new Date(r.createdAt).toLocaleString()}
                                    </p>
                                </div>
                                {r.status === "UPHELD" && (
                                    <div className="flex items-center gap-1 text-xs text-orange-400">
                                        <Clock className="h-3.5 w-3.5" />
                                        Deadline {new Date(r.proofDeadlineAt).toLocaleString()}
                                    </div>
                                )}
                            </div>

                            <p className="whitespace-pre-wrap text-sm text-foreground">
                                {r.message}
                            </p>

                            {(r.status === "PENDING" || r.status === "UPHELD") && (
                                <div className="flex flex-wrap gap-2 pt-1">
                                    {r.status === "PENDING" && (
                                        <>
                                            <button
                                                disabled={actionId === r.id}
                                                onClick={() => act(r.id, "uphold")}
                                                className="inline-flex items-center gap-1.5 rounded-md bg-orange-500/90 px-3 py-1.5 text-sm font-medium text-white hover:bg-orange-500 disabled:opacity-50"
                                            >
                                                <AlertTriangle className="h-4 w-4" /> Uphold (pause site)
                                            </button>
                                            <button
                                                disabled={actionId === r.id}
                                                onClick={() => act(r.id, "dismiss")}
                                                className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-sm font-medium text-foreground hover:bg-accent disabled:opacity-50"
                                            >
                                                <XCircle className="h-4 w-4" /> Dismiss
                                            </button>
                                        </>
                                    )}
                                    {r.status === "UPHELD" && (
                                        <button
                                            disabled={actionId === r.id}
                                            onClick={() => act(r.id, "ownership-proven")}
                                            className="inline-flex items-center gap-1.5 rounded-md bg-emerald-500/90 px-3 py-1.5 text-sm font-medium text-white hover:bg-emerald-500 disabled:opacity-50"
                                        >
                                            <ShieldCheck className="h-4 w-4" /> Owner proved — reactivate
                                        </button>
                                    )}
                                </div>
                            )}

                            {r.resolvedAt && (
                                <p className="flex items-center gap-1 text-xs text-muted-foreground">
                                    <CheckCircle2 className="h-3.5 w-3.5" /> Resolved{" "}
                                    {new Date(r.resolvedAt).toLocaleString()}
                                </p>
                            )}
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}
