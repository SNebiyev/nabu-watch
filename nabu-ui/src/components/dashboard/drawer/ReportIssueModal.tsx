"use client";

import {useEffect, useState} from "react";
import {createPortal} from "react-dom";
import {Flag, Info, X} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import type {LatestScanItem} from "@/lib/types";

interface ReportIssueModalProps {
    item: LatestScanItem;
    onClose: () => void;
    onReported: () => void;
}

export function ReportIssueModal({item, onClose, onReported}: ReportIssueModalProps) {
    const [mounted, setMounted] = useState(false);
    const [notes, setNotes] = useState("");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [touched, setTouched] = useState(false);

    useEffect(() => {
        setMounted(true);
    }, []);

    if (!mounted) return null;

    const notesEmpty = notes.trim().length === 0;

    async function handleSubmit() {
        setTouched(true);
        if (notesEmpty || !item.scanLogId) return;

        setSaving(true);
        setError(null);
        try {
            const res = await apiClient(`/api/v1/scan-logs/${item.scanLogId}/report`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({notes: notes.trim()}),
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({})) as {detail?: string};
                throw new Error(body.detail || `Failed to submit report (${res.status})`);
            }
            onReported();
            onClose();
        } catch (e) {
            setError(e instanceof Error ? e.message : "Failed to submit report");
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
                        <Flag className="h-4 w-4 text-orange-400"/>
                        <h2 className="text-sm font-semibold text-foreground">Report Monitor Result</h2>
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
                    className="mx-5 mt-2 flex items-start gap-2 rounded-xl border border-orange-500/20 bg-orange-950/20 px-3 py-2 text-xs text-orange-200">
                    <Info className="mt-0.5 h-3.5 w-3.5 shrink-0 text-orange-400"/>
                    <p>
                        This report will be reviewed by our team. Please describe why you believe the
                        scan result for <span className="font-semibold">{item.subType ?? item.monitorType}</span> is incorrect.
                    </p>
                </div>

                {/* Notes */}
                <div className="px-5 pt-4">
                    <label className="mb-1.5 block text-xs font-medium text-muted-foreground">
                        Notes <span className="text-red-400">*</span>
                    </label>
                    <textarea
                        value={notes}
                        onChange={(e) => setNotes(e.target.value)}
                        onBlur={() => setTouched(true)}
                        maxLength={2000}
                        rows={4}
                        placeholder="Describe the issue — e.g. 'The scanner flagged our cookie banner as non-compliant but it meets GDPR requirements...'"
                        className={`w-full rounded-xl border px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground/40 focus:outline-none focus:ring-1 bg-white/[0.03] ${
                            touched && notesEmpty
                                ? "border-red-500/50 focus:border-red-500/50 focus:ring-red-500/30"
                                : "border-white/10 focus:border-orange-500/50 focus:ring-orange-500/30"
                        }`}
                    />
                    <div className="mt-1 flex items-center justify-between">
                        {touched && notesEmpty ? (
                            <p className="text-xs text-red-400">Notes are required</p>
                        ) : (
                            <span/>
                        )}
                        <p className="text-right text-[10px] text-muted-foreground/50">{notes.length}/2000</p>
                    </div>
                </div>

                {/* Error */}
                {error && (
                    <p className="mx-5 mt-2 text-xs text-red-400">{error}</p>
                )}

                {/* Footer */}
                <div className="mt-4 flex items-center justify-end gap-2 border-t border-white/5 px-5 py-3">
                    <button
                        onClick={onClose}
                        className="rounded-xl px-4 py-2 text-xs font-medium text-muted-foreground transition-colors hover:bg-white/5"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={handleSubmit}
                        disabled={saving || notesEmpty}
                        className="rounded-xl bg-orange-600 px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-orange-500 disabled:opacity-50"
                    >
                        {saving ? "Submitting..." : "Submit Report"}
                    </button>
                </div>
            </div>
        </div>
    );

    return createPortal(modal, document.body);
}
