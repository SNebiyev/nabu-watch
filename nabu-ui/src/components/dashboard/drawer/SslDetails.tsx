import React from "react";
import {RemediationTip} from "./ComplianceDetails";

// ── Shared scan-failure banner ────────────────────────────────────────────────

export function ScanFailedBanner({error}: { error: string }) {
    return (
        <div
            className="mt-3 rounded-xl bg-red-50 p-3 md:p-4 ring-1 ring-red-200 dark:bg-red-950/40 dark:ring-red-900/40">
            <p className="text-xs md:text-sm font-semibold text-red-700 dark:text-red-400">Scan Failed</p>
            <p className="mt-1 text-xs md:text-sm text-red-600/80 break-words dark:text-red-300/80">{error}</p>
        </div>
    );
}

// ── Key/value row primitive ───────────────────────────────────────────────────

export function DetailRow({
                              label,
                              value,
                          }: {
    label: string;
    value: React.ReactNode;
}) {
    return (
        <div className="flex items-start justify-between gap-3 py-1.5 md:py-2">
      <span className="shrink-0 text-xs md:text-sm text-[--muted-foreground]">
        {label}
      </span>
            <span className="text-right text-xs md:text-sm text-[--foreground] break-all">
        {value}
      </span>
        </div>
    );
}

// ── SSL certificate detail panel ──────────────────────────────────────────────

export function SslDetails({result}: { result: Record<string, unknown> }) {
    if ((result.status as string) === "FAILED" || result.error != null) {
        return <ScanFailedBanner error={String(result.error ?? "Unknown error")}/>;
    }

    const daysRaw = result.days_remaining;
    const days = daysRaw != null ? Math.round(Number(daysRaw)) : null;
    const expiry = result.expiry_date as string | undefined;
    const issuer = result.issuer as string | undefined;
    const subject = result.subject as string | undefined;

    const daysColor =
        days == null
            ? ""
            : days <= 0
                ? "font-semibold text-red-600 dark:text-red-400"
                : days <= 14
                    ? "font-semibold text-yellow-600 dark:text-yellow-400"
                    : "font-semibold text-green-600 dark:text-green-400";

    return (
        <div className="mt-4">
            {days != null && (
                <div className="mb-3">
                    <div className="flex items-center gap-2 rounded-xl bg-secondary/40 px-4 py-3 ring-1 ring-border">
                        <span className="text-xs md:text-sm text-[--muted-foreground]">
                            Days remaining
                        </span>
                        <span className={`ml-auto text-sm md:text-base ${daysColor}`}>
                            {days <= 0 ? "EXPIRED" : `${days} days`}
                        </span>
                    </div>
                    {days <= 0 && <RemediationTip guidanceKey="SSL_EXPIRED"/>}
                    {days > 0 && days <= 30 && <RemediationTip guidanceKey="SSL_EXPIRING"/>}
                </div>
            )}
            <div
                className="divide-y divide-border rounded-xl bg-secondary/30 px-4 ring-1 ring-border md:grid md:grid-cols-2 md:divide-y-0 md:divide-x md:divide-border">
                <div className="md:pr-4">
                    {issuer && <DetailRow label="Issuer" value={issuer}/>}
                    {subject && <DetailRow label="Subject" value={subject}/>}
                </div>
                {expiry && (
                    <div className="md:pl-4">
                        <DetailRow label="Expiry Date" value={expiry}/>
                    </div>
                )}
            </div>
        </div>
    );
}
