"use client";

import {useEffect, useState} from "react";
import {createPortal} from "react-dom";
import {AlertTriangle, CheckCircle, Info, Shield, X} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import type {LatestScanItem} from "@/lib/types";

const SEVERITY_OPTIONS = [
    {value: "INFO", label: "Passing (INFO)", color: "text-green-600 dark:text-green-400"},
    {value: "MEDIUM", label: "Warning (MEDIUM)", color: "text-amber-600 dark:text-amber-400"},
    {value: "HIGH", label: "Failing (HIGH)", color: "text-red-600 dark:text-red-400"},
] as const;

interface StatusOverrideModalProps {
    item: LatestScanItem;
    onClose: () => void;
    onSaved: () => void;
}

export function StatusOverrideModal({item, onClose, onSaved}: StatusOverrideModalProps) {
    const [mounted, setMounted] = useState(false);
    const [selectedSeverity, setSelectedSeverity] = useState("INFO");
    const [note, setNote] = useState("");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        setMounted(true);
    }, []);

    if (!mounted) return null;

    const currentSeverity = item.severity ?? "INFO";

    async function handleSave() {
        if (!item.scanLogId) return;
        setSaving(true);
        setError(null);
        try {
            const res = await apiClient(`/api/v1/monitors/${item.monitorId}/override`, {
                method: "PUT",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({
                    scanLogId: item.scanLogId,
                    overrideSeverity: selectedSeverity,
                    note: note.trim() || null,
                }),
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                throw new Error(body.detail || `Failed to save override (${res.status})`);
            }
            onSaved();
            onClose();
        } catch (e) {
            setError(e instanceof Error ? e.message : "Failed to save override");
        } finally {
            setSaving(false);
        }
    }

    async function handleRemove() {
        setSaving(true);
        setError(null);
        try {
            const res = await apiClient(`/api/v1/monitors/${item.monitorId}/override`, {
                method: "DELETE",
            });
            if (!res.ok && res.status !== 204) {
                throw new Error(`Failed to remove override (${res.status})`);
            }
            onSaved();
            onClose();
        } catch (e) {
            setError(e instanceof Error ? e.message : "Failed to remove override");
        } finally {
            setSaving(false);
        }
    }

    const modal = (
        <div
            className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/90 p-4 backdrop-blur-xl"
            onClick={(e) => e.target === e.currentTarget && onClose()}
        >
            <div
                className="w-full max-w-md overflow-hidden rounded-2xl border border-white/5 bg-[#0B1120] shadow-2xl shadow-black/80">
                {/* Header */}
                <div className="flex items-start justify-between px-5 pb-2 pt-5">
                    <div className="flex items-center gap-2">
                        <Shield className="h-4 w-4 text-sky-400"/>
                        <h2 className="text-sm font-semibold text-foreground">Override Monitor Status</h2>
                    </div>
                    <button
                        onClick={onClose}
                        className="rounded-lg p-1 text-muted-foreground transition-colors hover:bg-white/5"
                    >
                        <X className="h-4 w-4"/>
                    </button>
                </div>

                {/* Info */}
                <div
                    className="mx-5 mt-2 flex items-start gap-2 rounded-xl border border-sky-500/20 bg-sky-950/30 px-3 py-2 text-xs text-sky-200">
                    <Info className="mt-0.5 h-3.5 w-3.5 shrink-0 text-sky-400"/>
                    <p>
                        The scanner reported <span className="font-semibold">{currentSeverity}</span>{" "}for this
                        monitor.
                        Select the status you consider correct. Future scans with the same result will use your choice.
                        If the result changes, you&apos;ll be notified.
                    </p>
                </div>

                {/* Severity selector */}
                <div className="px-5 pt-4">
                    <label className="mb-2 block text-xs font-medium text-muted-foreground">Override to:</label>
                    <div className="flex flex-col gap-2">
                        {SEVERITY_OPTIONS.map((opt) => (
                            <button
                                key={opt.value}
                                type="button"
                                onClick={() => setSelectedSeverity(opt.value)}
                                className={`flex items-center gap-2 rounded-xl border px-3 py-2.5 text-left text-sm transition-colors ${
                                    selectedSeverity === opt.value
                                        ? "border-sky-500/50 bg-sky-950/40 text-foreground"
                                        : "border-white/5 bg-white/[0.02] text-muted-foreground hover:bg-white/5"
                                }`}
                            >
                                {opt.value === "INFO" ? (
                                    <CheckCircle className={`h-4 w-4 ${opt.color}`}/>
                                ) : (
                                    <AlertTriangle className={`h-4 w-4 ${opt.color}`}/>
                                )}
                                {opt.label}
                                {opt.value === currentSeverity && (
                                    <span className="ml-auto text-[10px] text-muted-foreground">(current)</span>
                                )}
                            </button>
                        ))}
                    </div>
                </div>

                {/* Note */}
                <div className="px-5 pt-4">
                    <label className="mb-1.5 block text-xs font-medium text-muted-foreground">
                        Note <span className="text-muted-foreground/50">(optional)</span>
                    </label>
                    <textarea
                        value={note}
                        onChange={(e) => setNote(e.target.value)}
                        maxLength={500}
                        rows={2}
                        placeholder="Why is this acceptable? e.g. 'We use a third-party cookie banner provider'"
                        className="w-full rounded-xl border border-white/10 bg-white/[0.03] px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground/40 focus:border-sky-500/50 focus:outline-none focus:ring-1 focus:ring-sky-500/30"
                    />
                </div>

                {/* Error */}
                {error && (
                    <p className="mx-5 mt-2 text-xs text-red-400">{error}</p>
                )}

                {/* Footer */}
                <div className="mt-4 flex items-center justify-between border-t border-white/5 px-5 py-3">
                    <div>
                        {item.hasOverride && (
                            <button
                                type="button"
                                onClick={handleRemove}
                                disabled={saving}
                                className="rounded-xl px-3 py-2 text-xs font-medium text-red-400 transition-colors hover:bg-red-500/10 disabled:opacity-50"
                            >
                                Remove Override
                            </button>
                        )}
                    </div>
                    <div className="flex items-center gap-2">
                        <button
                            onClick={onClose}
                            className="rounded-xl px-4 py-2 text-xs font-medium text-muted-foreground transition-colors hover:bg-white/5"
                        >
                            Cancel
                        </button>
                        <button
                            onClick={handleSave}
                            disabled={saving || !item.scanLogId}
                            className="rounded-xl bg-primary px-4 py-2 text-xs font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
                        >
                            {saving ? "Saving..." : "Save Override"}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );

    return createPortal(modal, document.body);
}
