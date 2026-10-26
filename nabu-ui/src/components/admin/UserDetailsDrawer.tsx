"use client";

import {useCallback, useEffect, useState} from "react";
import {
    Activity,
    Ban,
    CheckCircle,
    ExternalLink,
    Globe,
    Loader2,
    ShieldAlert,
    ShieldCheck,
    Trash2,
    X,
} from "lucide-react";
import {Sheet, SheetContent} from "@/components/ui/sheet";
import {SecureDeleteModal} from "@/components/dashboard/SecureDeleteModal";
import {apiClient} from "@/lib/apiClient";
import {extractErrorMessage, formatRelativeTime} from "@/lib/utils";
import type {ScanActivityItem, UserSummaryItem, UserWebsiteItem} from "@/lib/types";

// ── shared badge helpers ───────────────────────────────────────────────────────

function StatusBadge({status}: { status: string }) {
    const map: Record<string, string> = {
        ACTIVE: "bg-green-950/60  text-green-400  ring-1 ring-green-900/40",
        PENDING_VERIFICATION: "bg-yellow-950/60 text-yellow-400 ring-1 ring-yellow-900/40",
        SUSPENDED: "bg-orange-950/60 text-orange-400 ring-1 ring-orange-900/40",
        DELETED: "bg-muted          text-muted-foreground ring-1 ring-border",
    };
    const labels: Record<string, string> = {
        ACTIVE: "Active", PENDING_VERIFICATION: "Pending",
        SUSPENDED: "Suspended", DELETED: "Deleted",
    };
    return (
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${map[status] ?? map.ACTIVE}`}>
      {labels[status] ?? status}
    </span>
    );
}

function SeverityBadge({severity}: { severity: string }) {
    const map: Record<string, string> = {
        CRITICAL: "bg-red-950/60    text-red-400    ring-1 ring-red-900/40",
        HIGH: "bg-orange-950/60 text-orange-400 ring-1 ring-orange-900/40",
        MEDIUM: "bg-yellow-950/60 text-yellow-400 ring-1 ring-yellow-900/40",
        INFO: "bg-slate-800      text-slate-400  ring-1 ring-slate-700",
        OK: "bg-green-950/60  text-green-400  ring-1 ring-green-900/40",
    };
    return (
        <span className={`rounded-full px-1.5 py-0.5 text-[10px] font-semibold uppercase ${map[severity] ?? map.INFO}`}>
      {severity}
    </span>
    );
}

function WebsiteStatusDot({status}: { status: string }) {
    const c: Record<string, string> = {
        ACTIVE: "bg-green-400", PAUSED: "bg-yellow-400", DELETED: "bg-slate-600",
    };
    return <span className={`inline-block h-1.5 w-1.5 rounded-full ${c[status] ?? "bg-slate-500"}`}/>;
}

// ── section heading ────────────────────────────────────────────────────────────

function SectionHeading({icon: Icon, label, count}: { icon: React.ElementType; label: string; count?: number }) {
    return (
        <div className="flex items-center gap-2 border-b border-border bg-secondary/20 px-4 py-2">
            <Icon className="h-3.5 w-3.5 text-muted-foreground"/>
            <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{label}</span>
            {count !== undefined && (
                <span
                    className="ml-auto rounded-full bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">{count}</span>
            )}
        </div>
    );
}

// ── websites list (adaptive) ───────────────────────────────────────────────────

function WebsitesList({userId, desktop}: { userId: string; desktop?: boolean }) {
    const [sites, setSites] = useState<UserWebsiteItem[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        setLoading(true);
        setError(null);
        apiClient(`/api/v1/admin/users/${userId}/websites`)
            .then(async (res) => {
                if (!res.ok) throw new Error(await extractErrorMessage(res, `HTTP ${res.status}`));
                return res.json() as Promise<UserWebsiteItem[]>;
            })
            .then(setSites)
            .catch((e) => setError(e instanceof Error ? e.message : "Failed to load websites"))
            .finally(() => setLoading(false));
    }, [userId]);

    if (loading)
        return (
            <div className="flex items-center justify-center py-8">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground"/>
            </div>
        );
    if (error)
        return <p className="py-6 text-center text-xs text-red-400">{error}</p>;
    if (sites.length === 0)
        return (
            <div className="flex flex-col items-center gap-2 py-8 text-center">
                <Globe className="h-6 w-6 text-muted-foreground"/>
                <p className="text-xs text-muted-foreground">No websites</p>
            </div>
        );

    if (desktop) {
        // Proper table layout for lg+ screens
        return (
            <table className="w-full text-xs">
                <thead>
                <tr className="border-b border-border bg-secondary/30 text-left text-muted-foreground">
                    <th className="px-4 py-2 font-normal">URL</th>
                    <th className="px-3 py-2 font-normal">Region</th>
                    <th className="px-3 py-2 font-normal">Status</th>
                    <th className="w-8 px-3 py-2"/>
                </tr>
                </thead>
                <tbody className="divide-y divide-border">
                {sites.map((s) => (
                    <tr key={s.id} className="hover:bg-secondary/20 transition-colors">
                        <td className="px-4 py-2.5">
                            <div className="flex items-center gap-2 min-w-0">
                                <WebsiteStatusDot status={s.status}/>
                                <span className="truncate max-w-[260px] text-foreground">{s.url}</span>
                            </div>
                        </td>
                        <td className="px-3 py-2.5 text-muted-foreground">{s.region}</td>
                        <td className="px-3 py-2.5"><StatusBadge status={s.status}/></td>
                        <td className="px-3 py-2.5">
                            <a
                                href={s.url}
                                target="_blank"
                                rel="noopener noreferrer"
                                onClick={(e) => e.stopPropagation()}
                                className="text-muted-foreground hover:text-foreground"
                            >
                                <ExternalLink className="h-3.5 w-3.5"/>
                            </a>
                        </td>
                    </tr>
                ))}
                </tbody>
            </table>
        );
    }

    // Mobile compact list
    return (
        <ul className="divide-y divide-border">
            {sites.map((s) => (
                <li key={s.id} className="flex items-center gap-3 px-4 py-2.5">
                    <WebsiteStatusDot status={s.status}/>
                    <div className="min-w-0 flex-1">
                        <p className="truncate text-sm text-foreground">{s.url}</p>
                        <p className="text-xs text-muted-foreground">{s.region}</p>
                    </div>
                    <StatusBadge status={s.status}/>
                    <a
                        href={s.url}
                        target="_blank"
                        rel="noopener noreferrer"
                        onClick={(e) => e.stopPropagation()}
                        className="shrink-0 text-muted-foreground hover:text-foreground"
                    >
                        <ExternalLink className="h-3.5 w-3.5"/>
                    </a>
                </li>
            ))}
        </ul>
    );
}

// ── activity list (adaptive) ───────────────────────────────────────────────────

function ActivityList({userId, desktop}: { userId: string; desktop?: boolean }) {
    const [scans, setScans] = useState<ScanActivityItem[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        setLoading(true);
        setError(null);
        apiClient(`/api/v1/admin/users/${userId}/scans`)
            .then(async (res) => {
                if (!res.ok) throw new Error(await extractErrorMessage(res, `HTTP ${res.status}`));
                return res.json() as Promise<ScanActivityItem[]>;
            })
            .then(setScans)
            .catch((e) => setError(e instanceof Error ? e.message : "Failed to load activity"))
            .finally(() => setLoading(false));
    }, [userId]);

    if (loading)
        return (
            <div className="flex items-center justify-center py-8">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground"/>
            </div>
        );
    if (error)
        return <p className="py-6 text-center text-xs text-red-400">{error}</p>;
    if (scans.length === 0)
        return (
            <div className="flex flex-col items-center gap-2 py-8 text-center">
                <Activity className="h-6 w-6 text-muted-foreground"/>
                <p className="text-xs text-muted-foreground">No recent scans</p>
            </div>
        );

    if (desktop) {
        return (
            <table className="w-full text-xs">
                <thead>
                <tr className="border-b border-border bg-secondary/30 text-left text-muted-foreground">
                    <th className="px-4 py-2 font-normal">Website</th>
                    <th className="px-3 py-2 font-normal">Type</th>
                    <th className="px-3 py-2 font-normal">Severity</th>
                    <th className="px-3 py-2 font-normal">When</th>
                </tr>
                </thead>
                <tbody className="divide-y divide-border">
                {scans.map((s) => (
                    <tr key={s.scanLogId} className="hover:bg-secondary/20 transition-colors">
                        <td className="px-4 py-2">
                            <span className="truncate max-w-[200px] block text-foreground">{s.websiteUrl}</span>
                        </td>
                        <td className="px-3 py-2 text-muted-foreground">
                            {s.monitorType} · {s.subType.replace(/_/g, " ")}
                        </td>
                        <td className="px-3 py-2"><SeverityBadge severity={s.severity}/></td>
                        <td className="px-3 py-2 text-muted-foreground tabular-nums">
                            {formatRelativeTime(s.scannedAt)}
                        </td>
                    </tr>
                ))}
                </tbody>
            </table>
        );
    }

    // Mobile compact list
    return (
        <ul className="divide-y divide-border">
            {scans.map((s) => (
                <li key={s.scanLogId} className="flex items-start gap-3 px-4 py-2.5">
                    <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                        <p className="truncate text-xs text-foreground">{s.websiteUrl}</p>
                        <p className="text-[10px] text-muted-foreground">
                            {s.monitorType} · {s.subType.replace(/_/g, " ")}
                        </p>
                    </div>
                    <div className="flex shrink-0 flex-col items-end gap-1">
                        <SeverityBadge severity={s.severity}/>
                        <p className="text-[10px] text-muted-foreground">{formatRelativeTime(s.scannedAt)}</p>
                    </div>
                </li>
            ))}
        </ul>
    );
}

// ── main component ─────────────────────────────────────────────────────────────

type Tab = "websites" | "activity";

interface UserDetailsDrawerProps {
    user: UserSummaryItem | null;
    open: boolean;
    onClose: () => void;
    onStatusChanged?: (userId: string, newStatus: string) => void;
    onDeleted?: (userId: string) => void;
}

export function UserDetailsDrawer({
                                      user,
                                      open,
                                      onClose,
                                      onStatusChanged,
                                      onDeleted,
                                  }: UserDetailsDrawerProps) {
    const [tab, setTab] = useState<Tab>("websites");
    const [statusLoading, setStatusLoading] = useState(false);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);

    useEffect(() => {
        if (user) setTab("websites");
    }, [user?.id]);

    const handleToggleStatus = useCallback(async () => {
        if (!user) return;
        const newStatus = user.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
        setStatusLoading(true);
        try {
            const res = await apiClient(`/api/v1/admin/users/${user.id}/status`, {
                method: "PATCH",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({status: newStatus}),
            });
            if (!res.ok) throw new Error(await extractErrorMessage(res, `HTTP ${res.status}`));
            onStatusChanged?.(user.id, newStatus);
        } catch (e) {
            console.error("Failed to update user status", e);
        } finally {
            setStatusLoading(false);
        }
    }, [user, onStatusChanged]);

    const handleDelete = useCallback(async (_otpCode?: string) => {
        if (!user) return;
        setDeleteLoading(true);
        try {
            const res = await apiClient(`/api/v1/admin/users/${user.id}`, {method: "DELETE"});
            if (!res.ok) throw new Error(await extractErrorMessage(res, `HTTP ${res.status}`));
            setDeleteOpen(false);
            onDeleted?.(user.id);
            onClose();
        } catch (e) {
            console.error("Failed to delete user", e);
        } finally {
            setDeleteLoading(false);
        }
    }, [user, onDeleted, onClose]);

    if (!user) return null;

    const isSuspended = user.status === "SUSPENDED";

    const mobileTabClass = (t: Tab) =>
        `flex-1 py-2.5 text-xs font-medium transition-colors border-b-2 ${
            tab === t
                ? "border-primary text-foreground"
                : "border-transparent text-muted-foreground hover:text-foreground"
        }`;

    return (
        <>
            {/*
       * panelClassName overrides the Sheet's default lg:w-[52vw] to a wider
       * workspace on large screens while keeping the mobile behaviour identical.
       */}
            <Sheet
                open={open}
                onOpenChange={(v) => !v && onClose()}
                panelClassName="lg:w-[72vw] xl:w-[62vw]"
            >
                <SheetContent className="flex h-full w-full flex-col gap-0 overflow-hidden p-0">

                    {/*
           * ─── TWO-COLUMN GRID (lg+) ────────────────────────────────────────
           * On large screens the drawer becomes a side-by-side workspace:
           *   Left  (320 px) — profile, stats, actions
           *   Right (flex-1) — websites table + activity feed (always visible)
           * On mobile it collapses to a single scrollable column with tabs.
           */}
                    <div className="flex min-h-0 flex-1 flex-col lg:grid lg:grid-cols-[320px_1fr]">

                        {/* ── LEFT COLUMN ─────────────────────────────────────────────── */}
                        <div
                            className="flex flex-col border-b border-border lg:border-b-0 lg:border-r lg:overflow-y-auto">

                            {/* Profile header */}
                            <div className="flex items-start justify-between border-b border-border px-4 pb-4 pt-4">
                                <div className="flex items-start gap-3">
                                    <div
                                        className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-full text-lg font-bold text-white ${
                                            user.role === "ADMIN" ? "bg-amber-600" : "bg-primary"
                                        }`}
                                    >
                                        {user.fullName.charAt(0).toUpperCase()}
                                    </div>
                                    <div className="min-w-0">
                                        <div className="flex flex-wrap items-center gap-2">
                                            <h2 className="text-base font-semibold text-foreground">{user.fullName}</h2>
                                            {user.role === "ADMIN" && (
                                                <span
                                                    className="flex items-center gap-0.5 rounded-full bg-amber-950/60 px-1.5 py-0.5 text-[10px] font-bold text-amber-400 ring-1 ring-amber-900/40">
                          <ShieldAlert className="h-2.5 w-2.5"/>
                          ADMIN
                        </span>
                                            )}
                                        </div>
                                        <p className="mt-0.5 truncate text-xs text-muted-foreground">{user.email}</p>
                                        <div className="mt-1.5 flex flex-wrap items-center gap-2">
                                            <StatusBadge status={user.status}/>
                                        </div>
                                    </div>
                                </div>
                                <button
                                    onClick={onClose}
                                    className="mt-0.5 shrink-0 rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
                                >
                                    <X className="h-4 w-4"/>
                                </button>
                            </div>

                            {/* Stats bar — 3–4 items in a row */}
                            <div
                                className="grid grid-cols-3 divide-x divide-border border-b border-border bg-secondary/20">
                                <div className="flex flex-col items-center py-3">
                                    <span
                                        className="text-lg font-bold tabular-nums text-foreground">{user.siteCount}</span>
                                    <span className="text-[10px] text-muted-foreground">Sites</span>
                                </div>
                                <div className="flex flex-col items-center py-3">
                  <span className="text-sm font-medium text-foreground">
                    {formatRelativeTime(user.createdAt)}
                  </span>
                                    <span className="text-[10px] text-muted-foreground">Joined</span>
                                </div>
                                <div className="flex flex-col items-center py-3">
                  <span className="text-sm font-medium text-foreground">
                    {user.lastLoginAt ? formatRelativeTime(user.lastLoginAt) : "—"}
                  </span>
                                    <span className="text-[10px] text-muted-foreground">Last login</span>
                                </div>
                            </div>

                            {/* Action buttons */}
                            <div className="flex flex-wrap gap-2 border-b border-border px-4 py-3">
                                <button
                                    onClick={handleToggleStatus}
                                    disabled={statusLoading || user.status === "DELETED"}
                                    className={`flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors disabled:opacity-40 ${
                                        isSuspended
                                            ? "bg-green-950/60 text-green-400 ring-1 ring-green-900/40 hover:bg-green-950/80"
                                            : "bg-orange-950/60 text-orange-400 ring-1 ring-orange-900/40 hover:bg-orange-950/80"
                                    }`}
                                >
                                    {statusLoading ? (
                                        <Loader2 className="h-3.5 w-3.5 animate-spin"/>
                                    ) : isSuspended ? (
                                        <CheckCircle className="h-3.5 w-3.5"/>
                                    ) : (
                                        <Ban className="h-3.5 w-3.5"/>
                                    )}
                                    {isSuspended ? "Activate" : "Suspend"}
                                </button>

                                <button
                                    onClick={() => setDeleteOpen(true)}
                                    disabled={user.status === "DELETED"}
                                    className="flex items-center gap-1.5 rounded-lg bg-red-950/60 px-3 py-1.5 text-xs font-medium text-red-400 ring-1 ring-red-900/40 transition-colors hover:bg-red-950/80 disabled:opacity-40"
                                >
                                    <Trash2 className="h-3.5 w-3.5"/>
                                    Delete
                                </button>

                                <div className="ml-auto flex items-center gap-1 text-xs text-muted-foreground">
                                    {user.role === "ADMIN" ? (
                                        <ShieldAlert className="h-3.5 w-3.5 text-amber-400"/>
                                    ) : (
                                        <ShieldCheck className="h-3.5 w-3.5 text-slate-500"/>
                                    )}
                                    {user.role}
                                </div>
                            </div>

                            {/* Account detail — visible on desktop left column */}
                            <div className="hidden lg:block border-b border-border px-4 py-4">
                                <div className="flex items-center justify-between">
                                    <span className="text-sm text-foreground">Account status</span>
                                    <StatusBadge status={user.status}/>
                                </div>
                                <div className="mt-2 flex items-center justify-between">
                                    <span className="text-sm text-foreground">Role</span>
                                    <span className="text-xs text-muted-foreground">{user.role}</span>
                                </div>
                            </div>
                        </div>

                        {/* ── RIGHT COLUMN ─────────────────────────────────────────────── */}
                        <div className="flex min-h-0 flex-1 flex-col overflow-hidden">

                            {/* Mobile tabs — hidden on lg+ */}
                            <div className="flex border-b border-border lg:hidden">
                                <button className={mobileTabClass("websites")} onClick={() => setTab("websites")}>
                                    Websites
                                </button>
                                <button className={mobileTabClass("activity")} onClick={() => setTab("activity")}>
                                    Activity
                                </button>
                            </div>

                            {/* Mobile single-pane scroll */}
                            <div className="min-h-0 flex-1 overflow-y-auto lg:hidden">
                                {tab === "websites" ? (
                                    <WebsitesList userId={user.id} key={`ws-mob-${user.id}`}/>
                                ) : (
                                    <ActivityList userId={user.id} key={`act-mob-${user.id}`}/>
                                )}
                            </div>

                            {/* Desktop two-pane stack (websites top, activity bottom) */}
                            <div className="hidden min-h-0 flex-1 flex-col lg:flex">

                                {/* Websites section — takes 55% */}
                                <div className="flex min-h-0 flex-col border-b border-border" style={{flex: "55 1 0%"}}>
                                    <SectionHeading icon={Globe} label="Websites" count={undefined}/>
                                    <div className="min-h-0 flex-1 overflow-y-auto">
                                        <WebsitesList userId={user.id} desktop key={`ws-desk-${user.id}`}/>
                                    </div>
                                </div>

                                {/* Activity section — takes 45% */}
                                <div className="flex min-h-0 flex-col" style={{flex: "45 1 0%"}}>
                                    <SectionHeading icon={Activity} label="Recent Activity"/>
                                    <div className="min-h-0 flex-1 overflow-y-auto">
                                        <ActivityList userId={user.id} desktop key={`act-desk-${user.id}`}/>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </SheetContent>
            </Sheet>

            {/*
       * Rendered outside the Sheet so the modal is centered relative to
       * the full viewport, not the drawer panel.
       */}
            {user && (
                <SecureDeleteModal
                    open={deleteOpen}
                    siteName={user.fullName}
                    requiresOtp={false}
                    onCancel={() => setDeleteOpen(false)}
                    onConfirm={handleDelete}
                    loading={deleteLoading}
                />
            )}
        </>
    );
}
