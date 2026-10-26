"use client";

import {useMemo, useState} from "react";
import {ChevronDown, ChevronRight, ChevronsUpDown, ChevronUp, Search, ShieldAlert,} from "lucide-react";
import {Dropdown} from "@/components/ui/Dropdown";
import {formatRelativeTime} from "@/lib/utils";
import type {UserAccountStatus, UserSummaryItem} from "@/lib/types";

type SortKey = "fullName" | "email" | "siteCount" | "createdAt" | "status";
type SortDir = "asc" | "desc";

// ── Grid contract ─────────────────────────────────────────────────────────────
// Both the header container and every row container MUST use ROW_BASE so that
// padding, gap, and column widths are identical — preventing column drift.
// `hidden sm:grid` handles mobile/desktop visibility; remaining classes apply
// as normal (they only matter once display:grid is active at sm+).
const ROW_BASE =
    "hidden sm:grid grid-cols-[2fr_2fr_120px_120px_80px_100px] items-center gap-4 px-4";

// ── Filter options ─────────────────────────────────────────────────────────────
const ROLE_OPTIONS = [
  {id: "ALL", name: "All roles"},
  {id: "USER", name: "User"},
  {id: "ADMIN", name: "Admin"},
];

const STATUS_OPTIONS = [
  {id: "ALL", name: "All statuses"},
  {id: "ACTIVE", name: "Active"},
  {id: "PENDING_VERIFICATION", name: "Pending"},
  {id: "SUSPENDED", name: "Suspended"},
];

// ── Sub-components ─────────────────────────────────────────────────────────────

function StatusBadge({ status }: { status: UserAccountStatus }) {
  const map: Record<UserAccountStatus, { label: string; className: string }> = {
    ACTIVE: {
      label: "Active",
        className: "bg-green-100 dark:bg-green-950/60 text-green-700 dark:text-green-400 ring-1 ring-green-300/40 dark:ring-green-900/40",
    },
    PENDING_VERIFICATION: {
      label: "Pending",
        className: "bg-yellow-100 dark:bg-yellow-950/60 text-yellow-700 dark:text-yellow-400 ring-1 ring-yellow-300/40 dark:ring-yellow-900/40",
    },
    SUSPENDED: {
      label: "Suspended",
        className: "bg-orange-100 dark:bg-orange-950/60 text-orange-700 dark:text-orange-400 ring-1 ring-orange-300/40 dark:ring-orange-900/40",
    },
    DELETED: {
      label: "Deleted",
        className: "bg-slate-100 dark:bg-slate-900/60 text-slate-500 ring-1 ring-slate-300 dark:ring-slate-800",
    },
  };
  const { label, className } = map[status] ?? map.ACTIVE;
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${className}`}>
      {label}
    </span>
  );
}

function SortIcon({col, active, dir}: { col: SortKey; active: SortKey; dir: SortDir }) {
  if (col !== active) return <ChevronsUpDown className="h-3 w-3 opacity-30" />;
  return dir === "asc" ? (
      <ChevronUp className="h-3 w-3 text-blue-400"/>
  ) : (
      <ChevronDown className="h-3 w-3 text-blue-400"/>
  );
}

function Avatar({name, role}: { name: string; role: string }) {
  return (
      <div
          className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white ${
              role === "ADMIN" ? "bg-amber-600" : "bg-blue-600"
          }`}
      >
        {name.charAt(0).toUpperCase()}
      </div>
  );
}

function TableSkeleton() {
  return (
    <div className="animate-pulse">
      {Array.from({ length: 6 }).map((_, i) => (
          <div
              key={i}
              className="flex items-center gap-4 border-b border-border px-4 py-3"
          >
            <div className="h-8 w-8 rounded-full bg-secondary/60"/>
          <div className="flex-1 space-y-1.5">
            <div className="h-3 w-32 rounded bg-secondary/60"/>
            <div className="h-2.5 w-48 rounded bg-secondary/60"/>
          </div>
            <div className="h-5 w-16 rounded-full bg-secondary/60"/>
            <div className="h-5 w-8 rounded bg-secondary/60"/>
            <div className="h-3 w-16 rounded bg-secondary/60"/>
        </div>
      ))}
    </div>
  );
}

// ── Main component ─────────────────────────────────────────────────────────────

interface UserTableProps {
  users: UserSummaryItem[];
  loading?: boolean;
  onSelect?: (user: UserSummaryItem) => void;
}

export function UserTable({users, loading, onSelect}: UserTableProps) {
  const [search, setSearch] = useState("");
  const [roleFilter, setRoleFilter] = useState("ALL");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [sortKey, setSortKey] = useState<SortKey>("createdAt");
  const [sortDir, setSortDir] = useState<SortDir>("desc");

  function toggleSort(key: SortKey) {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir("asc");
    }
  }

  const filtered = useMemo(() => {
    let result = users;

    if (search.trim()) {
      const q = search.toLowerCase();
      result = result.filter(
        (u) =>
          u.email.toLowerCase().includes(q) ||
          u.fullName.toLowerCase().includes(q)
      );
    }

    if (roleFilter !== "ALL") {
      result = result.filter((u) => u.role === roleFilter);
    }

    if (statusFilter !== "ALL") {
      result = result.filter((u) => u.status === statusFilter);
    }

    return [...result].sort((a, b) => {
      let av: string | number = a[sortKey] as string | number;
      let bv: string | number = b[sortKey] as string | number;
      if (typeof av === "string") av = av.toLowerCase();
      if (typeof bv === "string") bv = bv.toLowerCase();
      if (av < bv) return sortDir === "asc" ? -1 : 1;
      if (av > bv) return sortDir === "asc" ? 1 : -1;
      return 0;
    });
  }, [users, search, roleFilter, statusFilter, sortKey, sortDir]);

  const adminCount = users.filter((u) => u.role === "ADMIN").length;
  const activeCount = users.filter((u) => u.status === "ACTIVE").length;

  // Header cell — matches Dashboard's text-[10px] uppercase tracking-wider style
  const thClass =
      "text-left text-[10px] font-medium uppercase tracking-wider text-muted-foreground whitespace-nowrap select-none cursor-pointer hover:text-foreground transition-colors";

  return (
    <div className="flex flex-col gap-4">
      {/* ── Filter bar — mirrors Dashboard layout ── */}
      <div className="flex flex-col gap-2 md:flex-row md:items-center">
        {/* Search input */}
        <div className="relative flex-1">
          <Search
              className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-500"/>
          <input
              type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
              placeholder="Search by name or email…"
              className="h-8 w-full rounded-lg border border-border bg-secondary/50 pl-8 pr-3 text-xs text-foreground placeholder:text-muted-foreground transition-colors hover:border-primary/30 focus:outline-none focus:ring-1 focus:ring-primary/40"
          />
        </div>

        {/* Role dropdown */}
        <Dropdown
            options={ROLE_OPTIONS}
            value={roleFilter}
            onChange={(v) => setRoleFilter(String(v))}
            placeholder="All roles"
            className="w-full md:w-[130px] md:shrink-0"
        />

        {/* Status dropdown */}
        <Dropdown
            options={STATUS_OPTIONS}
            value={statusFilter}
            onChange={(v) => setStatusFilter(String(v))}
            placeholder="All statuses"
            className="w-full md:w-[140px] md:shrink-0"
        />
      </div>

      {/* ── Summary row ── */}
      <div className="flex gap-4 text-xs text-slate-500">
        <span>
          <strong className="text-foreground">{filtered.length}</strong> /{" "}
          {users.length} users
        </span>
        <span>·</span>
        <span>
          <strong className="text-amber-400">{adminCount}</strong>{" "}
          admin{adminCount !== 1 ? "s" : ""}
        </span>
        <span>·</span>
        <span>
          <strong className="text-green-400">{activeCount}</strong> active
        </span>
      </div>

      {/* ── Table ── */}
      <div className="glass-card overflow-hidden">
        {loading ? (
          <TableSkeleton />
        ) : (
          <>
            {/* Desktop header — hidden on mobile */}
            <div
                className={`${ROW_BASE} border-b border-border bg-secondary/20 py-1.5`}
            >
              <button className={thClass} onClick={() => toggleSort("fullName")}>
                <span className="flex items-center gap-1">
                  User <SortIcon col="fullName" active={sortKey} dir={sortDir} />
                </span>
              </button>
              <button className={thClass} onClick={() => toggleSort("email")}>
                <span className="flex items-center gap-1">
                  Email <SortIcon col="email" active={sortKey} dir={sortDir} />
                </span>
              </button>
              <span className={`${thClass} cursor-default`}>Role</span>
              <button className={thClass} onClick={() => toggleSort("status")}>
                <span className="flex items-center gap-1">
                  Status <SortIcon col="status" active={sortKey} dir={sortDir} />
                </span>
              </button>
              <button className={thClass} onClick={() => toggleSort("siteCount")}>
                <span className="flex items-center gap-1">
                  Sites <SortIcon col="siteCount" active={sortKey} dir={sortDir} />
                </span>
              </button>
              <button className={thClass} onClick={() => toggleSort("createdAt")}>
                <span className="flex items-center gap-1">
                  Joined <SortIcon col="createdAt" active={sortKey} dir={sortDir} />
                </span>
              </button>
            </div>

            {filtered.length === 0 ? (
              <div className="flex flex-col items-center justify-center gap-2 py-12 text-center">
                <Search className="h-6 w-6 text-muted-foreground"/>
                <p className="text-sm text-muted-foreground">No users match your filters</p>
              </div>
            ) : (
              <ul>
                {filtered.map((user) => (
                  <li
                    key={user.id}
                    onClick={() => onSelect?.(user)}
                    className={`border-b border-border last:border-0 transition-colors duration-200 ease-out ${
                        onSelect ? "cursor-pointer hover:bg-secondary/50 active:bg-secondary/80" : ""
                    }`}
                  >
                    {/* ── Desktop row (sm+) ── */}
                    <div className={`${ROW_BASE} py-3`}>
                      {/* User */}
                      <div className="flex min-w-0 items-center gap-3">
                        <Avatar name={user.fullName} role={user.role}/>
                          <p className="min-w-0 truncate text-sm font-medium text-foreground">
                          {user.fullName}
                        </p>
                      </div>

                      {/* Email */}
                        <p className="min-w-0 truncate text-sm text-muted-foreground">{user.email}</p>

                      {/* Role */}
                      <div className="min-w-0">
                        {user.role === "ADMIN" ? (
                            <span
                                className="flex w-fit items-center gap-1 rounded-full bg-amber-100 dark:bg-amber-950/60 px-2 py-0.5 text-xs font-semibold text-amber-700 dark:text-amber-400 ring-1 ring-amber-300/40 dark:ring-amber-900/40">
                            <ShieldAlert className="h-3 w-3" />
                            Admin
                          </span>
                        ) : (
                            <span
                                className="rounded-full bg-slate-100 dark:bg-slate-800/60 px-2 py-0.5 text-xs text-muted-foreground ring-1 ring-slate-200 dark:ring-slate-700/40">
                            User
                          </span>
                        )}
                      </div>

                      {/* Status */}
                      <div className="min-w-0">
                        <StatusBadge status={user.status}/>
                      </div>

                      {/* Sites */}
                        <p className="text-sm font-medium tabular-nums text-foreground">
                        {user.siteCount}
                      </p>

                      {/* Joined */}
                      <p className="min-w-0 truncate text-xs tabular-nums text-slate-500">
                        {formatRelativeTime(user.createdAt)}
                      </p>
                    </div>

                    {/* ── Mobile card (< sm) ── */}
                    <div className="block px-4 py-3 sm:hidden">
                      <div className="flex items-start gap-2.5">
                        <Avatar name={user.fullName} role={user.role}/>

                        <div className="min-w-0 flex-1 space-y-1">
                          {/* Row 1: Name + Status badge */}
                          <div className="flex items-center gap-2">
                              <p className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">
                              {user.fullName}
                            </p>
                            <StatusBadge status={user.status}/>
                            {onSelect && (
                                <ChevronRight className="h-4 w-4 shrink-0 text-slate-600"/>
                            )}
                          </div>

                          {/* Row 2: Email */}
                            <p className="truncate text-xs text-muted-foreground">{user.email}</p>

                          {/* Row 3: Joined */}
                          <div className="flex items-center gap-2 pt-0.5">
                            <span className="text-xs text-slate-500">
                              {formatRelativeTime(user.createdAt)}
                            </span>
                          </div>
                        </div>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </div>
  );
}
