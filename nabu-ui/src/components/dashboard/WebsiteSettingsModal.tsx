"use client";

import {useEffect, useState} from "react";
import {Camera, ExternalLink, Globe, Loader2, PauseCircle, PlayCircle, X} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {subTypeLabel} from "@/components/dashboard/drawer/utils";
import type {MonitorSubType} from "@/lib/types";

// ── Types ─────────────────────────────────────────────────────────────────────

interface MonitorConfigItem {
    monitorId: string;
    type: string;
    subType: string;
    targetUrl: string | null;
    active: boolean;
}

type AcceptedLangs = "NATIVE_ONLY" | "ENGLISH_ONLY" | "BOTH_ACCEPTED";

interface WebsiteSettingsModalProps {
    open: boolean;
    websiteId: string;
    websiteUrl: string;
    /** ISO region code, e.g. "DE", "US" */
    region: string;
    /** Current website-level status ("ACTIVE" | "PAUSED") */
    websiteStatus: string;
    /** Current accepted languages preference */
    acceptedLanguages: AcceptedLangs;
    /** Whether screenshot capture is enabled for this website */
    screenshotsEnabled: boolean;
    onClose: () => void;
    /** Called after a status toggle so the parent can refresh its data. */
    onStatusChanged?: (newStatus: "ACTIVE" | "PAUSED") => void;
    /** Called after language preference is updated */
    onLanguageChanged?: (newPref: AcceptedLangs) => void;
    /** Called after screenshot setting is changed */
    onScreenshotsChanged?: (enabled: boolean) => void;
}


// ── MonitorUrlRow — inline editable target URL ────────────────────────────────

interface MonitorUrlRowProps {
    item: MonitorConfigItem;
    /** Called after a successful save to refresh the list. */
    onSaved: (monitorId: string, newUrl: string | null) => void;
}

function MonitorUrlRow({item, onSaved}: MonitorUrlRowProps) {
    const [editing, setEditing] = useState(false);
    const [value, setValue] = useState(item.targetUrl ?? "");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const handleSave = async () => {
        setSaving(true);
        setError(null);
        try {
            const res = await apiClient(`/api/v1/monitors/${item.monitorId}`, {
                method: "PATCH",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({targetUrl: value.trim() || null}),
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                setError(body.detail ?? "Failed to save");
                return;
            }
            onSaved(item.monitorId, value.trim() || null);
            setEditing(false);
        } catch {
            setError("Network error. Try again.");
        } finally {
            setSaving(false);
        }
    };

    const handleClear = async () => {
        setValue("");
        setSaving(true);
        setError(null);
        try {
            const res = await apiClient(`/api/v1/monitors/${item.monitorId}`, {
                method: "PATCH",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({targetUrl: null}),
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                setError(body.detail ?? "Failed to clear");
                return;
            }
            onSaved(item.monitorId, null);
            setEditing(false);
        } catch {
            setError("Network error. Try again.");
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className="space-y-1">
            <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 text-xs font-medium text-foreground truncate">
          {subTypeLabel(item.subType as MonitorSubType)}
        </span>
                {!item.active && (
                    <span
                        className="rounded px-1.5 py-0.5 text-[10px] font-medium bg-secondary text-muted-foreground ring-1 ring-inset ring-border">
            Inactive
          </span>
                )}
            </div>

            {editing ? (
                <div className="flex items-center gap-1.5">
                    <input
                        type="url"
                        value={value}
                        onChange={(e) => setValue(e.target.value)}
                        placeholder="https://example.com/privacy"
                        className="min-w-0 flex-1 rounded-lg border border-border bg-secondary px-2.5 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:border-primary/50 focus:outline-none focus:ring-1 focus:ring-primary/30"
                        autoFocus
                    />
                    <button
                        type="button"
                        onClick={handleSave}
                        disabled={saving}
                        className="shrink-0 rounded-lg bg-primary px-2.5 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors"
                    >
                        {saving ? <Loader2 className="h-3 w-3 animate-spin"/> : "Save"}
                    </button>
                    {item.targetUrl && (
                        <button
                            type="button"
                            onClick={handleClear}
                            disabled={saving}
                            className="shrink-0 rounded-lg border border-border px-2.5 py-1.5 text-xs text-muted-foreground hover:text-red-400 hover:border-red-500/40 disabled:opacity-50 transition-colors"
                        >
                            Clear
                        </button>
                    )}
                    <button
                        type="button"
                        onClick={() => {
                            setEditing(false);
                            setValue(item.targetUrl ?? "");
                            setError(null);
                        }}
                        className="shrink-0 text-muted-foreground hover:text-foreground transition-colors"
                    >
                        <X className="h-3.5 w-3.5"/>
                    </button>
                </div>
            ) : (
                <button
                    type="button"
                    onClick={() => {
                        setEditing(true);
                        setValue(item.targetUrl ?? "");
                    }}
                    className="flex w-full items-center gap-1.5 rounded-lg border border-dashed border-border bg-secondary/40 px-2.5 py-1.5 text-left text-xs hover:border-primary/40 hover:bg-secondary transition-colors group"
                >
                    {item.targetUrl ? (
                        <>
                            <ExternalLink className="h-3 w-3 shrink-0 text-primary"/>
                            <span className="min-w-0 flex-1 truncate text-primary">{item.targetUrl}</span>
                            <span
                                className="shrink-0 text-[10px] text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity">
                Edit
              </span>
                        </>
                    ) : (
                        <span className="text-muted-foreground">+ Add target URL</span>
                    )}
                </button>
            )}

            {error && (
                <p className="text-[11px] text-red-400">{error}</p>
            )}
        </div>
    );
}

// ── Main modal ────────────────────────────────────────────────────────────────

const ENGLISH_REGIONS = new Set(["GB", "US", "AU", "NZ", "IE"]);

export function WebsiteSettingsModal({
                                         open,
                                         websiteId,
                                         websiteUrl,
                                         region,
                                         websiteStatus,
                                         acceptedLanguages,
                                         screenshotsEnabled,
                                         onClose,
                                         onStatusChanged,
                                         onLanguageChanged,
                                         onScreenshotsChanged,
                                     }: WebsiteSettingsModalProps) {
    const [monitors, setMonitors] = useState<MonitorConfigItem[]>([]);
    const [loadingMonitors, setLoadingMonitors] = useState(false);
    const [togglingStatus, setTogglingStatus] = useState(false);
    const [statusError, setStatusError] = useState<string | null>(null);
    const [localLangPref, setLocalLangPref] = useState<AcceptedLangs>(acceptedLanguages);
    const [savingLang, setSavingLang] = useState(false);
    const [langError, setLangError] = useState<string | null>(null);
    const [localScreenshots, setLocalScreenshots] = useState(screenshotsEnabled);
    const [savingScreenshots, setSavingScreenshots] = useState(false);
    const [screenshotError, setScreenshotError] = useState<string | null>(null);
    const isEnglishRegion = ENGLISH_REGIONS.has(region?.toUpperCase());

    // Sync local state when prop changes (e.g. parent refreshes data)
    useEffect(() => {
        setLocalLangPref(acceptedLanguages);
    }, [acceptedLanguages]);

    useEffect(() => {
        setLocalScreenshots(screenshotsEnabled);
    }, [screenshotsEnabled]);

    const handleLangChange = async (newPref: AcceptedLangs) => {
        setLocalLangPref(newPref); // optimistic UI update
        setSavingLang(true);
        setLangError(null);
        try {
            const res = await apiClient(`/api/v1/websites/${websiteId}/language-preference`, {
                method: "PATCH",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({acceptedLanguages: newPref}),
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                setLangError(body.detail ?? "Failed to update");
                setLocalLangPref(acceptedLanguages); // rollback
                return;
            }
            onLanguageChanged?.(newPref);
        } catch {
            setLangError("Network error. Try again.");
            setLocalLangPref(acceptedLanguages); // rollback
        } finally {
            setSavingLang(false);
        }
    };

    const handleScreenshotsToggle = async () => {
        const newVal = !localScreenshots;
        setLocalScreenshots(newVal); // optimistic
        setSavingScreenshots(true);
        setScreenshotError(null);
        try {
            const res = await apiClient(`/api/v1/websites/${websiteId}/screenshots`, {
                method: "PATCH",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({enabled: newVal}),
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                setScreenshotError(body.detail ?? "Failed to update");
                setLocalScreenshots(screenshotsEnabled); // rollback
                return;
            }
            onScreenshotsChanged?.(newVal);
        } catch {
            setScreenshotError("Network error. Try again.");
            setLocalScreenshots(screenshotsEnabled); // rollback
        } finally {
            setSavingScreenshots(false);
        }
    };

    // Fetch monitor config when opened
    useEffect(() => {
        if (!open) return;
        setLoadingMonitors(true);
        apiClient(`/api/v1/websites/${websiteId}/monitors`)
            .then((r) => (r.ok ? r.json() : []))
            .then((data: MonitorConfigItem[]) => {
                setMonitors(data);
            })
            .catch(() => setMonitors([]))
            .finally(() => setLoadingMonitors(false));
    }, [open, websiteId]);

    const handleStatusToggle = async () => {
        const newStatus = websiteStatus === "ACTIVE" ? "PAUSED" : "ACTIVE";
        setTogglingStatus(true);
        setStatusError(null);
        try {
            const res = await apiClient(`/api/v1/websites/${websiteId}/status`, {
                method: "PATCH",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({status: newStatus}),
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                setStatusError(body.detail ?? "Failed to update status");
                return;
            }
            onStatusChanged?.(newStatus);
        } catch {
            setStatusError("Network error. Try again.");
        } finally {
            setTogglingStatus(false);
        }
    };

    const handleMonitorSaved = (monitorId: string, newUrl: string | null) => {
        setMonitors((prev) =>
            prev.map((m) => (m.monitorId === monitorId ? {...m, targetUrl: newUrl} : m))
        );
    };

    if (!open) return null;

    const isPaused = websiteStatus === "PAUSED";
    const displayUrl = websiteUrl.replace(/^https?:\/\//, "");

    return (
        <div
            className="fixed inset-0 z-[200] flex items-center justify-center p-4"
            role="dialog"
            aria-modal="true"
            aria-labelledby="settings-modal-title"
        >
            {/* Backdrop */}
            <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onClose}/>

            {/* Panel */}
            <div
                className="relative z-10 w-full max-w-md rounded-2xl border border-border bg-card shadow-2xl backdrop-blur-md">
                {/* Header */}
                <div className="flex items-start justify-between gap-3 border-b border-border px-5 py-4">
                    <div className="min-w-0">
                        <p id="settings-modal-title" className="text-sm font-semibold text-foreground">
                            Website Settings
                        </p>
                        <p className="mt-0.5 text-xs text-muted-foreground truncate">{displayUrl}</p>
                    </div>
                    <button
                        type="button"
                        onClick={onClose}
                        className="shrink-0 text-muted-foreground hover:text-foreground transition-colors mt-0.5"
                        aria-label="Close"
                    >
                        <X className="h-4 w-4"/>
                    </button>
                </div>

                <div className="max-h-[70vh] overflow-y-auto px-5 py-4 space-y-5">
                    {/* Status toggle */}
                    <div>
                        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                            Monitoring Status
                        </p>
                        <div
                            className="flex items-center justify-between rounded-xl border border-border bg-secondary/40 px-4 py-3">
                            <div className="flex items-center gap-2.5">
                                {isPaused ? (
                                    <PauseCircle className="h-4 w-4 text-amber-400"/>
                                ) : (
                                    <PlayCircle className="h-4 w-4 text-green-400"/>
                                )}
                                <div>
                                    <p className="text-xs font-medium text-foreground">
                                        {isPaused ? "Paused" : "Active"}
                                    </p>
                                    <p className="text-[11px] text-muted-foreground">
                                        {isPaused
                                            ? "Scheduled scans are suspended"
                                            : "Scheduled scans are running normally"}
                                    </p>
                                </div>
                            </div>
                            <button
                                type="button"
                                onClick={handleStatusToggle}
                                disabled={togglingStatus}
                                className={`shrink-0 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors disabled:opacity-50 ${
                                    isPaused
                                        ? "bg-green-600 text-white hover:bg-green-500"
                                        : "bg-amber-600 text-white hover:bg-amber-500"
                                }`}
                            >
                                {togglingStatus ? (
                                    <Loader2 className="h-3.5 w-3.5 animate-spin"/>
                                ) : isPaused ? (
                                    "Resume"
                                ) : (
                                    "Pause"
                                )}
                            </button>
                        </div>
                        {statusError && (
                            <p className="mt-1.5 text-[11px] text-red-400">{statusError}</p>
                        )}
                    </div>

                    {/* Language preference — hidden for English-native regions */}
                    {!isEnglishRegion && (
                        <div>
                            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                                Accepted Languages
                            </p>
                            <p className="mb-3 text-[11px] text-muted-foreground">
                                Choose which languages count as a passing result for compliance scans.
                            </p>
                            <div className="space-y-2">
                                {([
                                    {
                                        value: "NATIVE_ONLY" as AcceptedLangs,
                                        label: "Regional language only",
                                        desc: "Only native-language keywords count as a passing result"
                                    },
                                    {
                                        value: "ENGLISH_ONLY" as AcceptedLangs,
                                        label: "English only",
                                        desc: "Site is in English — scan with English keywords instead of native"
                                    },
                                    {
                                        value: "BOTH_ACCEPTED" as AcceptedLangs,
                                        label: "Both languages accepted",
                                        desc: "Bilingual site — native or English keywords both count as passing"
                                    },
                                ]).map((opt) => (
                                    <label
                                        key={opt.value}
                                        className={`flex items-start gap-3 rounded-xl border px-4 py-3 cursor-pointer transition-colors ${
                                            localLangPref === opt.value
                                                ? "border-primary/50 bg-primary/5"
                                                : "border-border bg-secondary/40 hover:bg-secondary/60"
                                        } ${savingLang ? "opacity-50 pointer-events-none" : ""}`}
                                    >
                                        <input
                                            type="radio"
                                            name="acceptedLanguages"
                                            value={opt.value}
                                            checked={localLangPref === opt.value}
                                            onChange={() => handleLangChange(opt.value)}
                                            className="mt-0.5 h-3.5 w-3.5 accent-primary"
                                        />
                                        <div>
                                            <p className="text-xs font-medium text-foreground">{opt.label}</p>
                                            <p className="text-[11px] text-muted-foreground">{opt.desc}</p>
                                        </div>
                                    </label>
                                ))}
                            </div>
                            {langError && <p className="mt-1.5 text-[11px] text-red-400">{langError}</p>}
                            <div className="mt-2 flex items-start gap-1.5 text-[11px] text-muted-foreground">
                                <Globe className="mt-0.5 h-3 w-3 shrink-0"/>
                                <span>This setting takes effect on the next scan. Existing results are not re-evaluated.</span>
                            </div>
                        </div>
                    )}

                    {/* Screenshot capture */}
                    <div>
                        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                            Screenshot Capture
                        </p>
                        <label
                            className={`flex items-center gap-3 rounded-xl border px-4 py-3 cursor-pointer transition-colors ${
                                localScreenshots
                                    ? "border-primary/50 bg-primary/5"
                                    : "border-border bg-secondary/40 hover:bg-secondary/60"
                            } ${savingScreenshots ? "opacity-50 pointer-events-none" : ""}`}
                        >
                            <input
                                type="checkbox"
                                checked={localScreenshots}
                                onChange={handleScreenshotsToggle}
                                disabled={savingScreenshots}
                                className="h-3.5 w-3.5 accent-primary"
                            />
                            <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-1.5">
                                    <Camera className="h-3.5 w-3.5 text-muted-foreground"/>
                                    <p className="text-xs font-medium text-foreground">
                                        Enable visual evidence
                                    </p>
                                </div>
                                <p className="mt-0.5 text-[11px] text-muted-foreground">
                                    Captures a screenshot during headless scans (banner detection, etc.)
                                    as visual proof of compliance status.
                                </p>
                            </div>
                            {savingScreenshots && <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground"/>}
                        </label>
                        {screenshotError && (
                            <p className="mt-1.5 text-[11px] text-red-400">{screenshotError}</p>
                        )}
                    </div>

                    {/* Monitor target URLs */}
                    <div>
                        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                            Monitor Target URLs
                        </p>
                        <p className="mb-3 text-[11px] text-muted-foreground">
                            Override the scan target for specific monitors. Leave blank to use the site&apos;s primary
                            URL.
                        </p>

                        {loadingMonitors ? (
                            <div className="space-y-2.5">
                                {[1, 2, 3].map((i) => (
                                    <div key={i} className="h-14 animate-pulse rounded-xl bg-slate-800/50"/>
                                ))}
                            </div>
                        ) : monitors.length === 0 ? (
                            <p className="text-xs text-muted-foreground">No monitors found.</p>
                        ) : (
                            <div className="space-y-3">
                                {monitors.map((m) => (
                                    <MonitorUrlRow key={m.monitorId} item={m} onSaved={handleMonitorSaved}/>
                                ))}
                            </div>
                        )}
                    </div>
                </div>

                {/* Footer */}
                <div className="border-t border-border px-5 py-3">
                    <button
                        type="button"
                        onClick={onClose}
                        className="w-full rounded-xl border border-border bg-secondary px-4 py-2.5 text-sm text-muted-foreground hover:bg-secondary/80 hover:text-foreground transition-colors"
                    >
                        Done
                    </button>
                </div>
            </div>
        </div>
    );
}
