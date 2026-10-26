"use client";

import {useCallback, useEffect, useState} from "react";
import {Check, ChevronDown, Copy, Loader2, ShieldCheck, TriangleAlert} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {extractErrorMessage} from "@/lib/utils";
import {useCountdown} from "@/hooks/useCountdown";
import {DOMAIN_VERIFIED_EVENT, type DomainVerifiedDetail} from "./drawer/useVerifiedAt";

interface VerifyDomainResponse {
    status: "PENDING" | "VERIFIED" | "EXPIRED" | "UNVERIFIED";
    recordName: string;
    recordValue: string;
    createdAt: string;
    verifiedAt: string | null;
    expiresAt: string;
    lastCheckedAt: string | null;
    checkCount: number;
    failureReason: string | null;
}

interface Props {
    websiteId: string;
}

/**
 * DNS TXT ownership verification card shown on the website expand panel.
 *
 * Hidden once the website is VERIFIED — verification is a one-time proof of
 * ownership at a point in time, so there's no reason to keep prompting the
 * user after success.
 */
export function VerifyDomainCard({websiteId}: Props) {
    const [data, setData] = useState<VerifyDomainResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [checking, setChecking] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [copied, setCopied] = useState<"name" | "value" | null>(null);
    const storageKey = `verify-card-expanded:${websiteId}`;
    const [expanded, setExpandedState] = useState<boolean>(() => {
        if (typeof window === "undefined") return true;
        const stored = window.localStorage.getItem(storageKey);
        return stored === null ? true : stored === "1";
    });
    const setExpanded = useCallback((next: boolean) => {
        setExpandedState(next);
        try { window.localStorage.setItem(storageKey, next ? "1" : "0"); } catch { /* ignore quota */ }
    }, [storageKey]);

    const load = useCallback(async () => {
        try {
            const res = await apiClient(`/api/v1/websites/${websiteId}/verification`);
            if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to load verification"));
            setData(await res.json());
        } catch (e) {
            setError(e instanceof Error ? e.message : "Failed to load verification");
        } finally {
            setLoading(false);
        }
    }, [websiteId]);

    useEffect(() => { void load(); }, [load]);

    async function runCheck() {
        setChecking(true);
        setError(null);
        try {
            const res = await apiClient(`/api/v1/websites/${websiteId}/verification/check`, {method: "POST"});
            if (!res.ok) throw new Error(await extractErrorMessage(res, "Verification check failed"));
            const next: VerifyDomainResponse = await res.json();
            setData(next);
            if (next.status === "VERIFIED" && next.verifiedAt) {
                const detail: DomainVerifiedDetail = {websiteId, verifiedAt: next.verifiedAt};
                window.dispatchEvent(new CustomEvent(DOMAIN_VERIFIED_EVENT, {detail}));
            }
        } catch (e) {
            setError(e instanceof Error ? e.message : "Verification check failed");
        } finally {
            setChecking(false);
        }
    }

    async function copy(value: string, kind: "name" | "value") {
        try {
            await navigator.clipboard.writeText(value);
            setCopied(kind);
            setTimeout(() => setCopied(null), 1500);
        } catch {
            /* clipboard denied — no visual feedback, best-effort */
        }
    }

    // Self-updating countdown (ticks every 30s). `null` once past expiry.
    const remaining = useCountdown(data?.expiresAt);

    if (loading) {
        return (
            <div className="rounded-xl border border-border bg-card p-4">
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                    <Loader2 className="h-4 w-4 animate-spin"/> Loading verification…
                </div>
            </div>
        );
    }

    // Post-VERIFIED: compact badge, no card body.
    if (data?.status === "VERIFIED") {
        return (
            <div className="flex items-center gap-2 rounded-xl border border-emerald-300 dark:border-emerald-800/50 bg-emerald-50 dark:bg-emerald-950/30 px-3 py-2 text-xs text-emerald-700 dark:text-emerald-300">
                <ShieldCheck className="h-4 w-4 shrink-0"/>
                Domain ownership verified
                {data.verifiedAt && <span className="text-emerald-700/70 dark:text-emerald-300/70">· {new Date(data.verifiedAt).toLocaleDateString()}</span>}
            </div>
        );
    }

    if (!data) {
        if (error) {
            return (
                <details className="group rounded-xl border border-red-300 dark:border-red-800/50 bg-red-50 dark:bg-red-950/30 text-xs text-red-700 dark:text-red-300">
                    <summary className="flex cursor-pointer items-center gap-2 px-3 py-2 list-none">
                        <TriangleAlert className="h-4 w-4 shrink-0"/>
                        <span className="flex-1 truncate">Verification unavailable</span>
                        <ChevronDown className="h-4 w-4 shrink-0 opacity-60 transition-transform group-open:rotate-180"/>
                    </summary>
                    <p className="px-3 pb-3 pt-0">{error}</p>
                </details>
            );
        }
        return null;
    }

    const expired = data.status === "EXPIRED";

    return (
        <div className={`rounded-xl border ${expanded ? "p-4" : "px-3 py-2"} ${expired
            ? "border-amber-300 dark:border-amber-800/50 bg-amber-50 dark:bg-amber-950/30"
            : "border-sky-300 dark:border-sky-800/50 bg-sky-50 dark:bg-sky-950/30"}`}>
            <button
                type="button"
                onClick={() => setExpanded(!expanded)}
                className={`flex w-full items-center gap-2 text-left ${expanded ? "mb-3" : ""}`}
                aria-expanded={expanded}
            >
                {expired
                    ? <TriangleAlert className="h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400"/>
                    : <ShieldCheck className="h-4 w-4 shrink-0 text-sky-600 dark:text-sky-400"/>}
                <div className="flex-1 min-w-0">
                    <p className="text-sm font-semibold text-foreground truncate">
                        {expired ? "Verification expired" : "Verify domain ownership"}
                    </p>
                    {expanded && (
                        <p className="text-[11px] text-muted-foreground">
                            {expired
                                ? "The verification window has passed. Add the TXT record below and click \"Check now\" to try again."
                                : remaining
                                    ? `Add a DNS TXT record to unlock the verified badge. Expires in ${remaining}.`
                                    : "Add a DNS TXT record to unlock the verified badge."}
                        </p>
                    )}
                </div>
                <ChevronDown className={`h-4 w-4 shrink-0 text-muted-foreground transition-transform ${expanded ? "rotate-180" : ""}`}/>
            </button>

            {!expanded && null}
            {expanded && (<>

            <div className="space-y-2 rounded-lg border border-border bg-background/60 p-3 text-xs">
                <Row label="Record type" value="TXT"/>
                <RowCopy label="Name" value={data.recordName}
                         copied={copied === "name"} onCopy={() => copy(data.recordName, "name")}/>
                <RowCopy label="Value" value={data.recordValue}
                         copied={copied === "value"} onCopy={() => copy(data.recordValue, "value")}/>
                <p className="text-[10px] text-muted-foreground/80 pt-1">
                    DNS propagation can take a few minutes. We also re-check every 6 hours automatically.
                </p>
            </div>

            {error && (
                <p className="mt-2 text-xs text-red-600 dark:text-red-400">{error}</p>
            )}
            {!error && data.failureReason && (
                <p className="mt-2 text-[11px] text-muted-foreground">
                    Last check: {data.failureReason}
                </p>
            )}

            <div className="mt-3 flex items-center gap-2">
                <button
                    onClick={runCheck}
                    disabled={checking}
                    className="inline-flex items-center gap-1.5 rounded-lg bg-sky-600 hover:bg-sky-700 text-white text-xs font-medium px-3 py-1.5 disabled:opacity-60 disabled:cursor-not-allowed"
                >
                    {checking ? <Loader2 className="h-3.5 w-3.5 animate-spin"/> : <ShieldCheck className="h-3.5 w-3.5"/>}
                    Check now
                </button>
                {data.checkCount > 0 && (
                    <span className="text-[11px] text-muted-foreground">
                        {data.checkCount} check{data.checkCount === 1 ? "" : "s"} so far
                    </span>
                )}
            </div>
            </>)}
        </div>
    );
}

function Row({label, value}: {label: string; value: string}) {
    return (
        <div className="flex items-center gap-3">
            <span className="w-20 shrink-0 text-muted-foreground">{label}</span>
            <span className="font-mono text-foreground">{value}</span>
        </div>
    );
}

function RowCopy({label, value, copied, onCopy}: {label: string; value: string; copied: boolean; onCopy: () => void}) {
    // Stack label → value → copy on narrow screens so the long TXT string
    // has a full row to itself; switch to inline at sm+.
    return (
        <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:gap-3">
            <span className="sm:w-20 sm:shrink-0 text-muted-foreground">{label}</span>
            <div className="flex items-center gap-2 min-w-0">
                <code className="flex-1 min-w-0 break-all sm:truncate rounded bg-secondary/60 px-2 py-1 font-mono text-foreground">{value}</code>
                <button
                    onClick={onCopy}
                    className="flex h-6 w-6 shrink-0 items-center justify-center rounded hover:bg-secondary text-muted-foreground hover:text-foreground"
                    title="Copy"
                >
                    {copied ? <Check className="h-3.5 w-3.5 text-emerald-600"/> : <Copy className="h-3.5 w-3.5"/>}
                </button>
            </div>
        </div>
    );
}
