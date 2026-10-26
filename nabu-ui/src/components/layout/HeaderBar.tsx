"use client";

import {usePathname, useRouter} from "next/navigation";
import type {LucideIcon} from "lucide-react";
import {AlertTriangle, BadgeCheck, Bell, CheckCircle, Info, Loader2, Menu, ShieldAlert, TrendingDown, TrendingUp,} from "lucide-react";
import {useAuth} from "@/lib/authContext";
import {type AppNotification, useNotifications} from "@/lib/NotificationContext";
import {useCallback, useEffect, useRef, useState} from "react";
import {createPortal} from "react-dom";
import {useTimeAgo} from "@/hooks/useTimeAgo";
import {SHORT_LABEL_INFO} from "@/components/dashboard/drawer/utils";

type StatusChange =
    | "UNCHANGED"
    | "IMPROVED"
    | "REGRESSED"
    | "RECOVERED"
    | "REGRESSED_TO_FAILING"
    | "FIRST_SCAN";

const SEVERITY_CLASSES: Record<string, string> = {
    CRITICAL: "bg-red-500/10 text-red-400 ring-red-500/20",
    HIGH: "bg-orange-500/10 text-orange-400 ring-orange-500/20",
    MEDIUM: "bg-amber-500/10 text-amber-400 ring-amber-500/20",
};
const SEVERITY_CLASS_DEFAULT = "bg-emerald-500/10 text-emerald-400 ring-emerald-500/20";

function StatusChangeIcon({change}: { change: StatusChange | null | undefined }) {
    if (change === "REGRESSED_TO_FAILING" || change === "REGRESSED") {
        return <TrendingDown className="h-2.5 w-2.5 ml-0.5"/>;
    }
    if (change === "RECOVERED" || change === "IMPROVED") {
        return <TrendingUp className="h-2.5 w-2.5 ml-0.5"/>;
    }
    return null;
}

// ── Portal tooltip for monitor badges ────────────────────────────────────────
function BadgeTooltip({abbr, severity, statusChange, info}: {
    abbr: string;
    severity: string;
    statusChange?: StatusChange | null;
    info: { name: string; desc: string };
}) {
    const ref = useRef<HTMLSpanElement>(null);
    const [pos, setPos] = useState<{ x: number; y: number } | null>(null);

    const show = useCallback(() => {
        const el = ref.current;
        if (!el) return;
        const r = el.getBoundingClientRect();
        setPos({x: r.left + r.width / 2, y: r.top});
    }, []);
    const hide = useCallback(() => setPos(null), []);

    return (
        <span
            ref={ref}
            onMouseEnter={show}
            onMouseLeave={hide}
            className={`inline-flex items-center rounded-full px-1.5 py-0.5 text-[9px] font-semibold ring-1 cursor-default ${
                SEVERITY_CLASSES[severity] ?? SEVERITY_CLASS_DEFAULT
            }`}
        >
            {abbr}
            <StatusChangeIcon change={statusChange}/>
            {pos && createPortal(
                <span
                    style={{left: pos.x, top: pos.y}}
                    className="fixed z-[9999] -translate-x-1/2 -translate-y-full pointer-events-none animate-in fade-in duration-150"
                >
                    <span
                        className="block mb-1.5 whitespace-nowrap rounded-lg bg-slate-900 px-2.5 py-1.5 text-[11px] font-normal leading-snug text-slate-200 shadow-lg ring-1 ring-slate-700/50">
                        <span className="font-semibold">{info.name}</span>
                        <br/>
                        <span className="text-slate-400">{info.desc}</span>
                    </span>
                    <span
                        className="absolute left-1/2 bottom-0.5 -translate-x-1/2 border-4 border-transparent border-t-slate-900"/>
                </span>,
                document.body
            )}
        </span>
    );
}

export function notificationRouteFor(n: AppNotification): string | null {
    switch (n.type) {
        case "NEW_MESSAGE":
            return "/dashboard/messages";
        case "SCAN_COMPLETED":
        case "SCAN_SUMMARY":
        case "CERTIFICATE_EXPIRY":
        case "COMPLIANCE_ISSUE":
        case "SCAN_FAILED":
            return n.relatedEntityId ? `/dashboard/websites/${n.relatedEntityId}` : null;
        default:
            return null;
    }
}

// ── Notification item ────────────────────────────────────────────────────────
export function NotificationItem({
                              n,
                              onClick,
                              showOrgBadge,
                          }: {
    n: AppNotification;
    onClick: (n: AppNotification) => void;
    showOrgBadge: boolean;
}) {
    const lines = n.message.split('\n');
    const isStructured = lines.length >= 2 && lines[0].startsWith('http');
    const siteUrl = isStructured ? lines[0] : null;
    const subLine = isStructured && lines.length >= 3 ? lines[1] : null;
    const statusLine = isStructured ? lines[lines.length - 1] : null;

    const monitorBadges: { abbr: string; severity: string; statusChange?: StatusChange | null }[] = [];
    if (subLine) {
        for (const part of subLine.split(',')) {
            const [abbr, sev, change] = part.split(':');
            if (abbr && sev) monitorBadges.push({
                abbr: abbr.trim(),
                severity: sev.trim(),
                statusChange: (change?.trim() as StatusChange) || null,
            });
        }
    }
    const hasBadges = monitorBadges.length > 0;
    const hasIssues = hasBadges && monitorBadges.some(b => b.severity !== 'INFO');

    const isManual = n.title.startsWith('[Manual]');
    const isScheduledScan = n.type === 'SCAN_SUMMARY' && !isManual;
    const baseTitle = isManual ? n.title.replace(/^\[Manual\]\s*/, '') : n.title;
    const cleanTitle = isStructured ? baseTitle.replace(/\s*·\s*\S+$/, '') : baseTitle;

    function getNotificationStyle(): { Icon: LucideIcon; iconColor: string; iconBg: string } {
        if (n.type === "SCAN_SUMMARY") {
            return hasIssues
                ? {Icon: AlertTriangle, iconColor: "text-amber-500", iconBg: "bg-amber-500/10"}
                : {Icon: CheckCircle, iconColor: "text-emerald-500", iconBg: "bg-emerald-500/10"};
        }
        switch (n.type) {
            case "SCAN_COMPLETED":
                return {Icon: CheckCircle, iconColor: "text-emerald-500", iconBg: "bg-emerald-500/10"};
            case "CERTIFICATE_EXPIRY":
            case "SCAN_FAILED":
                return {Icon: ShieldAlert, iconColor: "text-red-500", iconBg: "bg-red-500/10"};
            case "COMPLIANCE_ISSUE":
                return {Icon: AlertTriangle, iconColor: "text-amber-500", iconBg: "bg-amber-500/10"};
            default:
                return {Icon: Info, iconColor: "text-blue-500", iconBg: "bg-blue-500/10"};
        }
    }

    const {Icon, iconColor, iconBg} = getNotificationStyle();
    const timeAgo = useTimeAgo(n.createdAt);

    return (
        <button
            onClick={() => onClick(n)}
            className={`w-full text-left px-3 py-2.5 border-b border-border/50 last:border-b-0 transition-colors hover:bg-secondary/50 ${
                !n.read ? "bg-primary/[0.03]" : ""
            }`}
        >
            <div className="flex items-start gap-2.5">
                <span
                    className={`mt-[1.1rem] flex h-1.5 w-1.5 shrink-0 rounded-full ${!n.read ? "bg-primary" : "bg-transparent"}`}/>
                <div className={`mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg ${iconBg}`}>
                    <Icon className={`h-3.5 w-3.5 ${iconColor}`}/>
                </div>
                <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-1 leading-tight">
                        {isManual && (
                            <span
                                className="inline-flex shrink-0 items-center rounded-full bg-violet-500/10 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-violet-400 ring-1 ring-violet-500/20">
                                Manual
                            </span>
                        )}
                        {isScheduledScan && (
                            <span
                                className="inline-flex shrink-0 items-center rounded-full bg-slate-500/10 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-slate-400 ring-1 ring-slate-500/20">
                                Scheduled
                            </span>
                        )}
                        {showOrgBadge && n.organizationName && (
                            <span
                                className="inline-flex shrink-0 items-center rounded-full bg-indigo-500/10 px-1.5 py-0.5 text-[9px] font-medium text-indigo-400 ring-1 ring-indigo-500/20">
                                {n.organizationName}
                            </span>
                        )}
                        <p className={`text-xs font-medium ${!n.read ? "text-foreground" : "text-muted-foreground"}`}>
                            {cleanTitle}
                        </p>
                    </div>
                    {isStructured ? (
                        <div className="mt-1 space-y-1">
                            {siteUrl && (
                                <p className="truncate font-mono text-[11px] text-sky-400/80">{siteUrl}</p>
                            )}
                            {hasBadges && (
                                <div className="flex flex-wrap gap-1">
                                    {monitorBadges.map(({abbr, severity, statusChange}) => {
                                        const info = SHORT_LABEL_INFO[abbr];
                                        return info
                                            ? <BadgeTooltip key={abbr} abbr={abbr} severity={severity}
                                                            statusChange={statusChange} info={info}/>
                                            : (
                                                <span key={abbr}
                                                      className={`inline-flex items-center rounded-full px-1.5 py-0.5 text-[9px] font-semibold ring-1 ${
                                                          SEVERITY_CLASSES[severity] ?? SEVERITY_CLASS_DEFAULT
                                                      }`}>
                                                    {abbr}
                                                    <StatusChangeIcon change={statusChange}/>
                                                </span>
                                            );
                                    })}
                                </div>
                            )}
                            {statusLine && (
                                <p className={`text-[11px] font-medium ${
                                    hasIssues ? "text-amber-400" : "text-emerald-400"
                                }`}>
                                    {statusLine}
                                </p>
                            )}
                        </div>
                    ) : (
                        <p className="mt-0.5 text-[11px] leading-relaxed text-muted-foreground line-clamp-5">
                            {n.message}
                        </p>
                    )}
                    <p className="mt-1 text-[10px] text-muted-foreground/60">
                        {timeAgo}
                    </p>
                </div>
            </div>
        </button>
    );
}

// ── Page title map ───────────────────────────────────────────────────────────
function getPageTitle(pathname: string): string {
    if (pathname === "/dashboard") return "Dashboard";
    if (pathname === "/dashboard/messages") return "Messages";
    if (pathname === "/dashboard/team") return "Team";
    if (pathname === "/dashboard/settings/webhooks") return "Webhooks";
    if (pathname === "/dashboard/settings/notifications") return "Notification Settings";
    if (pathname === "/dashboard/settings/privacy") return "Privacy & Data";
    if (pathname === "/dashboard/settings/trust-profile") return "Trust Profile";
    if (pathname === "/admin/abuse") return "Abuse Reports";
    if (pathname.startsWith("/dashboard/websites/") && pathname.endsWith("/trends")) return "Compliance Trends";
    if (pathname === "/admin/overview") return "Admin Overview";
    if (pathname === "/admin/users") return "Users";
    if (pathname === "/admin/organizations") return "Organizations";
    if (pathname === "/admin/monitor-settings") return "Monitor Settings";
    if (pathname.startsWith("/admin/compliance/global-fallbacks")) return "Global Fallbacks";
    if (pathname.startsWith("/admin/compliance")) return "Regions & Markets";
    return "Dashboard";
}

// ── Header bar component ─────────────────────────────────────────────────────

interface HeaderBarProps {
    onMenuClick: () => void;
}

export function HeaderBar({onMenuClick}: HeaderBarProps) {
    const pathname = usePathname();
    const router = useRouter();
    const {organizations, verificationStatus} = useAuth();
    const {
        notifications, unreadCount,
        isLastPage, loadingMore, loadMore,
        markAsRead, markAllAsRead,
    } = useNotifications();
    const [bellOpen, setBellOpen] = useState(false);
    const bellRef = useRef<HTMLDivElement>(null);

    const pageTitle = getPageTitle(pathname);

    // Close bell on route change
    useEffect(() => {
        setBellOpen(false);
    }, [pathname]);

    // Outside click for bell
    useEffect(() => {
        if (!bellOpen) return;
        const handler = (e: MouseEvent) => {
            if (bellRef.current && !bellRef.current.contains(e.target as Node)) {
                setBellOpen(false);
            }
        };
        document.addEventListener("mousedown", handler);
        return () => document.removeEventListener("mousedown", handler);
    }, [bellOpen]);

    function handleNotificationClick(n: AppNotification) {
        markAsRead(n.id);
        setBellOpen(false);
        const route = notificationRouteFor(n);
        if (route) router.push(route);
    }

    return (
        <header
            className="lg:hidden sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-border bg-background/80 backdrop-blur-md px-4 lg:px-6">
            {/* Mobile hamburger */}
            <button
                onClick={onMenuClick}
                className="flex lg:hidden h-9 w-9 items-center justify-center rounded-xl text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                aria-label="Open menu"
            >
                <Menu className="h-5 w-5"/>
            </button>

            {/* Page title */}
            <h1 className="text-base font-semibold text-foreground tracking-tight">
                {pageTitle}
            </h1>

            <VerifiedBadge status={verificationStatus}/>

            {/* Spacer */}
            <div className="flex-1"/>

            {/* Notification bell */}
            <div className="relative" ref={bellRef}>
                <button
                    onClick={() => setBellOpen(prev => !prev)}
                    className="relative flex h-9 w-9 items-center justify-center rounded-xl text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                    aria-label="Notifications"
                >
                    <Bell className="h-[18px] w-[18px]"/>
                    {unreadCount > 0 && (
                        <span className="absolute right-1.5 top-1.5 flex h-2 w-2">
                            <span
                                className="absolute inline-flex h-full w-full animate-ping rounded-full bg-red-400 opacity-75"/>
                            <span className="relative inline-flex h-2 w-2 rounded-full bg-red-500"/>
                        </span>
                    )}
                </button>

                {bellOpen && (
                    <>
                        <div className="fixed inset-0 z-40" onClick={() => setBellOpen(false)}/>
                        <div className="absolute right-0 top-full mt-2 z-50 w-80 sm:w-96">
                            <div
                                className="rounded-2xl border border-border bg-card shadow-xl overflow-hidden">
                                {/* Header */}
                                <div
                                    className="flex items-center justify-between border-b border-border px-4 py-2.5">
                                    <p className="text-xs font-semibold text-foreground">
                                        Notifications
                                        {unreadCount > 0 && (
                                            <span
                                                className="ml-2 rounded-full bg-red-100 dark:bg-red-950/60 px-1.5 py-0.5 text-[10px] font-bold text-red-600 dark:text-red-400">
                                                {unreadCount}
                                            </span>
                                        )}
                                    </p>
                                    {unreadCount > 0 && (
                                        <button
                                            onClick={markAllAsRead}
                                            className="text-[10px] font-medium text-primary hover:underline"
                                        >
                                            Mark all read
                                        </button>
                                    )}
                                </div>

                                {/* List */}
                                <div className="max-h-80 overflow-y-auto">
                                    {notifications.length === 0 ? (
                                        <div
                                            className="flex flex-col items-center justify-center gap-2 py-10 text-center">
                                            <Bell className="h-6 w-6 text-muted-foreground/30"/>
                                            <p className="text-xs text-muted-foreground">No notifications yet</p>
                                        </div>
                                    ) : (
                                        <>
                                            {notifications.map((n) => (
                                                <NotificationItem
                                                    key={n.id}
                                                    n={n}
                                                    onClick={handleNotificationClick}
                                                    showOrgBadge={organizations.length > 1}
                                                />
                                            ))}
                                            {!isLastPage && (
                                                <button
                                                    onClick={loadMore}
                                                    disabled={loadingMore}
                                                    className="flex w-full items-center justify-center gap-1.5 border-t border-border py-2.5 text-[11px] font-medium text-primary transition-colors hover:bg-secondary disabled:opacity-50"
                                                >
                                                    {loadingMore && <Loader2 className="h-3 w-3 animate-spin"/>}
                                                    {loadingMore ? "Loading..." : "Load more"}
                                                </button>
                                            )}
                                        </>
                                    )}
                                </div>
                            </div>
                        </div>
                    </>
                )}
            </div>
        </header>
    );
}

/**
 * Compact verified-user chip in the header. Links to the trust-profile
 * settings page so the user has a one-click path to the full badge card +
 * publish toggle.
 */
function VerifiedBadge({
    status,
}: {
    status: "UNVERIFIED" | "VERIFIED" | "VERIFIED_PENDING" | null;
}) {
    if (status !== "VERIFIED" && status !== "VERIFIED_PENDING") return null;
    const verified = status === "VERIFIED";
    const style = verified
        ? "bg-emerald-500/10 text-emerald-500 ring-emerald-500/20 hover:bg-emerald-500/15"
        : "bg-amber-500/10 text-amber-500 ring-amber-500/20 hover:bg-amber-500/15";
    const label = verified ? "Verified" : "Verified · grace";
    const title = verified
        ? "All your websites have completed DNS TXT verification."
        : "A new website is still inside its 7-day grace window — verify it to keep the badge.";
    return (
        <a
            href="/dashboard/settings/trust-profile"
            title={title}
            className={`hidden sm:inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 transition-colors ${style}`}
        >
            <BadgeCheck className="h-3 w-3"/>
            {label}
        </a>
    );
}
