"use client";

import {useCallback, useEffect, useState} from "react";
import {
    Building2,
    ChevronDown,
    ChevronLeft,
    ChevronRight,
    ChevronUp,
    Loader2,
    RefreshCw,
    Search,
    Users
} from "lucide-react";
import {apiClient} from "@/lib/apiClient";

interface OrgItem {
    id: string;
    name: string;
    enabled: boolean;
    memberCount: number;
    websiteCount: number;
    createdAt: string;
}

interface OrgPageResponse {
    content: OrgItem[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

interface OrgMember {
    userId: string;
    fullName: string;
    email: string;
    role: string;
    joinedAt: string;
}

const PAGE_SIZE = 20;

export default function AdminOrganizationsPage() {
    const [response, setResponse] = useState<OrgPageResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [page, setPage] = useState(0);
    const [search, setSearch] = useState("");
    const [searchInput, setSearchInput] = useState("");

    // Expanded org → members
    const [expandedOrgId, setExpandedOrgId] = useState<string | null>(null);
    const [members, setMembers] = useState<OrgMember[]>([]);
    const [membersLoading, setMembersLoading] = useState(false);

    const fetchOrgs = useCallback(
        async (silent = false, targetPage = page, query = search) => {
            if (silent) setRefreshing(true);
            else setLoading(true);
            setError(null);

            try {
                const params = new URLSearchParams({page: String(targetPage), size: String(PAGE_SIZE)});
                if (query) params.set("search", query);
                const res = await apiClient(`/api/v1/admin/organizations?${params}`);
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                const data: OrgPageResponse = await res.json();
                setResponse(data);
            } catch (err) {
                setError(err instanceof Error ? err.message : "Failed to load organizations");
            } finally {
                setLoading(false);
                setRefreshing(false);
            }
        },
        [page, search]
    );

    useEffect(() => {
        fetchOrgs(false, page, search);
    }, [page, search]); // eslint-disable-line react-hooks/exhaustive-deps

    const handleSearch = () => {
        setPage(0);
        setSearch(searchInput.trim());
    };

    const toggleExpand = async (orgId: string) => {
        if (expandedOrgId === orgId) {
            setExpandedOrgId(null);
            return;
        }
        setExpandedOrgId(orgId);
        setMembersLoading(true);
        try {
            const res = await apiClient(`/api/v1/admin/organizations/${orgId}/members`);
            if (res.ok) setMembers(await res.json());
        } catch { /* ignore */
        } finally {
            setMembersLoading(false);
        }
    };

    const toggleEnabled = async (orgId: string, enabled: boolean) => {
        try {
            await apiClient(`/api/v1/admin/organizations/${orgId}/status`, {
                method: "PATCH",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({enabled}),
            });
            setResponse((prev) => {
                if (!prev) return prev;
                return {
                    ...prev,
                    content: prev.content.map((o) =>
                        o.id === orgId ? {...o, enabled} : o
                    ),
                };
            });
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to update status");
        }
    };

    const orgs = response?.content ?? [];
    const totalPages = response?.totalPages ?? 1;
    const totalElements = response?.totalElements ?? 0;

    return (
        <div className="pb-10">
            {/* Header */}
            <div className="pb-2 pt-4">
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-lg font-bold tracking-tight text-foreground flex items-center gap-2">
                            <Building2 className="h-5 w-5"/>
                            Organizations
                        </h1>
                        <p className="text-xs text-muted-foreground">
                            {loading ? "Loading…" : `${totalElements} organizations`}
                        </p>
                    </div>
                    <button
                        onClick={() => fetchOrgs(true, page, search)}
                        disabled={refreshing}
                        className="flex items-center gap-1.5 rounded-xl border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground disabled:opacity-40"
                    >
                        <RefreshCw className={`h-3.5 w-3.5 ${refreshing ? "animate-spin" : ""}`}/>
                        Refresh
                    </button>
                </div>
            </div>

            {/* Search */}
            <div className="flex gap-2 py-3">
                <div className="relative flex-1">
                    <Search className="absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground"/>
                    <input
                        type="text"
                        value={searchInput}
                        onChange={(e) => setSearchInput(e.target.value)}
                        onKeyDown={(e) => e.key === "Enter" && handleSearch()}
                        placeholder="Search organizations…"
                        className="w-full rounded-xl border border-border bg-card pl-9 pr-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:border-blue-400 focus:outline-none focus:ring-1 focus:ring-blue-400"
                    />
                </div>
                <button
                    onClick={handleSearch}
                    className="rounded-xl border border-border bg-card px-4 py-2 text-sm text-foreground transition-colors hover:bg-secondary"
                >
                    Search
                </button>
            </div>

            {/* Content */}
            <div className="space-y-4 py-1">
                {error ? (
                    <div className="rounded-xl border border-red-900/50 bg-red-950/30 px-4 py-6 text-center">
                        <p className="text-sm font-medium text-red-400">Failed to load organizations</p>
                        <p className="mt-1 text-xs text-red-400/70">{error}</p>
                        <button
                            onClick={() => fetchOrgs()}
                            className="mt-4 rounded-lg bg-red-950 px-4 py-2 text-xs text-red-300 transition-colors hover:bg-red-900"
                        >
                            Retry
                        </button>
                    </div>
                ) : loading ? (
                    <div className="flex min-h-[30vh] items-center justify-center">
                        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground"/>
                    </div>
                ) : orgs.length === 0 ? (
                    <div
                        className="rounded-xl border border-border bg-card px-4 py-10 text-center text-sm text-muted-foreground">
                        No organizations found
                    </div>
                ) : (
                    <div className="rounded-xl border border-border bg-card divide-y divide-border overflow-hidden">
                        {orgs.map((org) => (
                            <div key={org.id}>
                                <div className="flex items-center justify-between px-4 py-3 gap-3">
                                    <button
                                        onClick={() => toggleExpand(org.id)}
                                        className="flex items-center gap-3 min-w-0 flex-1 text-left"
                                    >
                                        <div
                                            className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-xs font-bold ${
                                                org.enabled
                                                    ? "bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400"
                                                    : "bg-slate-100 dark:bg-slate-800 text-slate-400 dark:text-slate-500"
                                            }`}>
                                            <Building2 className="h-4 w-4"/>
                                        </div>
                                        <div className="min-w-0 flex-1">
                                            <div className="flex items-center gap-2">
                                                <p className="truncate text-sm font-medium text-foreground">{org.name}</p>
                                                {!org.enabled && (
                                                    <span
                                                        className="shrink-0 rounded-full bg-red-100 dark:bg-red-900/30 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-red-600 dark:text-red-400">
                                                        Disabled
                                                    </span>
                                                )}
                                            </div>
                                            <div className="flex items-center gap-3 text-xs text-muted-foreground">
                                                <span className="flex items-center gap-1">
                                                    <Users className="h-3 w-3"/>
                                                    {org.memberCount}
                                                </span>
                                                <span>{org.websiteCount} sites</span>
                                                <span>{new Date(org.createdAt).toLocaleDateString()}</span>
                                            </div>
                                        </div>
                                        {expandedOrgId === org.id
                                            ? <ChevronUp className="h-4 w-4 shrink-0 text-muted-foreground"/>
                                            : <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground"/>
                                        }
                                    </button>

                                    <button
                                        onClick={() => toggleEnabled(org.id, !org.enabled)}
                                        className={`shrink-0 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
                                            org.enabled
                                                ? "bg-red-100 dark:bg-red-900/20 text-red-600 dark:text-red-400 hover:bg-red-200 dark:hover:bg-red-900/40"
                                                : "bg-green-100 dark:bg-green-900/20 text-green-600 dark:text-green-400 hover:bg-green-200 dark:hover:bg-green-900/40"
                                        }`}
                                    >
                                        {org.enabled ? "Disable" : "Enable"}
                                    </button>
                                </div>

                                {/* Expanded members panel */}
                                {expandedOrgId === org.id && (
                                    <div className="border-t border-border bg-secondary/30 px-4 py-3">
                                        {membersLoading ? (
                                            <div className="flex items-center gap-2 text-xs text-muted-foreground">
                                                <Loader2 className="h-3.5 w-3.5 animate-spin"/>
                                                Loading members…
                                            </div>
                                        ) : members.length === 0 ? (
                                            <p className="text-xs text-muted-foreground">No members</p>
                                        ) : (
                                            <div className="space-y-1.5">
                                                <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground mb-2">
                                                    Members ({members.length})
                                                </p>
                                                {members.map((m) => (
                                                    <div key={m.userId}
                                                         className="flex items-center justify-between text-xs">
                                                        <div className="flex items-center gap-2 min-w-0">
                                                            <span
                                                                className="truncate font-medium text-foreground">{m.fullName}</span>
                                                            <span
                                                                className="truncate text-muted-foreground">{m.email}</span>
                                                        </div>
                                                        <span
                                                            className={`shrink-0 rounded-full px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide ${
                                                                m.role === "OWNER"
                                                                    ? "bg-amber-100 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400"
                                                                    : "bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400"
                                                            }`}>
                                                            {m.role}
                                                        </span>
                                                    </div>
                                                ))}
                                            </div>
                                        )}
                                    </div>
                                )}
                            </div>
                        ))}
                    </div>
                )}

                {/* Pagination */}
                {!loading && totalPages > 1 && (
                    <div className="flex items-center justify-between text-xs text-muted-foreground">
                        <span>Page {page + 1} of {totalPages}</span>
                        <div className="flex gap-1">
                            <button
                                onClick={() => setPage((p) => Math.max(0, p - 1))}
                                disabled={page === 0}
                                className="flex items-center gap-1 rounded-lg border border-border bg-card px-3 py-1.5 transition-colors hover:bg-secondary hover:text-foreground disabled:opacity-40"
                            >
                                <ChevronLeft className="h-3.5 w-3.5"/>
                                Prev
                            </button>
                            <button
                                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                                disabled={page >= totalPages - 1}
                                className="flex items-center gap-1 rounded-lg border border-border bg-card px-3 py-1.5 transition-colors hover:bg-secondary hover:text-foreground disabled:opacity-40"
                            >
                                Next
                                <ChevronRight className="h-3.5 w-3.5"/>
                            </button>
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}
