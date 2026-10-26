"use client";

import { useEffect, useState } from "react";
import { AlertTriangle, CheckCircle2, Clock, Loader2 } from "lucide-react";
import { apiClient } from "@/lib/apiClient";
import { ABUSE_EMAIL } from "@/lib/siteConfig";
import {
    type AbuseStatus,
    ABUSE_STATUS_CHIP,
    ABUSE_STATUS_OWNER_LABEL,
} from "@/lib/abuseStatus";

type Entry = {
    id: string;
    status: AbuseStatus;
    createdAt: string;
    proofDeadlineAt: string;
    resolvedAt: string | null;
};

interface Props {
    websiteId: string;
}

/**
 * Surfaces abuse reports filed against the current website to its owner.
 * Hidden when the list is empty so uneventful sites don't clutter the
 * expand panel. Reporter email + free-form complaint text are intentionally
 * omitted — those live only in the admin console to prevent harassment.
 */
export function OwnerAbuseReportCard({ websiteId }: Props) {
    const [reports, setReports] = useState<Entry[] | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let alive = true;
        (async () => {
            try {
                const res = await apiClient(`/api/v1/websites/${websiteId}/abuse-reports`);
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                const body = (await res.json()) as Entry[];
                if (alive) setReports(body);
            } catch {
                if (alive) setReports([]);
            } finally {
                if (alive) setLoading(false);
            }
        })();
        return () => {
            alive = false;
        };
    }, [websiteId]);

    if (loading) {
        return (
            <div className="rounded-xl border border-border bg-card p-4">
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                    <Loader2 className="h-4 w-4 animate-spin" /> Loading reports…
                </div>
            </div>
        );
    }

    if (!reports || reports.length === 0) return null;

    const hasOpen = reports.some((r) => r.status === "PENDING" || r.status === "UPHELD");

    return (
        <div
            className={`rounded-xl border p-4 ${
                hasOpen
                    ? "border-orange-300 dark:border-orange-800/50 bg-orange-50 dark:bg-orange-950/30"
                    : "border-border bg-card"
            }`}
        >
            <div className="mb-3 flex items-center gap-2">
                <AlertTriangle
                    className={`h-4 w-4 shrink-0 ${
                        hasOpen ? "text-orange-600 dark:text-orange-400" : "text-muted-foreground"
                    }`}
                />
                <p className="text-sm font-semibold text-foreground">
                    Abuse reports ({reports.length})
                </p>
            </div>
            <ul className="space-y-2">
                {reports.map((r) => (
                    <li key={r.id} className="rounded-lg border border-border bg-background/60 px-3 py-2 text-xs">
                        <div className="flex flex-wrap items-center gap-2">
                            <span
                                className={`rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider ring-1 ${ABUSE_STATUS_CHIP[r.status]}`}
                            >
                                {ABUSE_STATUS_OWNER_LABEL[r.status]}
                            </span>
                            <span className="text-muted-foreground">
                                Filed {new Date(r.createdAt).toLocaleDateString()}
                            </span>
                            {r.status === "UPHELD" && r.proofDeadlineAt && (
                                <span className="flex items-center gap-1 text-orange-600 dark:text-orange-400">
                                    <Clock className="h-3 w-3" />
                                    Proof deadline {new Date(r.proofDeadlineAt).toLocaleString()}
                                </span>
                            )}
                            {r.resolvedAt && (
                                <span className="flex items-center gap-1 text-muted-foreground">
                                    <CheckCircle2 className="h-3 w-3" />
                                    Resolved {new Date(r.resolvedAt).toLocaleDateString()}
                                </span>
                            )}
                        </div>
                        {r.status === "UPHELD" && (
                            <p className="mt-1.5 text-[11px] text-muted-foreground">
                                Complete DNS TXT verification above to prove control
                                {ABUSE_EMAIL ? (
                                    <>
                                        , or contact{" "}
                                        <a href={`mailto:${ABUSE_EMAIL}`} className="underline">
                                            {ABUSE_EMAIL}
                                        </a>{" "}
                                        to appeal
                                    </>
                                ) : (
                                    ", or contact the instance administrator to appeal"
                                )}{" "}
                                before the deadline.
                            </p>
                        )}
                    </li>
                ))}
            </ul>
        </div>
    );
}
