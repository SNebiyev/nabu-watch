"use client";

import Link from "next/link";
import {usePathname, useRouter} from "next/navigation";
import type {LucideIcon} from "lucide-react";
import {
    AlertTriangle,
    Bell,
    BookOpen,
    Building2,
    CheckCircle,
    ChevronDown,
    Globe2,
    Info,
    LayoutDashboard,
    LayoutGrid,
    Loader2,
    LogOut,
    Mail,
    Moon,
    RefreshCw,
    Shield,
    ShieldAlert,
    Sun,
    User,
    Users,
    Webhook,
    X,
} from "lucide-react";
import {logout} from "@/lib/authService";
import {useAuth} from "@/lib/authContext";
import {useTheme} from "@/components/ThemeProvider";
import {type AppNotification, useNotifications} from "@/lib/NotificationContext";
import {useMessages} from "@/lib/MessageContext";
import {useCallback, useEffect, useRef, useState} from "react";
import {createPortal} from "react-dom";
import {useTimeAgo} from "@/hooks/useTimeAgo";
import {SHORT_LABEL_INFO} from "@/components/dashboard/drawer/utils";

// ── Portal tooltip for monitor badges (escapes overflow:hidden containers) ────
function BadgeTooltip({abbr, severity, info}: {
    abbr: string;
    severity: string;
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
                severity === "CRITICAL" ? "bg-red-500/10 text-red-400 ring-red-500/20"
                    : severity === "HIGH" ? "bg-orange-500/10 text-orange-400 ring-orange-500/20"
                        : severity === "MEDIUM" ? "bg-amber-500/10 text-amber-400 ring-amber-500/20"
                            : "bg-emerald-500/10 text-emerald-400 ring-emerald-500/20"
            }`}
        >
            {abbr}
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

// ── Per-notification row with live timestamp ───────────────────────────────────
function NotificationItem({
                              n,
                              onClick,
                              showOrgBadge,
                          }: {
    n: AppNotification;
    onClick: (n: AppNotification) => void;
    showOrgBadge: boolean;
}) {
    // Parse structured message: Line 0 = website URL, Line 1 = "ABBR:SEV,ABBR:SEV,...", Line 2 = summary.
    // Produced by backend for all scan-type notifications (SCAN_SUMMARY, SCAN_COMPLETED, etc.)
    const lines = n.message.split('\n');
    const isStructured = lines.length >= 2 && lines[0].startsWith('http');
    const siteUrl = isStructured ? lines[0] : null;
    const subLine = isStructured && lines.length >= 3 ? lines[1] : null;
    const statusLine = isStructured ? lines[lines.length - 1] : null;

    // Parse "ABBR:SEVERITY" badge pairs from line 2
    const monitorBadges: { abbr: string; severity: string }[] = [];
    if (subLine) {
        for (const part of subLine.split(',')) {
            const [abbr, sev] = part.split(':');
            if (abbr && sev) monitorBadges.push({abbr: abbr.trim(), severity: sev.trim()});
        }
    }
    const hasBadges = monitorBadges.length > 0;
    const hasIssues = hasBadges && monitorBadges.some(b => b.severity !== 'INFO');

    // Strip [Manual] prefix from title — shown separately as a badge
    const isManual = n.title.startsWith('[Manual]');
    const isScheduledScan = n.type === 'SCAN_SUMMARY' && !isManual;
    // When the URL is already shown in the structured body, strip " · domain" from the title
    const baseTitle = isManual ? n.title.replace(/^\[Manual\]\s*/, '') : n.title;
    const cleanTitle = isStructured ? baseTitle.replace(/\s*·\s*\S+$/, '') : baseTitle;

    function getNotificationStyle(): { Icon: LucideIcon; iconColor: string; iconBg: string } {
        // For scan-summary and scan-completed, icon color reflects actual outcome
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
            className={`w-full text-left px-3 py-2.5 border-b border-slate-50 dark:border-slate-800/60 last:border-b-0 transition-colors hover:bg-slate-50 dark:hover:bg-slate-800/40 ${
                !n.read ? "bg-blue-50/40 dark:bg-blue-950/20" : ""
            }`}
        >
            <div className="flex items-start gap-2.5">
                <span
                    className={`mt-[1.1rem] flex h-1.5 w-1.5 shrink-0 rounded-full ${!n.read ? "bg-blue-500" : "bg-transparent"}`}/>
                <div className={`mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg ${iconBg}`}>
                    <Icon className={`h-3.5 w-3.5 ${iconColor}`}/>
                </div>
                <div className="min-w-0 flex-1">
                    {/* Title row with trigger badge */}
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
                        <p className={`text-xs font-medium ${!n.read ? "text-slate-900 dark:text-white" : "text-slate-500 dark:text-slate-400"}`}>
                            {cleanTitle}
                        </p>
                    </div>
                    {/* Structured rows or plain message fallback */}
                    {isStructured ? (
                        <div className="mt-1 space-y-1">
                            {siteUrl && (
                                <p className="truncate font-mono text-[11px] text-sky-400/80">{siteUrl}</p>
                            )}
                            {hasBadges && (
                                <div className="flex flex-wrap gap-1">
                                    {monitorBadges.map(({abbr, severity}) => {
                                        const info = SHORT_LABEL_INFO[abbr];
                                        return info
                                            ? <BadgeTooltip key={abbr} abbr={abbr} severity={severity} info={info}/>
                                            : (
                                                <span key={abbr}
                                                      className={`inline-flex items-center rounded-full px-1.5 py-0.5 text-[9px] font-semibold ring-1 ${
                                                          severity === "CRITICAL" ? "bg-red-500/10 text-red-400 ring-red-500/20"
                                                              : severity === "HIGH" ? "bg-orange-500/10 text-orange-400 ring-orange-500/20"
                                                                  : severity === "MEDIUM" ? "bg-amber-500/10 text-amber-400 ring-amber-500/20"
                                                                      : "bg-emerald-500/10 text-emerald-400 ring-emerald-500/20"
                                                      }`}>
                                                    {abbr}
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
                        <p className="mt-0.5 text-[11px] leading-relaxed text-slate-400 dark:text-slate-500 line-clamp-5">
                            {n.message}
                        </p>
                    )}
                    <p className="mt-1 text-[10px] text-slate-300 dark:text-slate-600">
                        {timeAgo}
                    </p>
                </div>
            </div>
        </button>
    );
}

export function TopNav() {
    const {
        email,
        isAdmin,
        organizations,
        currentOrganizationId,
        currentOrganizationName,
        currentOrganizationRole,
        switchOrg
    } = useAuth();
    const { theme, toggleTheme } = useTheme();
    const {
        notifications, unreadCount,
        isLastPage, loadingMore, loadMore,
        markAsRead, markAllAsRead,
    } = useNotifications();
    const {unreadSendersCount} = useMessages();
    const router = useRouter();
    const [profileOpen, setProfileOpen] = useState(false);
    const [bellOpen, setBellOpen] = useState(false);
    const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
    const [adminOpen, setAdminOpen] = useState(false);
    const pathname = usePathname();
    const mobileMenuRef = useRef<HTMLDivElement>(null);
    const bellRef = useRef<HTMLDivElement>(null);
    const adminMenuRef = useRef<HTMLDivElement>(null);

    const onDashboard = pathname === "/dashboard";
    const onMessages = pathname === "/dashboard/messages";
    const onWebhooks = pathname === "/dashboard/settings/webhooks";
    const onAdminOverview = pathname === "/admin/overview";
    const onAdminUsers = pathname === "/admin/users";
    const onAdminFallbacks = pathname.startsWith("/admin/compliance/global-fallbacks");
    const onAdminCompliance = pathname.startsWith("/admin/compliance") && !onAdminFallbacks;
    const onAdminOrgs = pathname === "/admin/organizations";
    const onAdminSync = pathname === "/admin/monitor-settings";
    const onAnyAdmin = pathname.startsWith("/admin");

    const isMemberView = currentOrganizationRole === "MEMBER" && !isAdmin;

    // Close menus on route change
    useEffect(() => {
        setMobileMenuOpen(false);
        setProfileOpen(false);
        setBellOpen(false);
        setAdminOpen(false);
    }, [pathname]);

    // Close admin dropdown on outside click
    useEffect(() => {
        if (!adminOpen) return;
        const handler = (e: MouseEvent) => {
            if (adminMenuRef.current && !adminMenuRef.current.contains(e.target as Node)) {
                setAdminOpen(false);
            }
        };
        document.addEventListener("mousedown", handler);
        return () => document.removeEventListener("mousedown", handler);
    }, [adminOpen]);

    // Close bell dropdown on outside click
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

    // ⌘K / Ctrl+K toggles mobile menu; Escape closes everything
    useEffect(() => {
        const handleKeyDown = (e: KeyboardEvent) => {
            if ((e.metaKey || e.ctrlKey) && e.key === "k") {
                e.preventDefault();
                setMobileMenuOpen(prev => !prev);
                setProfileOpen(false);
            }
            if (e.key === "Escape") {
                setMobileMenuOpen(false);
                setProfileOpen(false);
            }
        };
        window.addEventListener("keydown", handleKeyDown);
        return () => window.removeEventListener("keydown", handleKeyDown);
    }, []);

    // Close mobile menu on outside click
    useEffect(() => {
        if (!mobileMenuOpen) return;
        const handler = (e: MouseEvent) => {
            if (mobileMenuRef.current && !mobileMenuRef.current.contains(e.target as Node)) {
                setMobileMenuOpen(false);
            }
        };
        document.addEventListener("mousedown", handler);
        return () => document.removeEventListener("mousedown", handler);
    }, [mobileMenuOpen]);


    function handleNotificationClick(n: AppNotification) {
        markAsRead(n.id);
        setBellOpen(false);
        switch (n.type) {
            case "NEW_MESSAGE":
                router.push("/dashboard/messages");
                break;
            case "SCAN_COMPLETED":
            case "SCAN_SUMMARY":
            case "CERTIFICATE_EXPIRY":
            case "COMPLIANCE_ISSUE":
            case "SCAN_FAILED":
                if (n.relatedEntityId) {
                    router.push(`/dashboard/websites/${n.relatedEntityId}`);
                }
                break;
            default:
                break;
        }
    }

    return (
        <>
            {/* ── Floating pill ───────────────────────────────────────────────── */}
            <div className={`fixed top-3 left-1/2 -translate-x-1/2 z-50 flex items-center gap-1 px-2 py-1.5
                rounded-2xl backdrop-blur-xl transition-all duration-300 ${
                isMemberView
                    ? "bg-purple-50/90 dark:bg-purple-950/40 border border-purple-300/70 dark:border-purple-700/50 shadow-lg shadow-purple-200/40 dark:shadow-purple-950/40"
                    : "bg-white/90 dark:bg-slate-900/90 border border-slate-200/80 dark:border-slate-700/60 shadow-lg shadow-slate-200/60 dark:shadow-slate-950/60"
            }`}>

                {/* Logo */}
                <Link
                    href="/dashboard"
                    className="flex items-center gap-1.5 pl-1 pr-2 py-0.5 rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800/50 transition-colors group"
                >
                    <div className="flex h-6 w-6 items-center justify-center rounded-lg bg-blue-600 shadow-md shadow-blue-300/40 dark:shadow-blue-900/40 shrink-0 group-hover:scale-105 transition-transform">
                        <Shield className="h-3.5 w-3.5 text-white" />
                    </div>
                    <span className="hidden text-sm font-black tracking-tight text-slate-900 dark:text-white sm:block">
                        nabu<span className="text-blue-600">.watch</span>
                    </span>
                </Link>

                <div
                    className={`h-5 w-px mx-0.5 ${isMemberView ? "bg-purple-300 dark:bg-purple-700" : "bg-slate-200 dark:bg-slate-700"}`}/>

                {/* Member ribbon — visible workspace context when viewing another org */}
                {isMemberView && currentOrganizationName && (
                    <div
                        className="hidden sm:flex items-center gap-1.5 px-2.5 py-1 rounded-xl bg-purple-100/80 dark:bg-purple-900/30 border border-purple-200/60 dark:border-purple-800/40">
                        <Globe2 className="h-3 w-3 text-purple-500 dark:text-purple-400 shrink-0"/>
                        <span
                            className="max-w-[8rem] truncate text-xs font-semibold text-purple-700 dark:text-purple-300">
                            {currentOrganizationName}
                        </span>
                        <span
                            className="shrink-0 rounded-full bg-purple-200 dark:bg-purple-800/60 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-purple-600 dark:text-purple-300">
                            Member
                        </span>
                    </div>
                )}

                {isMemberView && <div className="hidden sm:block h-5 w-px bg-purple-300 dark:bg-purple-700 mx-0.5"/>}

                {/* Desktop nav links — always in DOM; unwrapped from !loading to prevent layout shift */}
                <nav className="hidden items-center gap-0.5 sm:flex">
                    <Link
                        href="/dashboard"
                        className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-sm font-medium transition-all duration-200 ${
                            onDashboard
                                ? "bg-blue-600 text-white shadow-sm shadow-blue-300/40 dark:shadow-blue-900/40"
                                : "text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
                        }`}
                    >
                        <LayoutDashboard className="h-3.5 w-3.5"/>
                        <span className="hidden lg:block">Dashboard</span>
                    </Link>

                    <Link
                        href="/dashboard/messages"
                        className={`relative flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-sm font-medium transition-all duration-200 ${
                            onMessages
                                ? "bg-blue-600 text-white shadow-sm shadow-blue-300/40 dark:shadow-blue-900/40"
                                : "text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
                        }`}
                        aria-label="Messages"
                    >
                        <Mail className="h-3.5 w-3.5"/>
                        <span className="hidden lg:block">Messages</span>
                        {unreadSendersCount > 0 && (
                            <span
                                className="absolute -right-1 -top-1 flex h-4 min-w-[1rem] items-center justify-center rounded-full bg-red-500 px-1 text-[9px] font-black text-white shadow-sm">
                                {unreadSendersCount > 9 ? "9+" : unreadSendersCount}
                            </span>
                        )}
                    </Link>

                    <Link
                        href="/dashboard/settings/webhooks"
                        className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-sm font-medium transition-all duration-200 ${
                            onWebhooks
                                ? "bg-blue-600 text-white shadow-sm shadow-blue-300/40 dark:shadow-blue-900/40"
                                : "text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
                        }`}
                    >
                        <Webhook className="h-3.5 w-3.5"/>
                        <span className="hidden lg:block">Webhooks</span>
                    </Link>

                    {/* Admin Panel dropdown */}
                    {isAdmin && (
                        <>
                            <div className="h-4 w-px bg-slate-200 dark:bg-slate-700 mx-1"/>
                            <div ref={adminMenuRef} className="relative">
                                <button
                                    onClick={() => setAdminOpen(p => !p)}
                                    className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-sm font-medium transition-all duration-200 ${
                                        onAnyAdmin
                                            ? "bg-amber-500 text-white shadow-sm shadow-amber-300/30 dark:shadow-amber-900/30"
                                            : "text-amber-600/80 dark:text-amber-400/80 hover:bg-amber-50 dark:hover:bg-amber-900/20"
                                    }`}
                                >
                                    <ShieldAlert className="h-3.5 w-3.5"/>
                                    <span className="hidden sm:block">Administration</span>
                                    <ChevronDown
                                        className={`h-3 w-3 transition-transform duration-150 ${adminOpen ? "rotate-180" : ""}`}/>
                                </button>

                                {adminOpen && (
                                    <div
                                        className="absolute left-0 top-full mt-1.5 w-52 rounded-2xl border border-border bg-card shadow-xl ring-1 ring-black/5 dark:ring-white/5 z-50 overflow-hidden">
                                        {/* Platform section */}
                                        <p className="px-3 pt-2.5 pb-1 text-[10px] font-black uppercase tracking-widest text-amber-500/70">Platform</p>
                                        {[
                                            {
                                                href: "/admin/overview",
                                                icon: ShieldAlert,
                                                label: "Overview",
                                                active: onAdminOverview
                                            },
                                            {href: "/admin/users", icon: Users, label: "Users", active: onAdminUsers},
                                            {
                                                href: "/admin/organizations",
                                                icon: Building2,
                                                label: "Organizations",
                                                active: onAdminOrgs
                                            },
                                        ].map(({href, icon: Icon, label, active}) => (
                                            <Link key={href} href={href}
                                                  className={`flex items-center gap-2.5 px-3 py-2 text-sm font-medium transition-colors ${active ? "bg-amber-500/10 text-amber-600 dark:text-amber-400" : "text-foreground/80 hover:bg-secondary/60"}`}>
                                                <Icon className="h-3.5 w-3.5 shrink-0"/>
                                                {label}
                                            </Link>
                                        ))}

                                        {/* Compliance section */}
                                        <p className="px-3 pt-3 pb-1 text-[10px] font-black uppercase tracking-widest text-amber-500/70 border-t border-border mt-1">Compliance</p>
                                        {[
                                            {
                                                href: "/admin/compliance",
                                                icon: Globe2,
                                                label: "Regions & Markets",
                                                active: onAdminCompliance
                                            },
                                            {
                                                href: "/admin/compliance/global-fallbacks",
                                                icon: BookOpen,
                                                label: "Global Fallbacks",
                                                active: onAdminFallbacks
                                            },
                                        ].map(({href, icon: Icon, label, active}) => (
                                            <Link key={href} href={href}
                                                  className={`flex items-center gap-2.5 px-3 py-2 text-sm font-medium transition-colors ${active ? "bg-amber-500/10 text-amber-600 dark:text-amber-400" : "text-foreground/80 hover:bg-secondary/60"}`}>
                                                <Icon className="h-3.5 w-3.5 shrink-0"/>
                                                {label}
                                            </Link>
                                        ))}

                                        {/* System section */}
                                        <p className="px-3 pt-3 pb-1 text-[10px] font-black uppercase tracking-widest text-amber-500/70 border-t border-border mt-1">System</p>
                                        <Link href="/admin/monitor-settings"
                                              className={`flex items-center gap-2.5 px-3 py-2 text-sm font-medium transition-colors ${onAdminSync ? "bg-amber-500/10 text-amber-600 dark:text-amber-400" : "text-foreground/80 hover:bg-secondary/60"}`}>
                                            <RefreshCw className="h-3.5 w-3.5 shrink-0"/>
                                            Monitor Settings
                                        </Link>
                                    </div>
                                )}
                            </div>
                        </>
                    )}
                </nav>

                {/* Mobile menu button — always in DOM to prevent layout shift */}
                <button
                    onClick={() => {
                        setMobileMenuOpen(prev => !prev);
                        setProfileOpen(false);
                    }}
                    className={`flex sm:hidden items-center gap-1.5 px-2.5 py-1.5 rounded-xl text-sm font-medium transition-all duration-200 ${
                        mobileMenuOpen
                            ? "bg-blue-600 text-white shadow-md shadow-blue-300/40 dark:shadow-blue-900/40"
                            : "text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
                    }`}
                    aria-label="Open navigation"
                >
                    {mobileMenuOpen ? <X className="h-4 w-4"/> : <LayoutGrid className="h-3.5 w-3.5"/>}
                </button>

                <div
                    className={`h-5 w-px mx-0.5 ${isMemberView ? "bg-purple-300 dark:bg-purple-700" : "bg-slate-200 dark:bg-slate-700"}`}/>

                {/* Theme toggle */}
                <button
                    onClick={toggleTheme}
                    className="p-2 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-colors"
                    aria-label="Toggle theme"
                >
                    {theme === "dark" ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
                </button>

                {/* Bell notification button */}
                <div className="relative" ref={bellRef}>
                    <button
                        onClick={() => {
                            setBellOpen(prev => !prev);
                            setProfileOpen(false);
                            setMobileMenuOpen(false);
                        }}
                        className="relative p-2 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-colors"
                        aria-label="Notifications"
                    >
                        <Bell className="h-4 w-4"/>
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
                            {/* ── Positioning wrapper (no styling) ─────────────── */}
                            <div className="
                                fixed top-[3.5rem] left-1/2 -translate-x-1/2 w-[calc(100vw-2rem)] max-w-sm
                                sm:absolute sm:left-1/2 sm:-translate-x-1/2 sm:right-auto sm:top-full sm:mt-3 sm:w-80
                                z-50">

                                {/* Arrow caret — desktop only, centered under bell icon */}
                                <div className="hidden sm:block absolute -top-[7px] left-1/2 -translate-x-1/2 z-10
                                    h-3.5 w-3.5 rotate-45 rounded-sm
                                    border-l border-t border-slate-200 dark:border-slate-700
                                    bg-white dark:bg-slate-900 shadow-sm"/>

                                {/* Card — owns the visual border, bg, and overflow clip */}
                                <div className="rounded-2xl border border-slate-200 dark:border-slate-700
                                    bg-white dark:bg-slate-900
                                    shadow-xl shadow-slate-200/60 dark:shadow-slate-950/60
                                    overflow-hidden">

                                    {/* Header */}
                                    <div
                                        className="flex items-center justify-between border-b border-slate-100 dark:border-slate-800 px-4 py-2.5">
                                        <p className="text-xs font-semibold text-slate-900 dark:text-white">
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
                                                className="text-[10px] font-medium text-blue-600 dark:text-blue-400 hover:underline"
                                            >
                                                Mark all read
                                            </button>
                                        )}
                                    </div>

                                    {/* Notification list */}
                                    <div className="max-h-72 overflow-y-auto">
                                        {notifications.length === 0 ? (
                                            <div
                                                className="flex flex-col items-center justify-center gap-2 py-8 text-center">
                                                <Bell className="h-6 w-6 text-slate-300 dark:text-slate-600"/>
                                                <p className="text-xs text-slate-400 dark:text-slate-500">
                                                    No notifications yet
                                                </p>
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

                                                {/* Load more */}
                                                {!isLastPage && (
                                                    <button
                                                        onClick={loadMore}
                                                        disabled={loadingMore}
                                                        className="flex w-full items-center justify-center gap-1.5 border-t border-slate-100 dark:border-slate-800 py-2.5 text-[11px] font-medium text-blue-600 dark:text-blue-400 transition-colors hover:bg-slate-50 dark:hover:bg-slate-800/40 disabled:opacity-50"
                                                    >
                                                        {loadingMore && (
                                                            <Loader2 className="h-3 w-3 animate-spin"/>
                                                        )}
                                                        {loadingMore ? "Loading…" : "Load more"}
                                                    </button>
                                                )}
                                            </>
                                        )}
                                    </div>

                                </div>
                                {/* /card */}
                            </div>
                            {/* /positioning wrapper */}
                        </>
                    )}
                </div>

                {/* Profile button — desktop */}
                <div className="relative hidden sm:block">
                    <button
                        onClick={() => { setProfileOpen(prev => !prev); setMobileMenuOpen(false); }}
                        className="flex items-center gap-1.5 px-2 py-1 rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                    >
                        <div
                            className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white ${isAdmin ? "bg-amber-500" : isMemberView ? "bg-purple-500" : "bg-blue-600"}`}>
                            <User className="h-3.5 w-3.5" />
                        </div>
                        <span className="hidden max-w-[7rem] truncate text-xs font-semibold text-slate-700 dark:text-slate-200 md:block">
                            {email?.split("@")[0] ?? "Account"}
                        </span>
                        {isAdmin && (
                            <span className="hidden shrink-0 rounded-full bg-amber-100 px-1.5 py-0.5 text-[9px] font-black uppercase tracking-wide text-amber-700 dark:bg-amber-950 dark:text-amber-400 md:block">
                                Admin
                            </span>
                        )}
                    </button>

                    {profileOpen && (
                        <>
                            <div className="fixed inset-0 z-40" onClick={() => setProfileOpen(false)} />
                            <div className="absolute right-0 top-full z-50 mt-2 min-w-[200px] rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 py-1 shadow-xl shadow-slate-200/60 dark:shadow-slate-950/60">
                                {email && (
                                    <div className="border-b border-slate-100 dark:border-slate-800 px-4 py-2.5">
                                        <p className="text-xs text-slate-400 dark:text-slate-500">Signed in as</p>
                                        <p className="mt-0.5 truncate text-sm font-medium text-slate-900 dark:text-white">{email}</p>
                                        {isAdmin ? (
                                            <span className="mt-1 inline-block rounded-full bg-amber-100 px-2 py-0.5 text-[9px] font-black uppercase tracking-wide text-amber-700 dark:bg-amber-950 dark:text-amber-400">
                                                Administrator
                                            </span>
                                        ) : null}
                                        {currentOrganizationRole === "MEMBER" && currentOrganizationName && (
                                            <p className="mt-1.5 flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                                                <Globe2 className="h-3 w-3 shrink-0"/>
                                                <span
                                                    className="truncate max-w-[10rem]">{currentOrganizationName}</span>
                                                <span
                                                    className="shrink-0 rounded-full bg-purple-100 dark:bg-purple-900/30 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-purple-600 dark:text-purple-400">
                                                    Member
                                                </span>
                                            </p>
                                        )}
                                    </div>
                                )}

                                {/* ── Organization switcher ── */}
                                {organizations.length > 0 && (
                                    <div className="border-b border-slate-100 dark:border-slate-800 px-4 py-2.5">
                                        <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-1.5">Workspace</p>
                                        {organizations.map((org) => (
                                            <button
                                                key={org.id}
                                                onClick={async () => {
                                                    if (org.id !== currentOrganizationId) {
                                                        await switchOrg(org.id);
                                                        setProfileOpen(false);
                                                    }
                                                }}
                                                className={`flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm transition-colors ${
                                                    org.id === currentOrganizationId
                                                        ? "bg-blue-50 dark:bg-blue-900/20 text-blue-700 dark:text-blue-300 font-medium"
                                                        : "text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800/50"
                                                }`}
                                            >
                                                <Globe2 className="h-3.5 w-3.5 shrink-0"/>
                                                <span className="truncate">{org.name}</span>
                                                {org.role === "MEMBER" && (
                                                    <span
                                                        className="shrink-0 rounded-full bg-slate-100 dark:bg-slate-800 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
                                                        Member
                                                    </span>
                                                )}
                                                {org.id === currentOrganizationId && (
                                                    <CheckCircle
                                                        className="ml-auto h-3.5 w-3.5 shrink-0 text-blue-500"/>
                                                )}
                                            </button>
                                        ))}
                                        <Link
                                            href="/dashboard/team"
                                            onClick={() => setProfileOpen(false)}
                                            className="mt-1.5 flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs text-slate-500 dark:text-slate-400 transition-colors hover:bg-slate-50 dark:hover:bg-slate-800/50"
                                        >
                                            <Users className="h-3.5 w-3.5"/>
                                            Manage team
                                        </Link>
                                        <Link
                                            href="/dashboard/settings/notifications"
                                            onClick={() => setProfileOpen(false)}
                                            className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs text-slate-500 dark:text-slate-400 transition-colors hover:bg-slate-50 dark:hover:bg-slate-800/50"
                                        >
                                            <Bell className="h-3.5 w-3.5"/>
                                            Notification settings
                                        </Link>
                                        <Link
                                            href="/dashboard/settings/privacy"
                                            onClick={() => setProfileOpen(false)}
                                            className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs text-slate-500 dark:text-slate-400 transition-colors hover:bg-slate-50 dark:hover:bg-slate-800/50"
                                        >
                                            <Shield className="h-3.5 w-3.5"/>
                                            Privacy &amp; data
                                        </Link>
                                    </div>
                                )}

                                <button
                                    onClick={logout}
                                    className="flex w-full items-center gap-2.5 px-4 py-2.5 text-sm text-red-600 dark:text-red-400 transition-colors hover:bg-red-50 dark:hover:bg-red-900/20"
                                >
                                    <LogOut className="h-4 w-4" />
                                    Sign out
                                </button>
                            </div>
                        </>
                    )}
                </div>

                {/* Logout — mobile only */}
                <button
                    onClick={logout}
                    className="sm:hidden p-2 text-slate-500 hover:text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-xl transition-colors mr-0.5"
                    title="Sign out"
                >
                    <LogOut className="h-4 w-4" />
                </button>
            </div>

            {/* ── Mobile dropdown menu ────────────────────────────────────────── */}
            {mobileMenuOpen && (
                <div
                    ref={mobileMenuRef}
                    className="fixed top-12 left-0 right-0 z-40 w-full px-2 mt-2 sm:hidden
                        bg-white dark:bg-slate-900
                        border border-slate-200 dark:border-slate-700
                        rounded-2xl shadow-xl shadow-slate-200/60 dark:shadow-slate-950/60
                        overflow-hidden animate-dropdown-in"
                >
                    {/* Member ribbon — mobile */}
                    {isMemberView && currentOrganizationName && (
                        <div
                            className="flex items-center gap-2 border-b border-purple-200 dark:border-purple-800/40 bg-purple-50 dark:bg-purple-950/30 px-4 py-2">
                            <Globe2 className="h-3.5 w-3.5 text-purple-500 dark:text-purple-400 shrink-0"/>
                            <span
                                className="truncate max-w-[12rem] text-xs font-semibold text-purple-700 dark:text-purple-300">{currentOrganizationName}</span>
                            <span
                                className="shrink-0 rounded-full bg-purple-200 dark:bg-purple-800/60 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-purple-600 dark:text-purple-300">
                                Member
                            </span>
                        </div>
                    )}

                    {/* User info */}
                    {email && (
                        <div className="flex items-center gap-3 border-b border-slate-100 dark:border-slate-800 px-4 py-3">
                            <div
                                className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-sm font-bold text-white ${isAdmin ? "bg-amber-500" : isMemberView ? "bg-purple-500" : "bg-blue-600"}`}>
                                <User className="h-4 w-4" />
                            </div>
                            <div className="min-w-0">
                                <p className="truncate text-sm font-medium text-slate-900 dark:text-white">{email}</p>
                                {isAdmin ? (
                                    <p className="text-[10px] font-bold uppercase tracking-wide text-amber-500">Administrator</p>
                                ) : null}
                            </div>
                        </div>
                    )}

                    {/* Nav links */}
                    <nav className="space-y-0.5 px-2 py-2">
                        <Link
                            href="/dashboard"
                            onClick={() => setMobileMenuOpen(false)}
                            className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all ${
                                onDashboard
                                    ? "bg-blue-600 text-white"
                                    : "text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
                            }`}
                        >
                            <LayoutDashboard className="h-4 w-4" />
                            Dashboard
                        </Link>

                        <Link
                            href="/dashboard/messages"
                            onClick={() => setMobileMenuOpen(false)}
                            className={`relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all ${
                                onMessages
                                    ? "bg-blue-600 text-white"
                                    : "text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
                            }`}
                        >
                            <Mail className="h-4 w-4"/>
                            Messages
                            {unreadSendersCount > 0 && (
                                <span
                                    className="ml-auto flex h-5 min-w-[1.25rem] items-center justify-center rounded-full bg-red-500 px-1.5 text-[10px] font-black text-white">
                                    {unreadSendersCount > 9 ? "9+" : unreadSendersCount}
                                </span>
                            )}
                        </Link>

                        <Link
                            href="/dashboard/settings/webhooks"
                            onClick={() => setMobileMenuOpen(false)}
                            className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all ${
                                onWebhooks
                                    ? "bg-blue-600 text-white"
                                    : "text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
                            }`}
                        >
                            <Webhook className="h-4 w-4"/>
                            Webhooks
                        </Link>

                        {isAdmin && (
                            <>
                                {/* Platform section */}
                                <p className="px-3 pb-1 pt-3 text-[10px] font-black uppercase tracking-widest text-amber-500/70">Platform</p>
                                {[
                                    {
                                        href: "/admin/overview",
                                        icon: ShieldAlert,
                                        label: "Overview",
                                        active: onAdminOverview
                                    },
                                    {href: "/admin/users", icon: Users, label: "Users", active: onAdminUsers},
                                    {
                                        href: "/admin/organizations",
                                        icon: Building2,
                                        label: "Organizations",
                                        active: onAdminOrgs
                                    },
                                ].map(({href, icon: Icon, label, active}) => (
                                    <Link
                                        key={href}
                                        href={href}
                                        onClick={() => setMobileMenuOpen(false)}
                                        className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all ${
                                            active
                                                ? "bg-amber-500 text-white"
                                                : "text-amber-600 dark:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-900/20"
                                        }`}
                                    >
                                        <Icon className="h-4 w-4"/>
                                        {label}
                                    </Link>
                                ))}

                                {/* Compliance section */}
                                <p className="px-3 pb-1 pt-3 text-[10px] font-black uppercase tracking-widest text-amber-500/70">Compliance</p>
                                {[
                                    {
                                        href: "/admin/compliance",
                                        icon: Globe2,
                                        label: "Regions & Markets",
                                        active: onAdminCompliance
                                    },
                                    {
                                        href: "/admin/compliance/global-fallbacks",
                                        icon: BookOpen,
                                        label: "Global Fallbacks",
                                        active: onAdminFallbacks
                                    },
                                ].map(({href, icon: Icon, label, active}) => (
                                    <Link
                                        key={href}
                                        href={href}
                                        onClick={() => setMobileMenuOpen(false)}
                                        className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all ${
                                            active
                                                ? "bg-amber-500 text-white"
                                                : "text-amber-600 dark:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-900/20"
                                        }`}
                                    >
                                        <Icon className="h-4 w-4"/>
                                        {label}
                                    </Link>
                                ))}

                                {/* System section */}
                                <p className="px-3 pb-1 pt-3 text-[10px] font-black uppercase tracking-widest text-amber-500/70">System</p>
                                <Link
                                    href="/admin/monitor-settings"
                                    onClick={() => setMobileMenuOpen(false)}
                                    className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all ${
                                        onAdminSync
                                            ? "bg-amber-500 text-white"
                                            : "text-amber-600 dark:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-900/20"
                                    }`}
                                >
                                    <RefreshCw className="h-4 w-4"/>
                                    Monitor Settings
                                </Link>
                            </>
                        )}
                    </nav>

                    {/* Footer */}
                    <div className="border-t border-slate-100 dark:border-slate-800 px-2 py-2 space-y-0.5">
                        {unreadCount > 0 && (
                            <button
                                onClick={() => {
                                    setMobileMenuOpen(false);
                                    markAllAsRead();
                                }}
                                className="flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-blue-600 dark:text-blue-400 transition-colors hover:bg-blue-50 dark:hover:bg-blue-900/20"
                            >
                                <Bell className="h-4 w-4"/>
                                Mark {unreadCount} notification{unreadCount !== 1 ? "s" : ""} read
                            </button>
                        )}
                        <button
                            onClick={toggleTheme}
                            className="flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-slate-600 dark:text-slate-400 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
                        >
                            {theme === "dark" ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
                            {theme === "dark" ? "Light mode" : "Dark mode"}
                        </button>
                        <button
                            onClick={() => { setMobileMenuOpen(false); logout(); }}
                            className="flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-red-600 dark:text-red-400 transition-colors hover:bg-red-50 dark:hover:bg-red-900/20"
                        >
                            <LogOut className="h-4 w-4" />
                            Sign out
                        </button>
                    </div>
                </div>
            )}
        </>
    );
}
