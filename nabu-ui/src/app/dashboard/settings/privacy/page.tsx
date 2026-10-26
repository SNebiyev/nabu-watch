"use client";

import {useState} from "react";
import {AlertTriangle, Download, Loader2, Trash2} from "lucide-react";
import {apiClient} from "@/lib/apiClient";

export default function PrivacySettingsPage() {
    const [exportLoading, setExportLoading] = useState(false);
    const [deleteStep, setDeleteStep] = useState<"idle" | "confirm" | "deleting" | "done">("idle");
    const [error, setError] = useState<string | null>(null);

    async function handleExport() {
        setExportLoading(true);
        setError(null);
        try {
            const res = await apiClient("/api/v1/users/me/export");
            if (!res.ok) throw new Error("Export failed");
            const data = await res.json();
            const blob = new Blob([JSON.stringify(data, null, 2)], {type: "application/json"});
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = `nabu-watch-data-export-${new Date().toISOString().slice(0, 10)}.json`;
            a.click();
            URL.revokeObjectURL(url);
        } catch {
            setError("Failed to export data. Please try again.");
        } finally {
            setExportLoading(false);
        }
    }

    async function handleDelete() {
        setDeleteStep("deleting");
        setError(null);
        try {
            const res = await apiClient("/api/v1/users/me", {method: "DELETE"});
            if (!res.ok) throw new Error("Deletion failed");
            setDeleteStep("done");
        } catch {
            setError("Failed to request account deletion. Please try again.");
            setDeleteStep("idle");
        }
    }

    return (
        <div className="space-y-6">
            <div>
                <h2 className="text-lg font-semibold text-foreground">Privacy & Data</h2>
                <p className="mt-1 text-sm text-muted-foreground">
                    Manage your personal data in accordance with GDPR. You can export all your data
                    or request permanent account deletion.
                </p>
            </div>

            {error && (
                <div className="flex items-start gap-2.5 rounded-xl border border-red-900/50 bg-red-950/50 px-3 py-2.5">
                    <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-red-400"/>
                    <p className="text-sm text-red-400">{error}</p>
                </div>
            )}

            {/* Data Export */}
            <div className="rounded-xl border border-border bg-card p-5 space-y-3">
                <div className="flex items-center gap-2.5">
                    <div
                        className="flex h-8 w-8 items-center justify-center rounded-lg bg-sky-950/60 ring-1 ring-sky-800/40">
                        <Download className="h-4 w-4 text-sky-400"/>
                    </div>
                    <div>
                        <h3 className="text-sm font-semibold text-foreground">Export your data</h3>
                        <p className="text-xs text-muted-foreground">GDPR Article 15 & 20</p>
                    </div>
                </div>
                <p className="text-sm text-muted-foreground">
                    Download a JSON file containing all your personal data: profile, organizations,
                    websites, monitors, notifications, and messages.
                </p>
                <button
                    onClick={handleExport}
                    disabled={exportLoading}
                    className="inline-flex items-center gap-2 rounded-lg bg-sky-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-sky-700 disabled:opacity-50"
                >
                    {exportLoading ? <Loader2 className="h-4 w-4 animate-spin"/> : <Download className="h-4 w-4"/>}
                    {exportLoading ? "Exporting..." : "Download my data"}
                </button>
            </div>

            {/* Account Deletion */}
            <div className="rounded-xl border border-red-900/30 bg-red-950/20 p-5 space-y-3">
                <div className="flex items-center gap-2.5">
                    <div
                        className="flex h-8 w-8 items-center justify-center rounded-lg bg-red-950/60 ring-1 ring-red-800/40">
                        <Trash2 className="h-4 w-4 text-red-400"/>
                    </div>
                    <div>
                        <h3 className="text-sm font-semibold text-foreground">Delete account</h3>
                        <p className="text-xs text-muted-foreground">GDPR Article 17 — Right to erasure</p>
                    </div>
                </div>

                {deleteStep === "done" ? (
                    <div className="rounded-lg border border-amber-800/50 bg-amber-950/40 px-4 py-3">
                        <p className="text-sm text-amber-200">
                            Account deletion has been scheduled. Your data will be permanently removed
                            after 30 days. You will be signed out shortly.
                        </p>
                    </div>
                ) : (
                    <>
                        <p className="text-sm text-muted-foreground">
                            Permanently delete your account and all associated data. This includes all
                            websites, scan history, monitors, and organization data (if you are the sole owner).
                            There is a <strong className="text-foreground">30-day grace period</strong> during
                            which your account can be recovered by contacting support.
                        </p>

                        {deleteStep === "idle" && (
                            <button
                                onClick={() => setDeleteStep("confirm")}
                                className="inline-flex items-center gap-2 rounded-lg border border-red-800/50 bg-red-950/40 px-4 py-2 text-sm font-medium text-red-400 transition-colors hover:bg-red-900/40"
                            >
                                <Trash2 className="h-4 w-4"/>
                                Request account deletion
                            </button>
                        )}

                        {deleteStep === "confirm" && (
                            <div className="space-y-3">
                                <div className="rounded-lg border border-red-800/50 bg-red-950/50 px-4 py-3">
                                    <p className="text-sm font-medium text-red-300">
                                        Are you sure? This action cannot be undone after 30 days.
                                    </p>
                                </div>
                                <div className="flex items-center gap-2">
                                    <button
                                        onClick={handleDelete}
                                        className="inline-flex items-center gap-2 rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-red-700"
                                    >
                                        <Trash2 className="h-4 w-4"/>
                                        Yes, delete my account
                                    </button>
                                    <button
                                        onClick={() => setDeleteStep("idle")}
                                        className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
                                    >
                                        Cancel
                                    </button>
                                </div>
                            </div>
                        )}

                        {deleteStep === "deleting" && (
                            <div className="flex items-center gap-2 text-sm text-muted-foreground">
                                <Loader2 className="h-4 w-4 animate-spin"/>
                                Processing deletion request...
                            </div>
                        )}
                    </>
                )}
            </div>
        </div>
    );
}
