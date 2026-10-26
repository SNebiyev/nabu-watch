import {useState} from "react";
import {ArrowLeft, Loader2, Lock, Save} from "lucide-react";
import {Button} from "@/components/ui/button";
import {apiClient} from "@/lib/apiClient";
import type {MonitorSubType, SyncMonitorsResult} from "@/lib/types";
import {COMPLIANCE_SUBTYPES} from "./utils";
import {MonitorIcon} from "./MonitorIcon";

export interface ManageMonitorsPanelProps {
    websiteId: string;
    /** Currently active compliance sub-types (derived from website.monitors). */
    initialEnabled: MonitorSubType[];
    onSaved: (result: SyncMonitorsResult, enabledSubTypes: MonitorSubType[]) => void;
    onCancel: () => void;
}

export function ManageMonitorsPanel({
                                        websiteId,
                                        initialEnabled,
                                        onSaved,
                                        onCancel,
                                    }: ManageMonitorsPanelProps) {
    const [enabled, setEnabled] = useState<MonitorSubType[]>(initialEnabled);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    function toggle(subType: MonitorSubType) {
        setEnabled((prev) =>
            prev.includes(subType)
                ? prev.filter((s) => s !== subType)
                : [...prev, subType]
        );
    }

    async function handleSave() {
        setSaving(true);
        setError(null);
        try {
            // Always include SSL so the backend gets the full picture;
            // it ignores SSL in the sync logic (SSL is permanent).
            const payload = {enabledSubTypes: ["SSL", ...enabled]};
            const res = await apiClient(`/api/v1/websites/${websiteId}/monitors`, {
                method: "PUT",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(payload),
            });
            if (!res.ok) {
                const text = await res.text();
                throw new Error(text || "Failed to update monitors");
            }
            const result: SyncMonitorsResult = await res.json();
            onSaved(result, enabled);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Something went wrong");
        } finally {
            setSaving(false);
        }
    }

    return (
        <div className="flex flex-col gap-4">
            {/* Info banner */}
            <div className="rounded-xl bg-secondary/40 px-4 py-3 ring-1 ring-border">
                <p className="text-xs md:text-sm text-[--muted-foreground]">
                    SSL certificate monitoring is always active.
                    Toggle compliance checks on or off — disabled monitors keep their
                    scan history and can be re-enabled at any time.
                </p>
            </div>

            {/* Checkbox grid */}
            <div className="space-y-2">
                {/* SSL — always on, non-toggleable */}
                <div
                    className="flex cursor-not-allowed items-center gap-3 rounded-xl border border-blue-200 bg-blue-50 px-3 py-3 md:py-3.5 opacity-70 dark:border-blue-800/30 dark:bg-blue-950/10">
                    <div
                        className="flex h-8 w-8 md:h-9 md:w-9 shrink-0 items-center justify-center rounded-xl bg-secondary/50 ring-1 ring-border">
                        <Lock className="h-4 w-4 text-blue-400"/>
                    </div>
                    <div className="min-w-0 flex-1">
                        <p className="text-sm font-medium text-[--foreground]">
                            SSL Certificate
                        </p>
                        <p className="text-xs text-[--muted-foreground]">
                            Always active — cannot be disabled
                        </p>
                    </div>
                    {/* Fixed checked box */}
                    <div
                        className="flex h-4 w-4 shrink-0 items-center justify-center rounded border border-blue-500 bg-blue-500">
                        <svg
                            viewBox="0 0 12 12"
                            className="h-3 w-3 text-white"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="2.5"
                        >
                            <path d="M2 6l3 3 5-5" strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                    </div>
                </div>

                {/* Compliance sub-types */}
                {COMPLIANCE_SUBTYPES.map((m) => {
                    const checked = enabled.includes(m.value);
                    return (
                        <label
                            key={m.value}
                            onClick={() => toggle(m.value)}
                            className={`flex cursor-pointer items-center gap-3 rounded-xl border px-3 py-3 md:py-3.5 transition-colors ${
                                checked
                                    ? "border-violet-300 bg-violet-50 dark:border-violet-800/50 dark:bg-violet-950/15"
                                    : "border-border bg-secondary/20 hover:bg-secondary/40"
                            }`}
                        >
                            <div
                                className="flex h-8 w-8 md:h-9 md:w-9 shrink-0 items-center justify-center rounded-xl bg-secondary/50 ring-1 ring-border">
                                <MonitorIcon subType={m.value}/>
                            </div>
                            <div className="min-w-0 flex-1">
                                <p className="text-sm font-medium text-[--foreground]">
                                    {m.label}
                                </p>
                                <p className="text-xs text-[--muted-foreground]">{m.desc}</p>
                            </div>
                            <div
                                className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border transition-colors ${
                                    checked
                                        ? "border-violet-500 bg-violet-500"
                                        : "border-border bg-transparent"
                                }`}
                            >
                                {checked && (
                                    <svg
                                        viewBox="0 0 12 12"
                                        className="h-3 w-3 text-white"
                                        fill="none"
                                        stroke="currentColor"
                                        strokeWidth="2.5"
                                    >
                                        <path d="M2 6l3 3 5-5" strokeLinecap="round" strokeLinejoin="round"/>
                                    </svg>
                                )}
                            </div>
                        </label>
                    );
                })}
            </div>

            {/* Error */}
            {error && (
                <div
                    className="flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 px-3 py-2.5 dark:border-red-900/40 dark:bg-red-950/30">
                    <span className="mt-0.5 h-1.5 w-1.5 shrink-0 rounded-full bg-red-500 dark:bg-red-400"/>
                    <p className="text-sm text-red-700 dark:text-red-400">{error}</p>
                </div>
            )}

            {/* Actions */}
            <div className="flex gap-2 pt-1">
                <Button
                    variant="outline"
                    size="sm"
                    className="flex-1"
                    onClick={onCancel}
                    disabled={saving}
                >
                    <ArrowLeft className="h-3.5 w-3.5"/>
                    Back
                </Button>
                <Button size="sm" className="flex-1" onClick={handleSave} disabled={saving}>
                    {saving ? (
                        <Loader2 className="h-3.5 w-3.5 animate-spin"/>
                    ) : (
                        <Save className="h-3.5 w-3.5"/>
                    )}
                    {saving ? "Saving…" : "Save Changes"}
                </Button>
            </div>
        </div>
    );
}
