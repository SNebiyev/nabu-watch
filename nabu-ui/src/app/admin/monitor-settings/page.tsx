"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {AlertCircle, CheckCircle2, Clock, Info, Loader2, RefreshCw, Settings2} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import type {IntervalConfigResponse, MonitorSubType} from "@/lib/types";
import {SUB_TYPE_LABELS} from "@/components/dashboard/drawer/utils";

// ── Constants ──────────────────────────────────────────────────────────────────

const COMPLIANCE_SUB_TYPES: MonitorSubType[] = [
    "PRIVACY_POLICY", "TERMS_OF_SERVICE", "IMPRESSUM",
    "COOKIE_POLICY", "GDPR_REQUEST", "COOKIE_BANNER", "TRACKER_DETECTION",
];

const ALL_SUB_TYPES: (MonitorSubType | string)[] = [
    "SSL", "PRIVACY_POLICY", "TERMS_OF_SERVICE", "IMPRESSUM",
    "COOKIE_POLICY", "GDPR_REQUEST", "COOKIE_BANNER", "TRACKER_DETECTION",
];

const KNOWN_REGIONS = ["DE", "AT", "CH", "FR", "GB", "IT", "NL", "ES", "PL", "BR", "CA", "GLOBAL"];

// ── Sync types ─────────────────────────────────────────────────────────────────

interface SyncResult {
    websitesScanned: number;
    monitorsCreated: number;
    dryRun: boolean;
    createdBySubType: Record<string, number>;
}

// ── Interval input cell ────────────────────────────────────────────────────────

function IntervalCell({
                          subTypeCode,
                          value,
                          isSSL,
                          onSave,
                          saving,
                      }: {
    subTypeCode: string;
    value: number;
    isSSL: boolean;
    onSave: (subTypeCode: string, minutes: number) => Promise<void>;
    saving: boolean;
}) {
    const [local, setLocal] = useState(String(value));
    const [dirty, setDirty] = useState(false);
    const inputRef = useRef<HTMLInputElement>(null);

    // Sync when parent value changes (e.g. after save round-trip)
    useEffect(() => {
        if (!dirty) setLocal(String(value));
    }, [value, dirty]);

    function handleChange(v: string) {
        setLocal(v);
        setDirty(v !== String(value));
    }

    async function handleBlurOrEnter() {
        const parsed = parseInt(local, 10);
        if (!dirty || isNaN(parsed) || parsed < 1 || parsed === value) {
            setLocal(String(value));
            setDirty(false);
            return;
        }
        await onSave(subTypeCode, parsed);
        setDirty(false);
    }

    if (isSSL) {
        return (
            <td className="px-3 py-3 text-center">
                <span className="text-xs text-muted-foreground tabular-nums">1440m</span>
                <span className="ml-1 text-[10px] text-muted-foreground/40">(fixed)</span>
            </td>
        );
    }

    return (
        <td className="px-3 py-3 text-center">
            <div className="inline-flex items-center gap-1">
                <input
                    ref={inputRef}
                    type="number"
                    min={1}
                    disabled={saving}
                    value={local}
                    onChange={e => handleChange(e.target.value)}
                    onBlur={handleBlurOrEnter}
                    onKeyDown={e => e.key === "Enter" && e.currentTarget.blur()}
                    className={`w-16 rounded-lg border px-2 py-1 text-center text-xs tabular-nums transition-colors focus:outline-none focus:ring-1 disabled:opacity-40
                        ${dirty
                        ? "border-amber-400 bg-amber-50/40 text-amber-700 focus:ring-amber-400/30 dark:bg-amber-950/20 dark:text-amber-300"
                        : "border-border bg-background text-foreground focus:ring-primary/20"
                    }`}
                />
                {saving && <Loader2 className="h-3 w-3 animate-spin text-muted-foreground"/>}
                {dirty && !saving && (
                    <span className="h-1.5 w-1.5 rounded-full bg-amber-400"
                          title="Unsaved — press Enter or click away"/>
                )}
            </div>
            <span className="ml-1 text-[10px] text-muted-foreground">m</span>
        </td>
    );
}

// ── Page ───────────────────────────────────────────────────────────────────────

export default function MonitorSettingsPage() {

    // ── Interval config ────────────────────────────────────────────────────────
    const [intervals, setIntervals] = useState<IntervalConfigResponse[]>([]);
    const [loadingIntervals, setLoadingIntervals] = useState(true);
    const [intervalError, setIntervalError] = useState<string | null>(null);
    const [savingInterval, setSavingInterval] = useState<string | null>(null); // subTypeCode

    const fetchData = useCallback(() => {
        setLoadingIntervals(true);
        apiClient("/api/v1/admin/monitor-settings/intervals")
            .then(r => r.ok ? r.json() as Promise<IntervalConfigResponse[]> : Promise.reject(new Error(`HTTP ${r.status}`)))
            .then(setIntervals)
            .catch(e => setIntervalError(e instanceof Error ? e.message : "Failed to load"))
            .finally(() => setLoadingIntervals(false));
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    // ── Interval save handler ─────────────────────────────────────────────────
    async function handleSaveInterval(subTypeCode: string, minutes: number) {
        setSavingInterval(subTypeCode);
        setIntervalError(null);
        try {
            const res = await apiClient("/api/v1/admin/monitor-settings/intervals", {
                method: "PUT",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({subTypeCode, intervalMinutes: minutes}),
            });
            if (!res.ok) throw new Error(await res.text() || `HTTP ${res.status}`);
            const updated: IntervalConfigResponse = await res.json();
            setIntervals(prev => prev.map(c =>
                c.subTypeCode === updated.subTypeCode ? updated : c));
        } catch (e) {
            setIntervalError(e instanceof Error ? e.message : "Failed to save interval");
        } finally {
            setSavingInterval(null);
        }
    }

    // ── Sync ──────────────────────────────────────────────────────────────────
    const [selectedRegions, setSelectedRegions] = useState<Set<string>>(new Set());
    const [selectedSubTypes, setSelectedSubTypes] = useState<Set<string>>(new Set());
    const [syncing, setSyncing] = useState(false);
    const [syncResult, setSyncResult] = useState<SyncResult | null>(null);
    const [syncError, setSyncError] = useState<string | null>(null);

    function toggleRegion(code: string) {
        setSelectedRegions(prev => {
            const next = new Set(prev);
            if (next.has(code)) next.delete(code); else next.add(code);
            return next;
        });
    }

    function toggleSubType(st: string) {
        setSelectedSubTypes(prev => {
            const next = new Set(prev);
            if (next.has(st)) next.delete(st); else next.add(st);
            return next;
        });
    }

    async function runSync(dryRun: boolean) {
        setSyncing(true);
        setSyncError(null);
        setSyncResult(null);
        try {
            const params = new URLSearchParams({dryRun: String(dryRun)});
            selectedRegions.forEach(r => params.append("regionCodes", r));
            selectedSubTypes.forEach(st => params.append("subTypes", st));

            const res = await apiClient(`/api/v1/admin/monitors/sync?${params}`, {method: "POST"});
            if (!res.ok) {
                setSyncError(await res.text() || `Error ${res.status}`);
                return;
            }
            setSyncResult(await res.json());
        } catch (e) {
            setSyncError(e instanceof Error ? e.message : "Network error");
        } finally {
            setSyncing(false);
        }
    }

    const hasFilters = selectedRegions.size > 0 || selectedSubTypes.size > 0;

    // ── Interval grid helpers ─────────────────────────────────────────────────
    function getInterval(subTypeCode: string): number {
        return intervals.find(c => c.subTypeCode === subTypeCode)
            ?.autoScanIntervalMinutes ?? 1440;
    }

    return (
        <div className="pb-8">
            {/* ── Header ──────────────────────────────────────────────────────── */}
            <div className="pb-2 pt-4">
                <h1 className="text-lg font-bold tracking-tight text-foreground flex items-center gap-2">
                    <Settings2 className="h-5 w-5 text-amber-500"/>
                    Monitor Settings
                </h1>
                <p className="text-xs text-muted-foreground mt-0.5">
                    Control automated scan intervals and sync missing monitors.
                </p>
            </div>

            {/* ── Scan Intervals Grid ──────────────────────────────────────────── */}
            <section className="mt-8">
                <div className="flex items-center gap-2 mb-1">
                    <h2 className="text-sm font-semibold text-foreground flex items-center gap-1.5">
                        <Clock className="h-4 w-4 text-amber-500"/>
                        Automated Scan Intervals
                    </h2>
                </div>
                <p className="text-xs text-muted-foreground mb-3">
                    Minutes between scheduled scans per monitor type. Changes propagate immediately to all
                    active monitors. SSL is fixed at 1440 min (24 h).
                </p>

                {loadingIntervals ? (
                    <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
                        <Loader2 className="h-4 w-4 animate-spin"/> Loading interval config…
                    </div>
                ) : intervalError ? (
                    <div
                        className="flex items-start gap-2 rounded-2xl border border-red-200 bg-red-50/60 px-4 py-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/20 dark:text-red-400">
                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0"/>
                        {intervalError}
                    </div>
                ) : (
                    <div className="rounded-2xl border border-border overflow-x-auto">
                        <table className="w-full text-sm">
                            <thead>
                            <tr className="border-b border-border bg-secondary/30">
                                <th className="px-4 py-2.5 text-left text-xs font-semibold text-muted-foreground uppercase tracking-wide">
                                    Monitor Type
                                </th>
                                <th className="px-3 py-2.5 text-center w-28 text-xs font-semibold text-muted-foreground uppercase tracking-wide">
                                    Interval
                                </th>
                            </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                            {ALL_SUB_TYPES.map(st => {
                                const isSSL = st === "SSL";
                                const label = SUB_TYPE_LABELS[st as MonitorSubType] ?? st;
                                return (
                                    <tr key={st} className="hover:bg-secondary/20 transition-colors">
                                        <td className="px-4 py-3">
                                            <span className="font-medium text-foreground text-sm">{label}</span>
                                            <span
                                                className="ml-2 text-[10px] font-mono text-muted-foreground/50 hidden sm:inline">{st}</span>
                                        </td>
                                        <IntervalCell
                                            subTypeCode={st}
                                            value={getInterval(st)}
                                            isSSL={isSSL}
                                            onSave={handleSaveInterval}
                                            saving={savingInterval === st}
                                        />
                                    </tr>
                                );
                            })}
                            </tbody>
                        </table>
                    </div>
                )}
                <p className="mt-2 text-[11px] text-muted-foreground">
                    Edit a cell and press <kbd
                    className="rounded border border-border bg-secondary px-1 py-0.5 font-mono text-[10px]">Enter</kbd> or
                    click away to save.
                </p>
            </section>

            {/* ── Monitor Sync ─────────────────────────────────────────────────── */}
            <section className="mt-8">
                <div className="mb-3">
                    <h2 className="text-sm font-semibold text-foreground flex items-center gap-2">
                        <RefreshCw className="h-4 w-4 text-amber-500"/>
                        Monitor Sync
                    </h2>
                    <p className="text-xs text-muted-foreground mt-0.5">
                        Create compliance monitors missing from existing websites. Run after adding new monitor types.
                    </p>
                </div>

                {/* Info banner */}
                <div
                    className="flex gap-2.5 rounded-2xl border border-sky-200 bg-sky-50/60 px-4 py-3 text-sm text-sky-800 dark:border-sky-800/40 dark:bg-sky-950/20 dark:text-sky-300">
                    <Info className="mt-0.5 h-4 w-4 shrink-0"/>
                    <p className="text-xs leading-relaxed opacity-80">
                        Use <span className="font-medium">Preview</span> to see what would be created without making
                        changes,
                        then <span className="font-medium">Apply</span> to persist. New monitors are picked up by the
                        scheduler on the next cycle.
                    </p>
                </div>

                {/* Filters */}
                <div className="mt-3 rounded-2xl border border-border bg-secondary/20 p-4 space-y-4">
                    <div className="flex items-center justify-between">
                        <p className="text-sm font-medium text-foreground">
                            Filters
                            <span className="ml-1.5 text-xs font-normal text-muted-foreground">(leave all unselected to sync everything)</span>
                        </p>
                        {hasFilters && (
                            <button
                                onClick={() => {
                                    setSelectedRegions(new Set());
                                    setSelectedSubTypes(new Set());
                                }}
                                className="text-xs text-muted-foreground hover:text-foreground transition-colors"
                            >
                                Clear all
                            </button>
                        )}
                    </div>

                    {/* Region pills */}
                    <div>
                        <p className="text-xs font-medium text-muted-foreground mb-2">Regions</p>
                        <div className="flex flex-wrap gap-1.5">
                            <button
                                onClick={() => setSelectedRegions(new Set())}
                                className={`inline-flex items-center rounded-full px-2.5 py-1 text-xs font-medium border transition-colors ${
                                    selectedRegions.size === 0
                                        ? "border-amber-400 bg-amber-500/10 text-amber-700 dark:text-amber-300"
                                        : "border-border text-muted-foreground hover:border-amber-400/60"
                                }`}
                            >All regions
                            </button>
                            {KNOWN_REGIONS.map(r => {
                                const checked = selectedRegions.has(r);
                                return (
                                    <button key={r} onClick={() => toggleRegion(r)}
                                            className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium border transition-colors ${
                                                checked
                                                    ? "border-amber-400 bg-amber-500/10 text-amber-700 dark:text-amber-300"
                                                    : "border-border text-muted-foreground hover:border-amber-400/60"
                                            }`}
                                    >
                                        <span
                                            className={`h-1.5 w-1.5 rounded-full ${checked ? "bg-amber-500" : "bg-muted-foreground/30"}`}/>
                                        {r}
                                    </button>
                                );
                            })}
                        </div>
                    </div>

                    {/* Monitor type pills */}
                    <div>
                        <p className="text-xs font-medium text-muted-foreground mb-2">Monitor types</p>
                        <div className="flex flex-wrap gap-1.5">
                            <button
                                onClick={() => setSelectedSubTypes(new Set())}
                                className={`inline-flex items-center rounded-full px-2.5 py-1 text-xs font-medium border transition-colors ${
                                    selectedSubTypes.size === 0
                                        ? "border-amber-400 bg-amber-500/10 text-amber-700 dark:text-amber-300"
                                        : "border-border text-muted-foreground hover:border-amber-400/60"
                                }`}
                            >All types
                            </button>
                            {COMPLIANCE_SUB_TYPES.map(st => {
                                const checked = selectedSubTypes.has(st);
                                return (
                                    <button key={st} onClick={() => toggleSubType(st)}
                                            className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium border transition-colors ${
                                                checked
                                                    ? "border-amber-400 bg-amber-500/10 text-amber-700 dark:text-amber-300"
                                                    : "border-border text-muted-foreground hover:border-amber-400/60"
                                            }`}
                                    >
                                        <span
                                            className={`h-1.5 w-1.5 rounded-full ${checked ? "bg-amber-500" : "bg-muted-foreground/30"}`}/>
                                        {SUB_TYPE_LABELS[st]}
                                    </button>
                                );
                            })}
                        </div>
                    </div>
                </div>

                {/* Sync action buttons */}
                <div className="mt-3 flex gap-2">
                    <button onClick={() => runSync(true)} disabled={syncing}
                            className="flex items-center gap-1.5 rounded-xl border border-border bg-card px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground disabled:opacity-40">
                        {syncing ? <Loader2 className="h-4 w-4 animate-spin"/> : <RefreshCw className="h-4 w-4"/>}
                        Preview
                    </button>
                    <button onClick={() => runSync(false)} disabled={syncing}
                            className="flex items-center gap-1.5 rounded-xl bg-amber-500 px-4 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:bg-amber-600 disabled:opacity-40">
                        {syncing ? <Loader2 className="h-4 w-4 animate-spin"/> : <CheckCircle2 className="h-4 w-4"/>}
                        Apply
                    </button>
                </div>

                {/* Sync error */}
                {syncError && (
                    <div
                        className="mt-3 flex items-start gap-2 rounded-2xl border border-red-200 bg-red-50/60 px-4 py-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/20 dark:text-red-400">
                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0"/>
                        {syncError}
                    </div>
                )}

                {/* Sync result */}
                {syncResult && (
                    <div className="mt-3 rounded-2xl border border-border bg-secondary/20 p-4 space-y-3">
                        <div className="flex items-center gap-2">
                            {syncResult.dryRun
                                ? <Info className="h-4 w-4 text-sky-500"/>
                                : <CheckCircle2 className="h-4 w-4 text-green-500"/>}
                            <p className="text-sm font-medium text-foreground">
                                {syncResult.dryRun ? "Preview result" : "Sync complete"}
                            </p>
                        </div>
                        <div className="grid grid-cols-2 gap-2">
                            <div className="rounded-xl border border-border bg-background px-3 py-2">
                                <p className="text-xs text-muted-foreground">Websites scanned</p>
                                <p className="mt-0.5 text-lg font-bold text-foreground">{syncResult.websitesScanned}</p>
                            </div>
                            <div className={`rounded-xl border px-3 py-2 ${
                                syncResult.monitorsCreated > 0
                                    ? "border-amber-200 bg-amber-50/60 dark:border-amber-800/40 dark:bg-amber-950/20"
                                    : "border-border bg-background"
                            }`}>
                                <p className="text-xs text-muted-foreground">
                                    Monitors {syncResult.dryRun ? "to create" : "created"}
                                </p>
                                <p className={`mt-0.5 text-lg font-bold ${
                                    syncResult.monitorsCreated > 0 ? "text-amber-600 dark:text-amber-400" : "text-foreground"
                                }`}>{syncResult.monitorsCreated}</p>
                            </div>
                        </div>
                        {Object.keys(syncResult.createdBySubType).length > 0 && (
                            <div>
                                <p className="text-xs font-medium text-muted-foreground mb-1.5">Breakdown by type</p>
                                <div className="flex flex-wrap gap-1.5">
                                    {Object.entries(syncResult.createdBySubType).map(([st, count]) => (
                                        <span key={st}
                                              className="inline-flex items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-2.5 py-0.5 text-xs font-medium text-amber-700 dark:border-amber-800/40 dark:bg-amber-950/30 dark:text-amber-300">
                                            {SUB_TYPE_LABELS[st as MonitorSubType] ?? st}
                                            <span className="font-bold">×{count}</span>
                                        </span>
                                    ))}
                                </div>
                            </div>
                        )}
                        {syncResult.monitorsCreated === 0 && (
                            <p className="text-xs text-muted-foreground">
                                All websites already have the expected monitors. Nothing to sync.
                            </p>
                        )}
                    </div>
                )}
            </section>
        </div>
    );
}
