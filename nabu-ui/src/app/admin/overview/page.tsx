"use client";

import {useCallback, useEffect, useState} from "react";
import {RefreshCw} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {AdminKpiCards} from "@/components/admin/AdminKpiCards";
import {GlobalScanFeed} from "@/components/admin/GlobalScanFeed";
import {StatusChangeFeed} from "@/components/admin/StatusChangeFeed";
import {ScanHealthReport} from "@/components/admin/ScanHealthReport";
import type {AdminStats} from "@/lib/types";


const EMPTY_STATS: AdminStats = {
  totalUsers: 0,
  totalWebsites: 0,
  totalMonitors: 0,
  activeScansLastHour: 0,
  systemFailureRate: 0,
};

export default function AdminOverviewPage() {
  const [stats, setStats] = useState<AdminStats>(EMPTY_STATS);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

  const fetchStats = useCallback(async (silent = false) => {
    if (silent) setRefreshing(true);
    else setLoading(true);

    try {
      const res = await apiClient("/api/v1/admin/stats");
      if (!res.ok) return;
      const data: AdminStats = await res.json();
      setStats(data);
      setLastUpdated(new Date());
    } catch {
      // keep stale data on network error
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    fetchStats();
    // Auto-refresh every 60s
    const interval = setInterval(() => fetchStats(true), 60_000);
    return () => clearInterval(interval);
  }, [fetchStats]);

  return (
      <div className="pb-8">
          {/* Page header */}
          <div className="pb-2 pt-4">
          <div className="flex items-center justify-between">
            <div>
                <h1 className="text-lg font-bold tracking-tight text-foreground">
                Global Overview
              </h1>
              <p className="text-xs text-muted-foreground">
                {lastUpdated
                  ? `Updated ${lastUpdated.toLocaleTimeString()}`
                  : "Loading platform metrics…"}
              </p>
            </div>
            <button
              onClick={() => fetchStats(true)}
              disabled={refreshing}
              className="flex items-center gap-1.5 rounded-xl border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground transition-colors duration-200 ease-out hover:bg-secondary hover:text-foreground disabled:opacity-40"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${refreshing ? "animate-spin" : ""}`} />
              Refresh
            </button>
          </div>
        </div>

          <div className="space-y-3 py-3">
              {/* KPI stat bar */}
        <AdminKpiCards stats={stats} loading={loading} />

              {/* Scan Health Report — full-width bulk review table */}
              <div className="h-[480px] lg:h-[520px]">
                  <ScanHealthReport/>
              </div>

        {/* Two-panel layout: scan feed + failing sites */}
              <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
                  <div className="h-[480px] lg:h-[520px]">
            <GlobalScanFeed />
          </div>
                  <div className="h-[480px] lg:h-[520px]">
                      <StatusChangeFeed/>
          </div>
        </div>
      </div>
    </div>
  );
}
