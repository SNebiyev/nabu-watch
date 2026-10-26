"use client";

import {Suspense, useCallback, useEffect, useRef, useState} from "react";
import {useRouter, useSearchParams} from "next/navigation";
import {Building2, ChevronLeft, ChevronRight, RefreshCw} from "lucide-react";
import {getToken} from "@/lib/authService";
import {apiClient} from "@/lib/apiClient";
import {useAuth} from "@/lib/authContext";
import {StatCards} from "@/components/dashboard/StatCards";
import {WebsiteList} from "@/components/dashboard/WebsiteList";
import {AddWebsiteFAB} from "@/components/dashboard/AddWebsiteFAB";
import {
    type FilterState,
    type SortDirection,
    type SortKey,
    WebsiteFilterBar
} from "@/components/dashboard/WebsiteFilterBar";
import type {DashboardStats, WebsiteHealth, WebsitePageResponse} from "@/lib/types";
import {websiteCache} from "@/lib/websiteCache";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

const EMPTY_STATS: DashboardStats = {
  totalSites: 0,
  failingSites: 0,
  passingSites: 0,
  pendingSites: 0,
  sslIssues: 0,
  complianceIssues: 0,
    recoveredCount: 0,
    degradedCount: 0,
};

const DEFAULT_PAGE_SIZE = 20;
const PAGE_SIZE_OPTIONS = [10, 20, 50];

const DEFAULT_SORT_KEY: SortKey = "lastCheckedAt";
const DEFAULT_SORT_DIRECTION: SortDirection = "desc";
const VALID_SORT_KEYS: SortKey[] = ["lastCheckedAt", "nextScanAt", "region", "grade", "status"];

function parseSortKey(v: string | null): SortKey {
    return (VALID_SORT_KEYS as string[]).includes(v ?? "") ? (v as SortKey) : DEFAULT_SORT_KEY;
}

function parseSortDirection(v: string | null): SortDirection {
    return v === "asc" || v === "desc" ? v : DEFAULT_SORT_DIRECTION;
}

// ── inner component (needs useSearchParams → must be inside <Suspense>) ──────

function DashboardInner() {
    const searchParams = useSearchParams();
    const router = useRouter();
    const {
        currentOrganizationId,
        currentOrganizationName,
        currentOrganizationRole,
        isAdmin
    } = useAuth();

    // ── filter / pagination state ─────────────────────────────────────────────
    const [filters, setFilters] = useState<FilterState>(() => ({
        search: searchParams.get("q") ?? "",
        status: searchParams.get("status") ?? "",
        monitorSubType: searchParams.get("monitorSubType") ?? "",
        monitorStatus: searchParams.get("monitorStatus") ?? "",
        languageOnly: searchParams.get("langOnly") === "1",
    }));
    const [page, setPage] = useState(() => Number(searchParams.get("page") ?? "0"));
    const [pageSize, setPageSize] = useState(() => Number(searchParams.get("size") ?? DEFAULT_PAGE_SIZE));
    const [sortKey, setSortKey] = useState<SortKey>(() => parseSortKey(searchParams.get("sort")));
    const [sortDirection, setSortDirection] = useState<SortDirection>(() => parseSortDirection(searchParams.get("dir")));

    // ── response state ────────────────────────────────────────────────────────
    const [websites, setWebsites] = useState<WebsiteHealth[]>([]);
    const [stats, setStats] = useState<DashboardStats>(EMPTY_STATS);
    const [totalPages, setTotalPages] = useState(0);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [expandedWebsiteId, setExpandedWebsiteId] = useState<string | null>(null);

    // ── URL sync ──────────────────────────────────────────────────────────────
    const syncUrl = useCallback((f: FilterState, p: number, s: number, sk: SortKey, sd: SortDirection) => {
        const params = new URLSearchParams();
        if (f.search) params.set("q", f.search);
        if (f.status) params.set("status", f.status);
        if (f.monitorSubType) params.set("monitorSubType", f.monitorSubType);
        if (f.monitorStatus) params.set("monitorStatus", f.monitorStatus);
        if (f.languageOnly) params.set("langOnly", "1");
        if (p > 0) params.set("page", String(p));
        if (s !== DEFAULT_PAGE_SIZE) params.set("size", String(s));
        if (sk !== DEFAULT_SORT_KEY) params.set("sort", sk);
        if (sd !== DEFAULT_SORT_DIRECTION) params.set("dir", sd);
        const qs = params.toString();
        router.replace(qs ? `?${qs}` : "/dashboard", {scroll: false});
    }, [router]);

    // ── fetch ─────────────────────────────────────────────────────────────────
    const fetchWebsites = useCallback(async (
        f: FilterState, p: number, s: number, sk: SortKey, sd: SortDirection, silent = false,
    ) => {
    if (!silent) setLoading(true);
    else setRefreshing(true);

        const params = new URLSearchParams({page: String(p), size: String(s), sort: sk, direction: sd});
        if (f.search) params.set("q", f.search);
        if (f.status) params.set("status", f.status);
        if (f.monitorSubType) params.set("monitorSubType", f.monitorSubType);
        if (f.monitorStatus) params.set("monitorStatus", f.monitorStatus);

    try {
        const res = await apiClient(`/api/v1/websites?${params}`);
      if (!res.ok) return;
        const data: WebsitePageResponse = await res.json();
        setWebsites(data.content ?? []);
        setStats(data.stats ?? EMPTY_STATS);
        setTotalPages(data.totalPages ?? 1);
    } catch {
      // Network error — keep existing data
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

    // Initial load + re-fetch when org switches
  useEffect(() => {
      fetchWebsites(filters, page, pageSize, sortKey, sortDirection);
      // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentOrganizationId]);

    // ── Deep-link: notification click → open drawer ───────────────────────────
    useEffect(() => {
        const websiteId = searchParams.get("websiteId");
        const action = searchParams.get("action");
        if (!websiteId || action !== "openDrawer") return;

        // Reset filters/pagination so the target website is visible regardless of current page
        const resetFilters: FilterState = {search: "", status: "", monitorSubType: "", monitorStatus: "", languageOnly: false};
        setFilters(resetFilters);
        setPage(0);
        syncUrl(resetFilters, 0, pageSize, sortKey, sortDirection);
        // Wait for data to load so the row exists in DOM before expanding
        fetchWebsites(resetFilters, 0, pageSize, sortKey, sortDirection).then(() => {
            setExpandedWebsiteId(websiteId);
        });
        router.replace("/dashboard", {scroll: false});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [searchParams]);

    // ── SSE: live monitor updates ─────────────────────────────────────────────
    const liveRefetchState = useRef({filters, page, pageSize, sortKey, sortDirection});
    liveRefetchState.current = {filters, page, pageSize, sortKey, sortDirection};

    useEffect(() => {
        const token = getToken();
        if (!token) return;

        let debounceTimer: ReturnType<typeof setTimeout> | null = null;

        const scheduleSilentRefetch = (delayMs: number) => {
            if (debounceTimer) clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => {
                debounceTimer = null;
                const s = liveRefetchState.current;
                fetchWebsites(s.filters, s.page, s.pageSize, s.sortKey, s.sortDirection, true);
            }, delayMs);
        };

        const es = new EventSource(
            `${API_URL}/api/v1/scans/stream?token=${encodeURIComponent(token)}`
        );

        es.addEventListener("scan-result", () => {
            websiteCache.clear();
            scheduleSilentRefetch(800);
        });
        es.addEventListener("website-updated", () => {
            websiteCache.clear();
            scheduleSilentRefetch(200);
        });

        return () => {
            es.close();
            if (debounceTimer) clearTimeout(debounceTimer);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [currentOrganizationId]);

    // ── event handlers ────────────────────────────────────────────────────────
    function handleFilterChange(partial: Partial<FilterState>) {
        const next = {...filters, ...partial};
        setFilters(next);
        setPage(0);
        setExpandedWebsiteId(null);
        syncUrl(next, 0, pageSize, sortKey, sortDirection);
        fetchWebsites(next, 0, pageSize, sortKey, sortDirection);
    }

    function handlePageChange(next: number) {
        setPage(next);
        setExpandedWebsiteId(null);
        syncUrl(filters, next, pageSize, sortKey, sortDirection);
        fetchWebsites(filters, next, pageSize, sortKey, sortDirection);
        window.scrollTo({top: 0, behavior: "smooth"});
    }

    function handlePageSizeChange(next: number) {
        setPageSize(next);
        setPage(0);
        setExpandedWebsiteId(null);
        syncUrl(filters, 0, next, sortKey, sortDirection);
        fetchWebsites(filters, 0, next, sortKey, sortDirection);
    }

    function handleSortChange(nextKey: SortKey, nextDirection: SortDirection) {
        setSortKey(nextKey);
        setSortDirection(nextDirection);
        setPage(0);
        setExpandedWebsiteId(null);
        syncUrl(filters, 0, pageSize, nextKey, nextDirection);
        fetchWebsites(filters, 0, pageSize, nextKey, nextDirection);
    }

    // ── Toggle expand with collapse-first animation when switching sites ─────
    const handleToggleExpand = useCallback((site: WebsiteHealth) => {
        setExpandedWebsiteId(prev => {
            if (prev === site.id) return null; // toggle off
            if (prev !== null) {
                // Switching: collapse current first, then expand new after animation completes
                setTimeout(() => setExpandedWebsiteId(site.id), 150);
                return null;
            }
            return site.id; // expand fresh
        });
    }, []);

  return (
      <div className="pb-8">
          <div>
        {/* Page header */}
          <div className="flex items-center justify-between pb-4">
              <div className="flex items-center gap-3">
                  <button
                      onClick={() => fetchWebsites(filters, page, pageSize, sortKey, sortDirection, true)}
                      disabled={refreshing}
                      className="flex items-center gap-1.5 rounded-xl border border-border bg-card px-3.5 py-2 text-xs font-medium text-muted-foreground transition-all duration-200 hover:bg-secondary hover:text-foreground disabled:opacity-40"
                  >
                      <RefreshCw className={`h-3.5 w-3.5 ${refreshing ? "animate-spin" : ""}`}/>
                      Refresh
                  </button>
                  <AddWebsiteFAB
                      onAdded={() => fetchWebsites(filters, 0, pageSize, sortKey, sortDirection)}
                  />
              </div>
          </div>

          {/* Organization member banner */}
          {currentOrganizationRole === "MEMBER" && !isAdmin && currentOrganizationName && (
              <div
                  className="mb-3 flex items-center gap-2.5 rounded-xl border border-purple-200/60 bg-purple-50/80 px-4 py-2.5 dark:border-purple-800/40 dark:bg-purple-950/30">
                  <Building2 className="h-4 w-4 shrink-0 text-purple-500 dark:text-purple-400"/>
                  <span className="text-xs font-semibold text-purple-700 dark:text-purple-300">
                      {currentOrganizationName}
                  </span>
                  <span
                      className="rounded-full bg-purple-100 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide text-purple-500 dark:bg-purple-900/60 dark:text-purple-400">
                      Member
                  </span>
              </div>
          )}

          {/* Stats */}
          <StatCards stats={stats}/>

        {/* Main content */}
          <div className="mt-4 space-y-3">
              {/* Filter bar */}
              <WebsiteFilterBar
                  filters={filters}
                  onChange={handleFilterChange}
                  sortKey={sortKey}
                  sortDirection={sortDirection}
                  onSortChange={handleSortChange}
              />

              <WebsiteList
                  websites={filters.languageOnly
                      ? websites.filter((s) => s.monitors.some((m) => m.active && m.languageInconsistency))
                      : websites}
                  loading={loading}
                  expandedWebsiteId={expandedWebsiteId}
                  onToggleExpand={handleToggleExpand}
                  onMonitorsUpdated={() => fetchWebsites(filters, page, pageSize, sortKey, sortDirection, true)}
              />

              {/* Pagination */}
              {(totalPages > 1 || pageSize !== DEFAULT_PAGE_SIZE) && (
                  <div
                      className="flex items-center justify-between rounded-xl border border-border bg-card px-4 py-3">
                      <div className="flex items-center gap-2 text-xs text-muted-foreground">
                          <span>Rows</span>
                          <select
                              value={pageSize}
                              onChange={(e) => handlePageSizeChange(Number(e.target.value))}
                              className="rounded-lg border border-border bg-secondary px-2.5 py-1 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-primary/30"
                          >
                              {PAGE_SIZE_OPTIONS.map((s) => (
                                  <option key={s} value={s}>{s}</option>
                              ))}
                          </select>
                      </div>
                      <div className="flex items-center gap-2">
                          <span className="text-xs text-muted-foreground">
                              Page {page + 1} of {Math.max(totalPages, 1)}
                          </span>
                          <button
                              onClick={() => handlePageChange(page - 1)}
                              disabled={page === 0}
                              className="flex h-8 w-8 items-center justify-center rounded-xl border border-border bg-card text-muted-foreground transition-all duration-200 hover:bg-secondary hover:text-foreground disabled:cursor-not-allowed disabled:opacity-30"
                          >
                              <ChevronLeft className="h-4 w-4"/>
                          </button>
                          <button
                              onClick={() => handlePageChange(page + 1)}
                              disabled={page >= totalPages - 1}
                              className="flex h-8 w-8 items-center justify-center rounded-xl border border-border bg-card text-muted-foreground transition-all duration-200 hover:bg-secondary hover:text-foreground disabled:cursor-not-allowed disabled:opacity-30"
                          >
                              <ChevronRight className="h-4 w-4"/>
                          </button>
                      </div>
                  </div>
              )}
          </div>
      </div>

    </div>
  );
}

// ── page export ─────────────────────────────────────────────────────────────

export default function DashboardPage() {
    return (
        <Suspense>
            <DashboardInner/>
        </Suspense>
    );
}
