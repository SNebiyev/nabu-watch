"use client";

import Link from "next/link";
import {usePathname} from "next/navigation";
import type {LucideIcon} from "lucide-react";
import {
    AlertTriangle,
    BadgeCheck,
    Bell,
    BookOpen,
    Building2,
    CheckCircle,
    ChevronDown,
    Flag,
    Globe2,
    LayoutDashboard,
    LogOut,
    Mail,
    Moon,
    PanelLeft,
    PanelLeftClose,
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
import {useMessages} from "@/lib/MessageContext";
import {useNotifications} from "@/lib/NotificationContext";
import {useEffect, useRef, useState} from "react";
import {apiClient} from "@/lib/apiClient";
import {SidebarNotificationPanel} from "./SidebarNotificationPanel";

// ── Nav item component ──────────────────────────────────────────────────────

interface NavItemProps {
    href: string;
    icon: LucideIcon;
    label: string;
    active: boolean;
    collapsed: boolean;
    badge?: number;
    onClick?: () => void;
}

function NavItem({href, icon: Icon, label, active, collapsed, badge, onClick}: NavItemProps) {
    return (
        <Link
            href={href}
            onClick={onClick}
            className={`group relative flex items-center gap-3 rounded-xl px-3 py-2 text-[13px] font-medium transition-all duration-200 ${
                active
                    ? "bg-primary text-white shadow-sm shadow-primary/25"
                    : "text-sidebar-foreground hover:bg-sidebar-accent hover:text-foreground"
            } ${collapsed ? "justify-center px-2" : ""}`}
            title={collapsed ? label : undefined}
        >
            <Icon
                className={`h-[18px] w-[18px] shrink-0 ${active ? "" : "text-sidebar-muted group-hover:text-foreground"} transition-colors`}/>
            {!collapsed && (
                <span className="truncate">{label}</span>
            )}
            {badge != null && badge > 0 && (
                <span
                    className={`${collapsed ? "absolute -right-1 -top-1" : "ml-auto"} flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-bold text-white`}>
                    {badge > 9 ? "9+" : badge}
                </span>
            )}
        </Link>
    );
}

// ── Section label ────────────────────────────────────────────────────────────

function SectionLabel({children, collapsed}: { children: React.ReactNode; collapsed: boolean }) {
    if (collapsed) return <div className="my-2 mx-3 h-px bg-sidebar-border"/>;
    return (
        <p className="mb-1 mt-4 px-3 text-[10px] font-bold uppercase tracking-widest text-sidebar-muted">
            {children}
        </p>
    );
}

// ── Organization switcher ────────────────────────────────────────────────────

function OrgSwitcher({collapsed}: { collapsed: boolean }) {
    const {
        organizations,
        currentOrganizationId,
        currentOrganizationName,
        currentOrganizationRole,
        switchOrg
    } = useAuth();
    const [open, setOpen] = useState(false);
    const ref = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!open) return;
        const handler = (e: MouseEvent) => {
            if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
        };
        document.addEventListener("mousedown", handler);
        return () => document.removeEventListener("mousedown", handler);
    }, [open]);

    if (organizations.length <= 1) return null;

    return (
        <div ref={ref} className="relative">
            <button
                onClick={() => setOpen(p => !p)}
                className={`flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left transition-colors hover:bg-sidebar-accent ${collapsed ? "justify-center px-2" : ""}`}
                title={collapsed ? currentOrganizationName ?? "Workspace" : undefined}
            >
                <div
                    className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                    <Globe2 className="h-3.5 w-3.5"/>
                </div>
                {!collapsed && (
                    <>
                        <div className="min-w-0 flex-1">
                            <p className="truncate text-xs font-semibold text-foreground">
                                {currentOrganizationName ?? "Workspace"}
                            </p>
                            {currentOrganizationRole && (
                                <p className="text-[10px] text-sidebar-muted capitalize">
                                    {currentOrganizationRole.toLowerCase()}
                                </p>
                            )}
                        </div>
                        <ChevronDown
                            className={`h-3.5 w-3.5 shrink-0 text-sidebar-muted transition-transform duration-200 ${open ? "rotate-180" : ""}`}/>
                    </>
                )}
            </button>

            {open && (
                <div
                    className={`absolute ${collapsed ? "left-full ml-2 top-0" : "bottom-full left-0 right-0 mb-2"} z-50 rounded-xl border border-border bg-card p-1.5 shadow-xl`}>
                    <p className="px-2 py-1 text-[10px] font-bold uppercase tracking-widest text-muted-foreground">
                        Workspaces
                    </p>
                    {organizations.map((org) => (
                        <button
                            key={org.id}
                            onClick={async () => {
                                if (org.id !== currentOrganizationId) {
                                    await switchOrg(org.id);
                                }
                                setOpen(false);
                            }}
                            className={`flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left text-sm transition-colors ${
                                org.id === currentOrganizationId
                                    ? "bg-primary/10 text-primary font-medium"
                                    : "text-foreground hover:bg-secondary"
                            }`}
                        >
                            <Globe2 className="h-3.5 w-3.5 shrink-0"/>
                            <span className="truncate">{org.name}</span>
                            {org.role === "MEMBER" && (
                                <span
                                    className="shrink-0 rounded-full bg-secondary px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-muted-foreground">
                                    Member
                                </span>
                            )}
                            {org.id === currentOrganizationId && (
                                <CheckCircle className="ml-auto h-3.5 w-3.5 shrink-0 text-primary"/>
                            )}
                        </button>
                    ))}
                </div>
            )}
        </div>
    );
}

// ── Main Sidebar ─────────────────────────────────────────────────────────────

interface SidebarProps {
    collapsed: boolean;
    onToggleCollapse: () => void;
    mobileOpen: boolean;
    onMobileClose: () => void;
}

export function Sidebar({collapsed, onToggleCollapse, mobileOpen, onMobileClose}: SidebarProps) {
    const {email, isAdmin, currentOrganizationRole} = useAuth();
    const {theme, toggleTheme} = useTheme();
    const {unreadSendersCount} = useMessages();
    const {unreadCount: unreadNotifications} = useNotifications();
    const [notificationsOpen, setNotificationsOpen] = useState(false);
    const pathname = usePathname();

    useEffect(() => {
        setNotificationsOpen(false);
    }, [pathname]);
    const [unresolvedReports, setUnresolvedReports] = useState<number>(0);

    const isMemberView = currentOrganizationRole === "MEMBER" && !isAdmin;

    // Poll unresolved report count for admins
    useEffect(() => {
        if (!isAdmin) return;
        const fetchCount = () =>
            apiClient("/api/v1/admin/reports/count")
                .then((r) => r.ok ? r.json() : {count: 0})
                .then((d: {count: number}) => setUnresolvedReports((prev) => prev !== d.count ? d.count : prev))
                .catch(() => {});
        fetchCount();
        const timer = setInterval(fetchCount, 60_000);
        return () => clearInterval(timer);
    }, [isAdmin]);

    // Active states
    const onDashboard = pathname === "/dashboard";
    const onMessages = pathname === "/dashboard/messages";
    const onWebhooks = pathname === "/dashboard/settings/webhooks";
    const onTeam = pathname === "/dashboard/team";
    const onNotificationSettings = pathname === "/dashboard/settings/notifications";
    const onPrivacySettings = pathname === "/dashboard/settings/privacy";
    const onTrustProfile = pathname === "/dashboard/settings/trust-profile";
    const onAdminOverview = pathname === "/admin/overview";
    const onAdminUsers = pathname === "/admin/users";
    const onAdminFallbacks = pathname.startsWith("/admin/compliance/global-fallbacks");
    const onAdminCompliance = pathname.startsWith("/admin/compliance") && !onAdminFallbacks;
    const onAdminOrgs = pathname === "/admin/organizations";
    const onAdminReports = pathname === "/admin/reports";
    const onAdminAbuse = pathname === "/admin/abuse";
    const onAdminSync = pathname === "/admin/monitor-settings";


    // Close mobile sidebar on route change
    useEffect(() => {
        onMobileClose();
    }, [pathname, onMobileClose]);

    const sidebarContent = (
        <div className="flex h-full flex-col">
            {/* Logo */}
            <div className={`flex items-center gap-2.5 px-4 py-5 ${collapsed ? "flex-col" : ""}`}>
                <Link href="/dashboard" className="flex items-center gap-2.5 group min-w-0" onClick={onMobileClose}>
                    <div
                        className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-primary shadow-md shadow-primary/25 group-hover:shadow-primary/40 transition-shadow">
                        <Shield className="h-4 w-4 text-white"/>
                    </div>
                    {!collapsed && (
                        <span className="text-base font-black tracking-tight text-foreground">
                            nabu<span className="text-primary">.watch</span>
                        </span>
                    )}
                </Link>
                {/* Collapse/expand toggle — desktop, stable position */}
                <button
                    onClick={onToggleCollapse}
                    className={`hidden lg:flex h-7 w-7 shrink-0 items-center justify-center rounded-lg text-sidebar-muted hover:bg-sidebar-accent hover:text-foreground transition-colors ${collapsed ? "" : "ml-auto"}`}
                    title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
                >
                    {collapsed ? <PanelLeft className="h-4 w-4"/> : <PanelLeftClose className="h-4 w-4"/>}
                </button>
                {/* Mobile close */}
                <button
                    onClick={onMobileClose}
                    className="ml-auto flex lg:hidden h-7 w-7 items-center justify-center rounded-lg text-sidebar-muted hover:bg-sidebar-accent hover:text-foreground transition-colors"
                >
                    <X className="h-4 w-4"/>
                </button>
            </div>

            {/* Navigation */}
            <nav className="flex-1 overflow-y-auto px-3 pb-4 space-y-0.5">
                {/* Main section */}
                <NavItem href="/dashboard" icon={LayoutDashboard} label="Dashboard" active={onDashboard}
                         collapsed={collapsed} onClick={onMobileClose}/>
                <NavItem href="/dashboard/messages" icon={Mail} label="Messages" active={onMessages}
                         collapsed={collapsed} badge={unreadSendersCount} onClick={onMobileClose}/>
                <button
                    type="button"
                    onClick={() => setNotificationsOpen(v => !v)}
                    className={`group relative w-full hidden md:flex items-center gap-3 rounded-xl px-3 py-2 text-[13px] font-medium transition-all duration-200 ${
                        notificationsOpen
                            ? "bg-primary text-white shadow-sm shadow-primary/25"
                            : "text-sidebar-foreground hover:bg-sidebar-accent hover:text-foreground"
                    }`}
                    title={collapsed ? "Notifications" : undefined}
                >
                    <Bell className={`h-4 w-4 shrink-0 ${notificationsOpen ? "text-white" : "text-sidebar-muted group-hover:text-foreground"}`}/>
                    {!collapsed && <span className="flex-1 text-left">Notifications</span>}
                    {unreadNotifications > 0 && (
                        <span className={`${collapsed ? "absolute right-1 top-1" : "ml-auto"} flex h-4 min-w-4 items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-bold text-white`}>
                            {unreadNotifications > 9 ? "9+" : unreadNotifications}
                        </span>
                    )}
                </button>
                <NavItem href="/dashboard/settings/webhooks" icon={Webhook} label="Webhooks" active={onWebhooks}
                         collapsed={collapsed} onClick={onMobileClose}/>

                {/* Settings section */}
                <SectionLabel collapsed={collapsed}>Settings</SectionLabel>
                <NavItem href="/dashboard/team" icon={Users} label="Team" active={onTeam}
                         collapsed={collapsed} onClick={onMobileClose}/>
                <NavItem href="/dashboard/settings/notifications" icon={Bell} label="Alert Preferences"
                         active={onNotificationSettings} collapsed={collapsed} onClick={onMobileClose}/>
                <NavItem href="/dashboard/settings/privacy" icon={Shield} label="Privacy & Data"
                         active={onPrivacySettings} collapsed={collapsed} onClick={onMobileClose}/>
                <NavItem href="/dashboard/settings/trust-profile" icon={BadgeCheck} label="Trust Profile"
                         active={onTrustProfile} collapsed={collapsed} onClick={onMobileClose}/>

                {/* Admin section */}
                {isAdmin && (
                    <>
                        <SectionLabel collapsed={collapsed}>Administration</SectionLabel>

                        {collapsed ? (
                            // Collapsed: icons only, same order as expanded (Platform → Compliance → System)
                            <>
                                <NavItem href="/admin/users" icon={Users} label="Users" active={onAdminUsers}
                                         collapsed={collapsed} onClick={onMobileClose}/>
                                <NavItem href="/admin/organizations" icon={Building2} label="Organizations"
                                         active={onAdminOrgs} collapsed={collapsed} onClick={onMobileClose}/>
                                <NavItem href="/admin/reports" icon={Flag} label="Reports"
                                         active={onAdminReports} collapsed={collapsed} onClick={onMobileClose}
                                         badge={unresolvedReports > 0 ? unresolvedReports : undefined}/>
                                <NavItem href="/admin/abuse" icon={AlertTriangle} label="Abuse"
                                         active={onAdminAbuse} collapsed={collapsed} onClick={onMobileClose}/>
                                <NavItem href="/admin/compliance" icon={Globe2} label="Regions"
                                         active={onAdminCompliance} collapsed={collapsed} onClick={onMobileClose}/>
                                <NavItem href="/admin/compliance/global-fallbacks" icon={BookOpen} label="Fallbacks"
                                         active={onAdminFallbacks} collapsed={collapsed} onClick={onMobileClose}/>
                                <NavItem href="/admin/overview" icon={ShieldAlert} label="Overview"
                                         active={onAdminOverview} collapsed={collapsed} onClick={onMobileClose}/>
                                <NavItem href="/admin/monitor-settings" icon={RefreshCw} label="Monitor Settings"
                                         active={onAdminSync} collapsed={collapsed} onClick={onMobileClose}/>
                            </>
                        ) : (
                            // Expanded: show with subsections
                            <>
                                {/* Platform */}
                                <p className="px-3 pt-1 text-[10px] font-semibold uppercase tracking-wider text-amber-500/70">Platform</p>
                                {[
                                    {href: "/admin/users", icon: Users, label: "Users", active: onAdminUsers},
                                    {
                                        href: "/admin/organizations",
                                        icon: Building2,
                                        label: "Organizations",
                                        active: onAdminOrgs
                                    },
                                ].map(item => (
                                    <Link key={item.href} href={item.href} onClick={onMobileClose}
                                          className={`flex items-center gap-2.5 rounded-xl px-3 py-1.5 text-[13px] font-medium transition-all duration-200 ${
                                              item.active
                                                  ? "bg-amber-500/10 text-amber-600 dark:text-amber-400"
                                                  : "text-sidebar-foreground hover:bg-sidebar-accent"
                                          }`}>
                                        <item.icon className="h-4 w-4 shrink-0"/>
                                        {item.label}
                                    </Link>
                                ))}
                                <Link href="/admin/reports" onClick={onMobileClose}
                                      className={`flex items-center gap-2.5 rounded-xl px-3 py-1.5 text-[13px] font-medium transition-all duration-200 ${
                                          onAdminReports
                                              ? "bg-amber-500/10 text-amber-600 dark:text-amber-400"
                                              : "text-sidebar-foreground hover:bg-sidebar-accent"
                                      }`}>
                                    <Flag className="h-4 w-4 shrink-0"/>
                                    Reports
                                    {unresolvedReports > 0 && (
                                        <span className="ml-auto flex h-4 min-w-4 items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-bold text-white">
                                            {unresolvedReports > 9 ? "9+" : unresolvedReports}
                                        </span>
                                    )}
                                </Link>
                                <Link href="/admin/abuse" onClick={onMobileClose}
                                      className={`flex items-center gap-2.5 rounded-xl px-3 py-1.5 text-[13px] font-medium transition-all duration-200 ${
                                          onAdminAbuse
                                              ? "bg-amber-500/10 text-amber-600 dark:text-amber-400"
                                              : "text-sidebar-foreground hover:bg-sidebar-accent"
                                      }`}>
                                    <AlertTriangle className="h-4 w-4 shrink-0"/>
                                    Abuse
                                </Link>

                                {/* Compliance */}
                                <p className="px-3 pt-2 text-[10px] font-semibold uppercase tracking-wider text-amber-500/70">Compliance</p>
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
                                ].map(item => (
                                    <Link key={item.href} href={item.href} onClick={onMobileClose}
                                          className={`flex items-center gap-2.5 rounded-xl px-3 py-1.5 text-[13px] font-medium transition-all duration-200 ${
                                              item.active
                                                  ? "bg-amber-500/10 text-amber-600 dark:text-amber-400"
                                                  : "text-sidebar-foreground hover:bg-sidebar-accent"
                                          }`}>
                                        <item.icon className="h-4 w-4 shrink-0"/>
                                        {item.label}
                                    </Link>
                                ))}

                                {/* System */}
                                <p className="px-3 pt-2 text-[10px] font-semibold uppercase tracking-wider text-amber-500/70">System</p>
                                {[
                                    {
                                        href: "/admin/overview",
                                        icon: ShieldAlert,
                                        label: "Overview",
                                        active: onAdminOverview
                                    },
                                    {
                                        href: "/admin/monitor-settings",
                                        icon: RefreshCw,
                                        label: "Monitor Settings",
                                        active: onAdminSync
                                    },
                                ].map(item => (
                                    <Link key={item.href} href={item.href} onClick={onMobileClose}
                                          className={`flex items-center gap-2.5 rounded-xl px-3 py-1.5 text-[13px] font-medium transition-all duration-200 ${
                                              item.active
                                                  ? "bg-amber-500/10 text-amber-600 dark:text-amber-400"
                                                  : "text-sidebar-foreground hover:bg-sidebar-accent"
                                          }`}>
                                        <item.icon className="h-4 w-4 shrink-0"/>
                                        {item.label}
                                    </Link>
                                ))}
                            </>
                        )}
                    </>
                )}
            </nav>

            {/* Bottom section */}
            <div className="border-t border-sidebar-border px-3 py-3 space-y-1">
                {/* Org switcher */}
                <OrgSwitcher collapsed={collapsed}/>

                {/* Theme toggle */}
                <button
                    onClick={toggleTheme}
                    className={`flex w-full items-center gap-3 rounded-xl px-3 py-2 text-[13px] font-medium text-sidebar-foreground transition-colors hover:bg-sidebar-accent ${collapsed ? "justify-center px-2" : ""}`}
                    title={collapsed ? (theme === "dark" ? "Light mode" : "Dark mode") : undefined}
                >
                    {theme === "dark" ? <Sun className="h-[18px] w-[18px] shrink-0 text-sidebar-muted"/> :
                        <Moon className="h-[18px] w-[18px] shrink-0 text-sidebar-muted"/>}
                    {!collapsed && (theme === "dark" ? "Light mode" : "Dark mode")}
                </button>

                {/* Profile */}
                <div
                    className={`flex items-center gap-2.5 rounded-xl px-3 py-2 ${collapsed ? "justify-center px-2" : ""}`}>
                    <div
                        className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white ${isAdmin ? "bg-amber-500" : isMemberView ? "bg-purple-500" : "bg-primary"}`}>
                        <User className="h-3.5 w-3.5"/>
                    </div>
                    {!collapsed && (
                        <div className="min-w-0 flex-1">
                            <p className="truncate text-xs font-semibold text-foreground">
                                {email?.split("@")[0] ?? "Account"}
                            </p>
                            <p className="text-[10px] text-sidebar-muted">
                                {isAdmin ? "Admin" : "User"}
                            </p>
                        </div>
                    )}
                    {!collapsed && (
                        <button
                            onClick={logout}
                            className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg text-sidebar-muted hover:bg-red-500/10 hover:text-red-500 transition-colors"
                            title="Sign out"
                        >
                            <LogOut className="h-3.5 w-3.5"/>
                        </button>
                    )}
                </div>

            </div>
        </div>
    );

    return (
        <>
            {/* Desktop sidebar */}
            <aside
                className={`hidden lg:flex flex-col fixed left-0 top-0 bottom-0 z-40 bg-sidebar border-r border-sidebar-border transition-[width] duration-300 ease-out ${
                    collapsed ? "w-[var(--sidebar-collapsed-width)]" : "w-[var(--sidebar-width)]"
                }`}
            >
                {sidebarContent}
            </aside>

            <SidebarNotificationPanel
                open={notificationsOpen}
                sidebarCollapsed={collapsed}
                onClose={() => setNotificationsOpen(false)}
            />

            {/* Mobile sidebar overlay */}
            {mobileOpen && (
                <>
                    <div
                        className="fixed inset-0 z-40 bg-black/50 lg:hidden animate-sidebar-overlay"
                        onClick={onMobileClose}
                    />
                    <aside
                        className="fixed left-0 top-0 bottom-0 z-50 w-[280px] bg-sidebar border-r border-sidebar-border lg:hidden animate-sidebar-in"
                    >
                        {sidebarContent}
                    </aside>
                </>
            )}
        </>
    );
}
