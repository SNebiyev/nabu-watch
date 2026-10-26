"use client";

import {useCallback, useEffect, useState} from "react";
import {
    AlertCircle,
    CheckCircle2,
    ChevronDown,
    ExternalLink,
    Loader2,
    Plus,
    Send,
    Trash2,
    Webhook,
    X
} from "lucide-react";
import {apiClient} from "@/lib/apiClient";

// ── Types ──────────────────────────────────────────────────────────────────────

type WebhookType = "GENERIC" | "SLACK";

interface WebhookConfig {
    id: string;
    name: string;
    url: string;
    hasSecret: boolean;
    type: WebhookType;
    enabled: boolean;
    createdAt: string;
}

interface FormState {
    name: string;
    url: string;
    secret: string;
    type: WebhookType;
    enabled: boolean;
}

const EMPTY_FORM: FormState = {name: "", url: "", secret: "", type: "GENERIC", enabled: true};

// ── WebhookRow ─────────────────────────────────────────────────────────────────

function WebhookRow({
                        config,
                        onDelete,
                        onTest,
                        onToggle,
                    }: {
    config: WebhookConfig;
    onDelete: (id: string) => void;
    onTest: (id: string) => void;
    onToggle: (id: string, enabled: boolean) => void;
}) {
    const [deleting, setDeleting] = useState(false);
    const [testing, setTesting] = useState(false);
    const [testResult, setTestResult] = useState<"ok" | "err" | null>(null);

    const handleDelete = async () => {
        setDeleting(true);
        await onDelete(config.id);
        setDeleting(false);
    };

    const handleTest = async () => {
        setTesting(true);
        setTestResult(null);
        try {
            await onTest(config.id);
            setTestResult("ok");
        } catch {
            setTestResult("err");
        } finally {
            setTesting(false);
            setTimeout(() => setTestResult(null), 3000);
        }
    };

    const typeBadge = config.type === "SLACK"
        ? "bg-purple-500/10 text-purple-400 ring-1 ring-purple-500/20"
        : "bg-sky-500/10 text-sky-400 ring-1 ring-sky-500/20";

    return (
        <div
            className={`flex flex-col gap-3 rounded-xl border border-border bg-card p-4 transition-opacity ${!config.enabled ? "opacity-60" : ""}`}>
            <div className="flex items-start gap-3">
                <div
                    className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-sky-500/10 ring-1 ring-sky-500/20 mt-0.5">
                    <Webhook className="h-4 w-4 text-sky-400"/>
                </div>
                <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-sm font-semibold text-foreground">{config.name}</span>
                        <span className={`text-[11px] px-1.5 py-0.5 rounded-md font-medium ${typeBadge}`}>
                            {config.type}
                        </span>
                        {!config.enabled && (
                            <span
                                className="text-[11px] px-1.5 py-0.5 rounded-md font-medium bg-muted text-muted-foreground">
                                Disabled
                            </span>
                        )}
                    </div>
                    <p className="mt-0.5 text-xs text-muted-foreground truncate">{config.url}</p>
                    {config.hasSecret && (
                        <p className="mt-0.5 text-[11px] text-muted-foreground">Signed with secret</p>
                    )}
                </div>
            </div>

            <div className="flex items-center gap-2 flex-wrap">
                <button
                    onClick={handleTest}
                    disabled={testing || !config.enabled}
                    className="flex items-center gap-1.5 rounded-lg border border-border bg-secondary/50 px-3 py-1.5 text-xs font-medium text-foreground hover:bg-secondary transition-colors disabled:opacity-50"
                >
                    {testing ? <Loader2 className="h-3 w-3 animate-spin"/> : <Send className="h-3 w-3"/>}
                    Send test
                </button>
                {testResult === "ok" && (
                    <span className="flex items-center gap-1 text-xs text-emerald-400">
                        <CheckCircle2 className="h-3 w-3"/> Delivered
                    </span>
                )}
                {testResult === "err" && (
                    <span className="flex items-center gap-1 text-xs text-red-400">
                        <AlertCircle className="h-3 w-3"/> Failed
                    </span>
                )}

                <div className="flex-1"/>

                <button
                    onClick={() => onToggle(config.id, !config.enabled)}
                    className="rounded-lg border border-border bg-secondary/50 px-3 py-1.5 text-xs font-medium text-foreground hover:bg-secondary transition-colors"
                >
                    {config.enabled ? "Disable" : "Enable"}
                </button>

                <button
                    onClick={handleDelete}
                    disabled={deleting}
                    className="flex items-center gap-1.5 rounded-lg border border-red-900/40 bg-red-950/20 px-3 py-1.5 text-xs font-medium text-red-400 hover:bg-red-950/40 transition-colors disabled:opacity-50"
                >
                    {deleting ? <Loader2 className="h-3 w-3 animate-spin"/> : <Trash2 className="h-3 w-3"/>}
                    Delete
                </button>
            </div>
        </div>
    );
}

// ── AddWebhookModal ────────────────────────────────────────────────────────────

function AddWebhookModal({
                             onClose,
                             onSave,
                         }: {
    onClose: () => void;
    onSave: (form: FormState) => Promise<void>;
}) {
    const [form, setForm] = useState<FormState>(EMPTY_FORM);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const set = (key: keyof FormState, value: string | boolean) =>
        setForm(f => ({...f, [key]: value}));

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!form.name.trim() || !form.url.trim()) return;
        setSaving(true);
        setError(null);
        try {
            await onSave(form);
            onClose();
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to save webhook");
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
            <div className="w-full max-w-md rounded-2xl border border-border bg-card shadow-2xl">
                <div className="flex items-center justify-between border-b border-border px-5 py-4">
                    <h2 className="text-sm font-semibold text-foreground">Add webhook</h2>
                    <button onClick={onClose} className="text-muted-foreground hover:text-foreground transition-colors">
                        <X className="h-4 w-4"/>
                    </button>
                </div>

                <form onSubmit={handleSubmit} className="p-5 space-y-4">
                    {/* Type */}
                    <div className="space-y-1.5">
                        <label className="text-xs font-medium text-muted-foreground">Type</label>
                        <div className="flex gap-2">
                            {(["GENERIC", "SLACK"] as WebhookType[]).map(t => (
                                <button
                                    key={t}
                                    type="button"
                                    onClick={() => set("type", t)}
                                    className={`flex-1 rounded-lg border px-3 py-2 text-xs font-medium transition-colors ${
                                        form.type === t
                                            ? "border-sky-500/50 bg-sky-500/10 text-sky-400"
                                            : "border-border bg-secondary/50 text-muted-foreground hover:text-foreground"
                                    }`}
                                >
                                    {t === "SLACK" ? "Slack" : "Generic (HTTP)"}
                                </button>
                            ))}
                        </div>
                        {form.type === "SLACK" && (
                            <p className="text-[11px] text-muted-foreground">
                                Paste your{" "}
                                <a
                                    href="https://api.slack.com/messaging/webhooks"
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    className="text-sky-400 hover:underline inline-flex items-center gap-0.5"
                                >
                                    Slack Incoming Webhook URL <ExternalLink className="h-2.5 w-2.5"/>
                                </a>
                            </p>
                        )}
                    </div>

                    {/* Name */}
                    <div className="space-y-1.5">
                        <label className="text-xs font-medium text-muted-foreground">Name</label>
                        <input
                            type="text"
                            value={form.name}
                            onChange={e => set("name", e.target.value)}
                            placeholder="e.g. Slack #alerts"
                            maxLength={100}
                            required
                            className="w-full rounded-lg border border-border bg-secondary/50 px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-1 focus:ring-sky-500/50"
                        />
                    </div>

                    {/* URL */}
                    <div className="space-y-1.5">
                        <label className="text-xs font-medium text-muted-foreground">
                            {form.type === "SLACK" ? "Slack Webhook URL" : "Endpoint URL"}
                        </label>
                        <input
                            type="url"
                            value={form.url}
                            onChange={e => set("url", e.target.value)}
                            placeholder="https://"
                            maxLength={2048}
                            required
                            className="w-full rounded-lg border border-border bg-secondary/50 px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-1 focus:ring-sky-500/50"
                        />
                    </div>

                    {/* Secret (generic only) */}
                    {form.type === "GENERIC" && (
                        <div className="space-y-1.5">
                            <label className="text-xs font-medium text-muted-foreground">
                                Secret <span className="text-muted-foreground/60">(optional)</span>
                            </label>
                            <input
                                type="text"
                                value={form.secret}
                                onChange={e => set("secret", e.target.value)}
                                placeholder="Used for HMAC-SHA256 signature verification"
                                maxLength={255}
                                className="w-full rounded-lg border border-border bg-secondary/50 px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-1 focus:ring-sky-500/50"
                            />
                            <p className="text-[11px] text-muted-foreground">
                                Sent as <code className="font-mono text-sky-400">X-Nabu-Signature</code> header
                            </p>
                        </div>
                    )}

                    {error && (
                        <div
                            className="flex items-center gap-2 rounded-lg bg-red-950/40 px-3 py-2 ring-1 ring-red-900/40">
                            <AlertCircle className="h-3.5 w-3.5 shrink-0 text-red-400"/>
                            <p className="text-xs text-red-300">{error}</p>
                        </div>
                    )}

                    <div className="flex gap-2 pt-1">
                        <button
                            type="button"
                            onClick={onClose}
                            className="flex-1 rounded-lg border border-border bg-secondary/50 py-2 text-sm font-medium text-foreground hover:bg-secondary transition-colors"
                        >
                            Cancel
                        </button>
                        <button
                            type="submit"
                            disabled={saving || !form.name.trim() || !form.url.trim()}
                            className="flex-1 flex items-center justify-center gap-2 rounded-lg bg-sky-600 py-2 text-sm font-medium text-white hover:bg-sky-500 transition-colors disabled:opacity-50"
                        >
                            {saving && <Loader2 className="h-3.5 w-3.5 animate-spin"/>}
                            Add webhook
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}

// ── WebhooksPage ───────────────────────────────────────────────────────────────

export default function WebhooksPage() {
    const [configs, setConfigs] = useState<WebhookConfig[]>([]);
    const [loading, setLoading] = useState(true);
    const [showAdd, setShowAdd] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);

    const fetchConfigs = useCallback(async () => {
        try {
            const res = await apiClient("/api/v1/webhooks");
            setConfigs(await res.json());
        } catch {
            setError("Failed to load webhooks");
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchConfigs();
    }, [fetchConfigs]);

    const handleAdd = async (form: FormState) => {
        const res = await apiClient("/api/v1/webhooks", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({
                name: form.name.trim(),
                url: form.url.trim(),
                secret: form.secret.trim() || null,
                type: form.type,
            }),
        });
        const created: WebhookConfig = await res.json();
        setConfigs(c => [...c, created]);
        setSuccess("Webhook added");
        setTimeout(() => setSuccess(null), 3000);
    };

    const handleDelete = async (id: string) => {
        await apiClient(`/api/v1/webhooks/${id}`, {method: "DELETE"});
        setConfigs(c => c.filter(x => x.id !== id));
    };

    const handleTest = async (id: string) => {
        const res = await apiClient(`/api/v1/webhooks/${id}/test`, {method: "POST"});
        if (!res.ok) throw new Error("Delivery failed");
    };

    const handleToggle = async (id: string, enabled: boolean) => {
        const config = configs.find(c => c.id === id);
        if (!config) return;
        const res = await apiClient(`/api/v1/webhooks/${id}`, {
            method: "PUT",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({
                name: config.name,
                url: config.url,
                secret: null,
                type: config.type,
                enabled,
            }),
        });
        const updated: WebhookConfig = await res.json();
        setConfigs(c => c.map(x => x.id === id ? updated : x));
    };

    return (
        <div className="space-y-6">
            {/* Header */}
            <div className="flex items-start justify-between gap-4">
                <div>
                    <h1 className="text-xl font-semibold text-foreground">Webhooks</h1>
                    <p className="mt-1 text-sm text-muted-foreground">
                        Receive real-time HTTP POST notifications when scan results arrive.
                        Supports generic endpoints and Slack incoming webhooks.
                    </p>
                </div>
                <button
                    onClick={() => setShowAdd(true)}
                    className="flex shrink-0 items-center gap-1.5 rounded-lg bg-sky-600 px-3 py-2 text-sm font-medium text-white hover:bg-sky-500 transition-colors"
                >
                    <Plus className="h-4 w-4"/> Add webhook
                </button>
            </div>

            {/* Toast */}
            {success && (
                <div
                    className="flex items-center gap-2 rounded-lg bg-emerald-950/40 px-3 py-2 ring-1 ring-emerald-900/40">
                    <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-400"/>
                    <p className="text-xs text-emerald-300">{success}</p>
                </div>
            )}
            {error && (
                <div className="flex items-center gap-2 rounded-lg bg-red-950/40 px-3 py-2 ring-1 ring-red-900/40">
                    <AlertCircle className="h-3.5 w-3.5 shrink-0 text-red-400"/>
                    <p className="text-xs text-red-300">{error}</p>
                </div>
            )}

            {/* Content */}
            {loading ? (
                <div className="flex justify-center py-12">
                    <Loader2 className="h-5 w-5 animate-spin text-muted-foreground"/>
                </div>
            ) : configs.length === 0 ? (
                <div
                    className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-16 text-center">
                    <Webhook className="h-8 w-8 text-muted-foreground/40 mb-3"/>
                    <p className="text-sm font-medium text-muted-foreground">No webhooks configured</p>
                    <p className="mt-1 text-xs text-muted-foreground/60">
                        Add a webhook to start receiving scan notifications.
                    </p>
                    <button
                        onClick={() => setShowAdd(true)}
                        className="mt-4 flex items-center gap-1.5 rounded-lg bg-sky-600 px-3 py-2 text-sm font-medium text-white hover:bg-sky-500 transition-colors"
                    >
                        <Plus className="h-4 w-4"/> Add your first webhook
                    </button>
                </div>
            ) : (
                <div className="space-y-3">
                    {configs.map(config => (
                        <WebhookRow
                            key={config.id}
                            config={config}
                            onDelete={handleDelete}
                            onTest={handleTest}
                            onToggle={handleToggle}
                        />
                    ))}
                </div>
            )}

            {/* Payload reference */}
            {configs.length > 0 && (
                <div className="rounded-xl border border-border bg-secondary/20 p-4">
                    <div className="flex items-center gap-2 mb-3">
                        <ChevronDown className="h-3.5 w-3.5 text-muted-foreground"/>
                        <span className="text-xs font-medium text-muted-foreground">Generic payload example</span>
                    </div>
                    <pre className="text-[11px] text-sky-300/80 font-mono leading-relaxed overflow-x-auto">{`{
  "event":          "scan.result",
  "scanLogId":      "uuid",
  "monitorType":    "COMPLIANCE",
  "subType":        "PRIVACY_POLICY",
  "websiteUrl":     "https://example.com",
  "severity":       "MEDIUM",
  "status":         "PASSING",
  "scannedAt":      "2026-04-02T12:00:00Z"
}`}</pre>
                </div>
            )}

            {showAdd && (
                <AddWebhookModal onClose={() => setShowAdd(false)} onSave={handleAdd}/>
            )}
        </div>
    );
}
