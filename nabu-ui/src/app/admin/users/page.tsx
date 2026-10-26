"use client";

import {useCallback, useEffect, useState} from "react";
import {ChevronLeft, ChevronRight, RefreshCw} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {extractErrorMessage} from "@/lib/utils";
import {UserTable} from "@/components/admin/UserTable";
import {UserDetailsDrawer} from "@/components/admin/UserDetailsDrawer";
import type {UserPageResponse, UserSummaryItem} from "@/lib/types";

export default function AdminUsersPage() {
  const [response, setResponse] = useState<UserPageResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const PAGE_SIZE = 20;

  const [selectedUser, setSelectedUser] = useState<UserSummaryItem | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const fetchUsers = useCallback(
      async (silent = false, targetPage = page) => {
        if (silent) setRefreshing(true);
        else setLoading(true);
        setError(null);

        try {
          const res = await apiClient(
              `/api/v1/admin/users?page=${targetPage}&size=${PAGE_SIZE}`
          );
          if (!res.ok) throw new Error(await extractErrorMessage(res, `HTTP ${res.status}`));
          const data: UserPageResponse = await res.json();
          setResponse(data);
        } catch (err) {
          setError(err instanceof Error ? err.message : "Failed to load users");
        } finally {
          setLoading(false);
          setRefreshing(false);
        }
      },
      [page]
  );

  useEffect(() => {
    fetchUsers(false, page);
  }, [page]); // eslint-disable-line react-hooks/exhaustive-deps

  const users = response?.content ?? [];
  const totalPages = response?.totalPages ?? 1;
  const totalElements = response?.totalElements ?? 0;

  function handleSelect(user: UserSummaryItem) {
    setSelectedUser(user);
    setDrawerOpen(true);
  }

  function handleStatusChanged(userId: string, newStatus: string) {
    setResponse((prev) => {
      if (!prev) return prev;
      return {
        ...prev,
        content: prev.content.map((u) =>
            u.id === userId ? {...u, status: newStatus as UserSummaryItem["status"]} : u
        ),
      };
    });
    setSelectedUser((prev) =>
        prev?.id === userId ? {...prev, status: newStatus as UserSummaryItem["status"]} : prev
    );
  }

  function handleDeleted(userId: string) {
    setResponse((prev) => {
      if (!prev) return prev;
      return {
        ...prev,
        content: prev.content.filter((u) => u.id !== userId),
        totalElements: prev.totalElements - 1,
      };
    });
  }

  return (
      <div className="pb-10">
      {/* Page header */}
        <div className="pb-2 pt-4">
          <div className="flex items-center justify-between">
          <div>
            <h1 className="text-lg font-bold tracking-tight text-foreground">User Directory</h1>
            <p className="text-xs text-muted-foreground">
              {loading ? "Loading…" : `${totalElements} users`}
            </p>
          </div>
          <button
              onClick={() => fetchUsers(true, page)}
              disabled={refreshing}
              className="flex items-center gap-1.5 rounded-xl border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground disabled:opacity-40"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${refreshing ? "animate-spin" : ""}`}/>
            Refresh
          </button>
        </div>
      </div>

        <div className="space-y-4 py-3">
        {error ? (
            <div className="rounded-xl border border-red-900/50 bg-red-950/30 px-4 py-6 text-center">
              <p className="text-sm font-medium text-red-400">Failed to load users</p>
              <p className="mt-1 text-xs text-red-400/70">{error}</p>
            <button
              onClick={() => fetchUsers()}
              className="mt-4 rounded-lg bg-red-950 px-4 py-2 text-xs text-red-300 transition-colors hover:bg-red-900"
            >
              Retry
            </button>
          </div>
        ) : (
            <>
              <UserTable users={users} loading={loading} onSelect={handleSelect}/>

              {/* Pagination */}
              {!loading && totalPages > 1 && (
                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>
                  Page {page + 1} of {totalPages}
                </span>
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
            </>
        )}
      </div>

      <UserDetailsDrawer
          user={selectedUser}
          open={drawerOpen}
          onClose={() => setDrawerOpen(false)}
          onStatusChanged={handleStatusChanged}
          onDeleted={handleDeleted}
      />
    </div>
  );
}
