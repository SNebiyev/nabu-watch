"use client";

import {useEffect, useRef, useState} from "react";
import {useRouter} from "next/navigation";
import {
    AlertCircle,
    Camera,
    ChevronRight,
    Clock,
    Globe,
    PauseCircle,
    Shield,
    ShieldAlert,
    ShieldCheck,
    Timer
} from "lucide-react";
import {formatCountdown, formatRelativeTime} from "@/lib/utils";
import type {MonitorHealth, MonitorStatus, WebsiteHealth} from "@/lib/types";
import {GradeBadge} from "@/components/dashboard/GradeBadge";
import {WebsiteExpandPanel} from "@/components/dashboard/WebsiteExpandPanel";

// ── Derived status ─────────────────────────────────────────────────────────────

type EffectiveStatus = MonitorStatus | "WARNING";

function deriveEffectiveStatus(site: WebsiteHealth): EffectiveStatus {
    if (site.status === "FAILING") return "FAILING";
    if (site.status === "PASSING") {
        const hasWarning = site.monitors.some((m) => m.active && m.effectiveStatus === "WARNING");
        return hasWarning ? "WARNING" : "PASSING";
    }
    return site.status;
}

// ── Short labels ───────────────────────────────────────────────────────────────

const SUBTYPE_SHORT: Record<string, string> = {
    SSL: "SSL",
    PRIVACY_POLICY: "PP",
    TERMS_OF_SERVICE: "ToS",
    IMPRESSUM: "IMP",
    COOKIE_POLICY: "CKP",
    GDPR_REQUEST: "GDPR",
    COOKIE_BANNER: "CB",
    TRACKER_DETECTION: "TRK",
};

// ── Status dot with glow ───────────────────────────────────────────────────────

function GlowDot({status}: { status: EffectiveStatus }) {
    if (status === "PASSING")
        return (
            <span className="relative flex h-2.5 w-2.5 shrink-0">
                <span
                    className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-30"/>
                <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-green-500 dot-glow-green"/>
            </span>
        );
    if (status === "WARNING")
        return (
            <span className="relative flex h-2.5 w-2.5 shrink-0">
                <span
                    className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-30"/>
                <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-amber-500 dot-glow-amber"/>
            </span>
        );
    if (status === "FAILING")
        return <span className="h-2.5 w-2.5 shrink-0 rounded-full bg-red-500 dot-glow-red block"/>;
    return <span className="h-2.5 w-2.5 shrink-0 rounded-full bg-slate-300 dark:bg-slate-600 block"/>;
}

// ── Monitor sort weight ────────────────────────────────────────────────────────

function monitorSortWeight(m: MonitorHealth): number {
    if (m.effectiveStatus === "FAILING") return 0;
    if (m.effectiveStatus === "WARNING") return 1;
    if (m.effectiveStatus === "PASSING") return 2;
    return 3;
}

// ── Monitor pill ───────────────────────────────────────────────────────────────

function MonitorPill({monitor}: { monitor: MonitorHealth }) {
    const label = SUBTYPE_SHORT[monitor.subType] ?? monitor.subType;

    let colorClass = "bg-secondary text-muted-foreground";

    if (monitor.effectiveStatus === "WARNING") {
        colorClass = "bg-amber-100 text-amber-700 ring-1 ring-amber-200 dark:bg-amber-950/60 dark:text-amber-400 dark:ring-amber-800/40";
    } else if (monitor.effectiveStatus === "FAILING") {
        colorClass = "bg-red-100 text-red-700 ring-1 ring-red-200 dark:bg-red-950/60 dark:text-red-400 dark:ring-red-900/40";
    } else if (monitor.effectiveStatus === "PASSING") {
        colorClass = "bg-green-100 text-green-700 ring-1 ring-green-200 dark:bg-green-950/60 dark:text-green-400 dark:ring-green-900/40";
    }

    return (
        <span
            className={`inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 text-[10px] font-medium transition-colors ${colorClass}`}>
            {label}
            {monitor.hasOverride && (
                <span title={monitor.overrideMismatch ? "Override mismatch" : "Status overridden"}>
                    <ShieldAlert className="h-2.5 w-2.5 shrink-0 text-amber-500 dark:text-amber-400"/>
                </span>
            )}
        </span>
    );
}

// ── Status cell ────────────────────────────────────────────────────────────────

function StatusCell({
                        effectiveStatus,
                        failingCount,
                        warningCount,
                    }: {
    effectiveStatus: EffectiveStatus;
    failingCount: number;
    warningCount: number;
}) {
    if (failingCount > 0) {
        return (
            <span className="inline-flex items-center gap-1.5 text-xs font-medium text-red-600 dark:text-red-400">
                <ShieldAlert className="h-3.5 w-3.5 shrink-0"/>
                {failingCount} issue{failingCount > 1 ? "s" : ""}
            </span>
        );
    }
    if (warningCount > 0) {
        return (
            <span className="inline-flex items-center gap-1.5 text-xs font-medium text-amber-600 dark:text-amber-400">
                <AlertCircle className="h-3.5 w-3.5 shrink-0"/>
                {warningCount} warning{warningCount > 1 ? "s" : ""}
            </span>
        );
    }
    if (effectiveStatus === "PASSING") {
        return (
            <span className="inline-flex items-center gap-1.5 text-xs font-medium text-green-600 dark:text-green-400">
                <ShieldCheck className="h-3.5 w-3.5 shrink-0"/>
                Passing
            </span>
        );
    }
    return (
        <span className="inline-flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
            <Clock className="h-3.5 w-3.5 shrink-0"/>
            Pending
        </span>
    );
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

function RowSkeleton() {
    return (
        <div className="flex animate-pulse items-center gap-4 px-5 py-4">
            <div className="h-2.5 w-2.5 rounded-full bg-slate-200 dark:bg-slate-700"/>
            <div className="h-4 w-44 rounded-lg bg-slate-200 dark:bg-slate-700"/>
            <div className="h-4 w-10 rounded-lg bg-slate-200 dark:bg-slate-700 ml-auto"/>
            <div className="flex gap-1.5">
                <div className="h-5 w-10 rounded-full bg-slate-200 dark:bg-slate-700"/>
                <div className="h-5 w-10 rounded-full bg-slate-200 dark:bg-slate-700"/>
            </div>
            <div className="h-4 w-16 rounded-lg bg-slate-200 dark:bg-slate-700"/>
            <div className="h-4 w-4 rounded bg-slate-200 dark:bg-slate-700"/>
        </div>
    );
}

// ── Empty state ───────────────────────────────────────────────────────────────

function EmptyState() {
    return (
        <div className="flex flex-col items-center justify-center gap-5 py-20 text-center">
            <div className="relative">
                <div className="absolute inset-0 rounded-full bg-primary/5 blur-xl"/>
                <div
                    className="relative flex h-16 w-16 items-center justify-center rounded-2xl border border-border bg-secondary/50">
                    <svg className="h-8 w-8 text-muted-foreground" fill="none" viewBox="0 0 24 24"
                         stroke="currentColor" strokeWidth={1.2}>
                        <circle cx="12" cy="12" r="10"/>
                        <path d="M12 2a14.5 14.5 0 0 1 0 20M12 2a14.5 14.5 0 0 0 0 20M2 12h20"/>
                    </svg>
                </div>
            </div>
            <div>
                <p className="text-sm font-semibold text-foreground">No websites yet</p>
                <p className="mt-1 text-sm text-muted-foreground">
                    Add your first website to start monitoring SSL &amp; compliance
                </p>
            </div>
        </div>
    );
}

// ── Next scan countdown ──────────────────────────────────────────────────────

function NextScanCountdown({monitors}: { monitors: MonitorHealth[] }) {
    const earliest = monitors
        .filter((m) => m.active && m.nextScanAt)
        .map((m) => new Date(m.nextScanAt!).getTime())
        .reduce((min, t) => Math.min(min, t), Infinity);

    const dateStr = earliest < Infinity ? new Date(earliest).toISOString() : null;

    const [label, setLabel] = useState(() => formatCountdown(dateStr));

    useEffect(() => {
        setLabel(formatCountdown(dateStr));
        if (!dateStr) return;
        const id = setInterval(() => setLabel(formatCountdown(dateStr)), 30_000);
        return () => clearInterval(id);
    }, [dateStr]);

    const isOverdue = !label && dateStr && new Date(dateStr).getTime() < Date.now();

    if (!label && !isOverdue) return null;

    if (isOverdue) {
        return (
            <span className="inline-flex items-center gap-1 text-[11px] text-amber-600 dark:text-amber-400"
                  title="Scan is queued and will run automatically when its turn comes.">
                <Clock className="h-2.5 w-2.5 shrink-0"/>
                Overdue
            </span>
        );
    }

    return (
        <span className="inline-flex items-center gap-1 text-[11px] text-muted-foreground"
              title={`Next scan: ${new Date(earliest).toLocaleString()}`}>
            <Timer className="h-2.5 w-2.5 shrink-0"/>
            {label}
        </span>
    );
}

// ── Main component ────────────────────────────────────────────────────────────

interface WebsiteListProps {
    websites: WebsiteHealth[];
    loading?: boolean;
    expandedWebsiteId?: string | null;
    onToggleExpand?: (website: WebsiteHealth) => void;
    onMonitorsUpdated?: () => void;
}

export function WebsiteList({
                                websites,
                                loading,
                                expandedWebsiteId,
                                onToggleExpand,
                                onMonitorsUpdated
                            }: WebsiteListProps) {
    const router = useRouter();
    const expandRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (expandedWebsiteId && expandRef.current) {
            // Delay scroll slightly so the panel has time to mount and the layout settles
            const timer = setTimeout(() => {
                expandRef.current?.scrollIntoView({behavior: "smooth", block: "start"});
            }, 50);
            return () => clearTimeout(timer);
        }
    }, [expandedWebsiteId]);

    useEffect(() => {
        if (loading || websites.length === 0) return;
        let y: number | null = null;
        try {
            const raw = sessionStorage.getItem("nabu:dashScrollY");
            if (raw !== null) {
                y = Number(raw);
                sessionStorage.removeItem("nabu:dashScrollY");
            }
        } catch { /* ignore storage errors */ }
        if (y !== null && Number.isFinite(y)) {
            requestAnimationFrame(() => window.scrollTo({top: y!, behavior: "auto"}));
        }
    }, [loading, websites.length]);

    if (loading) {
        return (
            <div className="glass-card overflow-hidden">
                <div
                    className="hidden md:grid md:grid-cols-[72px_minmax(0,1.5fr)_52px_minmax(0,1fr)_130px_32px] items-center border-b border-border/50 px-5 py-2.5 gap-3">
                    {["Region", "Website", "Grade", "Monitors", "Status", ""].map((h, i) => (
                        <span key={i}
                              className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">{h}</span>
                    ))}
                </div>
                {Array.from({length: 3}).map((_, i) => <RowSkeleton key={i}/>)}
            </div>
        );
    }

    if (websites.length === 0) {
        return (
            <div className="glass-card overflow-hidden">
                <EmptyState/>
            </div>
        );
    }

    return (
        <div className="glass-card isolate [&>*:first-child]:rounded-t-[inherit] [&>*:last-child]:rounded-b-[inherit]">
            {/* ── Desktop table header ─────────────────────────────────── */}
            <div
                className="hidden md:grid md:grid-cols-[72px_minmax(0,1.5fr)_52px_minmax(0,1fr)_130px_32px] items-center border-b border-border/50 px-5 py-2.5 gap-3">
                <span
                    className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">Region</span>
                <span
                    className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">Website</span>
                <span
                    className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground text-center">Grade</span>
                <span
                    className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground pl-4">Monitors</span>
                <span
                    className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">Status</span>
                <span/>
            </div>

            {/* ── Rows ─────────────────────────────────────────────────── */}
            {websites.map((site) => {
                const effectiveStatus = deriveEffectiveStatus(site);
                const isAllInactive =
                    site.monitors.length > 0 && site.monitors.every((m) => !m.active);
                const failingCount = site.monitors.filter((m) => m.active && m.effectiveStatus === "FAILING").length;
                const warningCount = site.monitors.filter((m) => m.active && m.effectiveStatus === "WARNING").length;
                const hasLangIssue = site.monitors.some((m) => m.active && m.languageInconsistency);
                const hasWafBlock = site.monitors.some((m) => m.active && m.wafBlocked);
                const isPaused = site.websiteStatus === "PAUSED";
                const displayUrl = site.url.replace(/^https?:\/\//, "");

                return (
                    <div key={site.id} ref={expandedWebsiteId === site.id ? expandRef : undefined} className="[&:not(:first-child)]:border-t border-slate-200 dark:border-slate-700/80">
                    <button
                        onClick={() => {
                            try {
                                sessionStorage.setItem("nabu:dashScrollY", String(window.scrollY));
                            } catch { /* ignore storage errors */ }
                            router.push(`/dashboard/websites/${site.id}`);
                        }}
                        aria-expanded={expandedWebsiteId === site.id}
                        className={`group w-full text-left transition-all duration-200 ease-out hover:bg-secondary/40 active:bg-secondary/60 ${isAllInactive ? "opacity-40 grayscale" : ""} ${expandedWebsiteId === site.id ? "bg-primary/[0.06] dark:bg-primary/[0.12] border-l-4 border-l-primary/40" : ""}`}
                    >
                        {/* ── Desktop row ──────────────────────────────── */}
                        <div
                            className="hidden md:grid md:grid-cols-[72px_minmax(0,1.5fr)_52px_minmax(0,1fr)_130px_32px] items-center px-5 py-3 gap-3">
                            {/* Region */}
                            <span
                                className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                                {site.region || "—"}
                            </span>

                            {/* URL + last scan */}
                            <div className="min-w-0 flex items-center gap-2.5">
                                <GlowDot status={effectiveStatus}/>
                                <div className="min-w-0">
                                    <div className="flex items-center gap-1.5 min-w-0">
                                        <p className="truncate text-sm font-medium text-foreground group-hover:text-primary transition-colors">
                                            {displayUrl}
                                        </p>
                                        {isPaused && (
                                            <span title="Monitoring paused"
                                                  className="inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium bg-slate-100 text-slate-500 ring-1 ring-slate-200 dark:bg-slate-800/60 dark:text-slate-400 dark:ring-slate-700/60">
                                                <PauseCircle className="h-2.5 w-2.5"/>
                                                Paused
                                            </span>
                                        )}
                                        {hasLangIssue && (
                                            <span title="Language inconsistency detected"
                                                  className="inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium bg-amber-100 text-amber-700 ring-1 ring-amber-200 dark:bg-amber-950/50 dark:text-amber-400 dark:ring-amber-800/40">
                                                <Globe className="h-2.5 w-2.5"/>
                                                Lang
                                            </span>
                                        )}
                                        {hasWafBlock && (
                                            <span title="Firewall blocking scans"
                                                  className="inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium bg-red-100 text-red-700 ring-1 ring-red-200 dark:bg-red-950/50 dark:text-red-400 dark:ring-red-800/40">
                                                <Shield className="h-2.5 w-2.5"/>
                                                WAF
                                            </span>
                                        )}
                                        {site.screenshotsEnabled && (
                                            <span title="Screenshot capture enabled"
                                                  className="inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium bg-violet-100 text-violet-700 ring-1 ring-violet-200 dark:bg-violet-950/60 dark:text-violet-300 dark:ring-violet-500/30">
                                                <Camera className="h-2.5 w-2.5"/>
                                            </span>
                                        )}
                                        <VerificationBadge status={site.verificationStatus}/>
                                    </div>
                                    <p className="mt-0.5 flex items-center gap-2 text-[11px] text-muted-foreground">
                                        <span>
                                            {site.status === "PENDING"
                                                ? "Awaiting first scan"
                                                : formatRelativeTime(site.lastCheckedAt)}
                                        </span>
                                        {site.status !== "PENDING" && (
                                            <NextScanCountdown monitors={site.monitors}/>
                                        )}
                                    </p>
                                </div>
                            </div>

                            {/* Compliance grade */}
                            <div className="flex justify-center">
                                <GradeBadge grade={site.complianceGrade} score={site.complianceScore}/>
                            </div>

                            {/* Monitor pills */}
                            <div className="flex items-center gap-1 flex-wrap pl-4">
                                {site.monitors
                                    .filter((m) => m.active)
                                    .toSorted((a, b) => monitorSortWeight(a) - monitorSortWeight(b))
                                    .map((m) => <MonitorPill key={m.id} monitor={m}/>)}
                            </div>

                            {/* Status */}
                            <div className="flex justify-start">
                                <StatusCell effectiveStatus={effectiveStatus} failingCount={failingCount}
                                            warningCount={warningCount}/>
                            </div>

                            <ChevronRight
                                className={`h-4 w-4 text-muted-foreground/30 transition-all duration-200 group-hover:text-primary ${expandedWebsiteId === site.id ? "rotate-90" : "group-hover:translate-x-0.5"}`}/>
                        </div>

                        {/* ── Mobile row ─────────────────────── */}
                        <div className="flex items-center gap-3 px-4 py-3 md:hidden">
                            <div
                                className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-border bg-secondary/50">
                                <Globe className="h-4 w-4 text-muted-foreground"/>
                            </div>
                            <div className="min-w-0 flex-1">
                                <div className="flex items-center gap-1.5 flex-wrap min-w-0">
                                    <p className="truncate text-sm font-medium text-foreground">{displayUrl}</p>
                                    {isPaused && (
                                        <span
                                            className="inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium bg-slate-100 text-slate-500 ring-1 ring-slate-200 dark:bg-slate-800/60 dark:text-slate-400 dark:ring-slate-700/60">
                                            <PauseCircle className="h-2.5 w-2.5"/>
                                            Paused
                                        </span>
                                    )}
                                    {site.screenshotsEnabled && (
                                        <span title="Screenshot capture enabled"
                                              className="inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium bg-violet-100 text-violet-700 ring-1 ring-violet-200 dark:bg-violet-950/60 dark:text-violet-300 dark:ring-violet-500/30">
                                            <Camera className="h-2.5 w-2.5"/>
                                        </span>
                                    )}
                                    {hasWafBlock && (
                                        <span title="Firewall blocking scans"
                                              className="inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium bg-red-100 text-red-700 ring-1 ring-red-200 dark:bg-red-950/50 dark:text-red-400 dark:ring-red-800/40">
                                            <Shield className="h-2.5 w-2.5"/>
                                            WAF
                                        </span>
                                    )}
                                    <VerificationBadge status={site.verificationStatus}/>
                                </div>
                                <p className="mt-0.5 text-xs text-muted-foreground">
                                    {site.region || "—"}
                                    {site.monitors.length > 0 && ` · ${site.monitors.filter(m => m.active).length} monitors`}
                                </p>
                            </div>
                            <div className="shrink-0 flex flex-col items-end gap-1">
                                <div className="flex items-center gap-1.5">
                                    <GradeBadge grade={site.complianceGrade} score={site.complianceScore}/>
                                    <StatusCell effectiveStatus={effectiveStatus} failingCount={failingCount}
                                                warningCount={warningCount}/>
                                </div>
                                {site.lastCheckedAt && (
                                    <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                                        <span>{formatRelativeTime(site.lastCheckedAt)}</span>
                                        <NextScanCountdown monitors={site.monitors}/>
                                    </div>
                                )}
                            </div>
                            <ChevronRight
                                className={`h-4 w-4 shrink-0 text-muted-foreground/30 transition-all duration-200 group-hover:text-primary ${expandedWebsiteId === site.id ? "rotate-90" : "group-hover:translate-x-0.5"}`}/>
                        </div>
                    </button>
                        {expandedWebsiteId === site.id && (
                            <div
                                className="animate-in fade-in slide-in-from-top-2 duration-200 border-l-4 border-l-primary/40"
                            >
                                <WebsiteExpandPanel
                                    website={site}
                                    onClose={() => onToggleExpand?.(site)}
                                    onMonitorsUpdated={onMonitorsUpdated}
                                />
                            </div>
                        )}
                    </div>
                );
            })}
        </div>
    );
}

/**
 * Dashboard-row chip that surfaces DNS TXT ownership-verification state.
 * Clicking the row opens the expand panel where the full VerifyDomainCard
 * lives with copy-paste TXT instructions.
 */
function VerificationBadge({status}: {status?: "UNVERIFIED" | "PENDING" | "VERIFIED" | "EXPIRED"}) {
    if (!status || status === "VERIFIED") return null;
    const copy =
        status === "EXPIRED"
            ? {label: "Verify expired", title: "DNS TXT window lapsed — open to re-verify"}
            : {label: "Verify domain", title: "Add a DNS TXT record to get the verified badge"};
    const style =
        status === "EXPIRED"
            ? "bg-amber-100 text-amber-700 ring-1 ring-amber-200 dark:bg-amber-950/50 dark:text-amber-400 dark:ring-amber-800/40"
            : "bg-sky-100 text-sky-700 ring-1 ring-sky-200 dark:bg-sky-950/50 dark:text-sky-400 dark:ring-sky-800/40";
    return (
        <span title={copy.title}
              className={`inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium ${style}`}>
            <ShieldCheck className="h-2.5 w-2.5"/>
            {copy.label}
        </span>
    );
}
