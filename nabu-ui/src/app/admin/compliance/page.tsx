"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {AlertCircle, ArrowRight, Globe2, Loader2, Plus, RefreshCw, Shield, X,} from "lucide-react";
import {Toggle} from "@/components/ui/Toggle";
import {ConfirmModal} from "@/components/ui/ConfirmModal";
import {cn} from "@/lib/utils";
import {createRegion, fetchRegions, patchRegionActive} from "@/lib/complianceApi";
import type {ComplianceRegion} from "@/lib/complianceTypes";

// ── Helpers ────────────────────────────────────────────────────────────────────

function getCountryFlag(code: string): string {
  if (code === "GLOBAL") return "🌐";
  if (code.length !== 2) return "🌐";
  const offset = 127397;
  return String.fromCodePoint(
      code.toUpperCase().charCodeAt(0) + offset,
      code.toUpperCase().charCodeAt(1) + offset
  );
}

// ── Add Region Modal ───────────────────────────────────────────────────────────

function AddRegionModal({
                          onSave,
                          onClose,
                        }: {
  onSave: (code: string, name: string) => Promise<void>;
  onClose: () => void;
}) {
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await onSave(code.toUpperCase().trim(), name.trim());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create region");
    } finally {
      setSaving(false);
    }
  }

  const inputCls =
      "w-full rounded-lg border border-border bg-secondary/40 px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20";

  return (
      <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4"
          onClick={(e) => e.target === e.currentTarget && onClose()}
      >
        <div className="w-full max-w-md overflow-hidden rounded-2xl border border-border bg-card shadow-2xl">
          <div className="flex items-center justify-between border-b border-border px-5 py-4">
            <h2 className="text-sm font-semibold text-foreground">Add New Region</h2>
            <button
                onClick={onClose}
                className="rounded-lg p-1 text-muted-foreground hover:bg-secondary"
            >
              <X className="h-4 w-4"/>
            </button>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4 p-5">
            <div>
              <label className="mb-1 block text-xs font-medium text-muted-foreground">
                Region Code *
              </label>
              <input
                  required
                  maxLength={10}
                  value={code}
                  onChange={(e) => setCode(e.target.value.toUpperCase())}
                  placeholder="DE"
                  className={inputCls}
              />
              <p className="mt-1 text-[10px] text-muted-foreground">
                ISO 3166-1 alpha-2 code (e.g. DE, FR, GB) or a custom identifier
              </p>
            </div>

            <div>
              <label className="mb-1 block text-xs font-medium text-muted-foreground">
                Region Name *
              </label>
              <input
                  required
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Germany"
                  className={inputCls}
              />
            </div>

            {error && (
                <p className="rounded-lg bg-red-950/40 px-3 py-2 text-xs text-red-400">{error}</p>
            )}

            <div className="flex justify-end gap-2 border-t border-border pt-4">
              <button
                  type="button"
                  onClick={onClose}
                  className="rounded-xl px-4 py-2 text-xs font-medium text-muted-foreground hover:bg-secondary"
              >
                Cancel
              </button>
              <button
                  type="submit"
                  disabled={saving}
                  className="flex items-center gap-1.5 rounded-xl bg-primary px-4 py-2 text-xs font-semibold text-primary-foreground hover:bg-primary/90 disabled:opacity-40"
              >
                {saving && <Loader2 className="h-3.5 w-3.5 animate-spin"/>}
                Create Region
              </button>
            </div>
          </form>
        </div>
      </div>
  );
}

// ── Region table row ───────────────────────────────────────────────────────────

function RegionRow({
                     region,
                     isToggling,
                     onToggle,
                     onNavigate,
                   }: {
  region: ComplianceRegion;
  isToggling: boolean;
  onToggle: () => void;
  onNavigate: () => void;
}) {
  const flag = getCountryFlag(region.code);

  return (
      <tr
          onClick={onNavigate}
          className="cursor-pointer border-b border-border transition-colors hover:bg-secondary/20 last:border-0"
      >
        {/* Flag + Name */}
        <td className="px-4 py-3">
          <div className="flex items-center gap-3">
            <span className="text-2xl leading-none">{flag}</span>
            <span className="font-medium text-foreground">{region.name}</span>
          </div>
        </td>

        {/* Code */}
        <td className="px-3 py-3">
        <span
            className="rounded bg-secondary px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-widest text-muted-foreground">
          {region.code}
        </span>
        </td>

        {/* Status toggle — td captures all clicks to stop row navigation */}
        <td
            className="px-3 py-3"
            onClick={(e) => e.stopPropagation()}
        >
          {region.globalDefault ? (
              <span className="text-xs text-amber-400/80">Always on</span>
          ) : (
              <Toggle
                  checked={region.active}
                  onChange={onToggle}
                  disabled={isToggling}
                  size="sm"
                  aria-label={`${region.active ? "Disable" : "Enable"} ${region.name}`}
              />
          )}
        </td>

        {/* Type badge */}
        <td className="px-3 py-3">
          {region.globalDefault ? (
              <span
                  className="inline-flex items-center gap-1 rounded-full bg-amber-950/60 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-amber-400 ring-1 ring-amber-900/40">
            <Globe2 className="h-2.5 w-2.5"/>
            Global Default
          </span>
          ) : region.active ? (
              <span
                  className="rounded-full bg-green-950/60 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-green-400 ring-1 ring-green-900/40">
            Active
          </span>
          ) : (
              <span
                  className="rounded-full bg-secondary px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground ring-1 ring-border">
            Inactive
          </span>
          )}
        </td>

        {/* Arrow */}
        <td className="w-10 px-3 py-3">
          <ArrowRight className="h-4 w-4 text-muted-foreground"/>
        </td>
      </tr>
  );
}

// ── Page ───────────────────────────────────────────────────────────────────────

export default function CompliancePage() {
  const router = useRouter();
  const [regions, setRegions] = useState<ComplianceRegion[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [toggling, setToggling] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [pendingToggle, setPendingToggle] = useState<ComplianceRegion | null>(null);

  const load = useCallback(async (silent = false) => {
    if (silent) setRefreshing(true);
    else setLoading(true);
    setError(null);
    try {
      const data = await fetchRegions();
      data.sort((a, b) =>
          a.globalDefault ? -1 : b.globalDefault ? 1 : a.name.localeCompare(b.name)
      );
      setRegions(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load regions");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function handleActiveToggle(region: ComplianceRegion) {
    if (region.globalDefault) return;
    const prevActive = region.active;
    // Optimistic update — new array reference forces React to reconcile the row
    setRegions((current) =>
        [...current.map((r) => (r.id === region.id ? {...r, active: !prevActive} : r))]
    );
    setToggling(region.id);
    try {
      const updated = await patchRegionActive(region.id, !prevActive);
      setRegions((prev) => prev.map((r) => (r.id === updated.id ? updated : r)));
    } catch (err) {
      // Revert the optimistic update on failure
      setRegions((prev) =>
          prev.map((r) => (r.id === region.id ? {...r, active: prevActive} : r))
      );
      setError(err instanceof Error ? err.message : "Failed to update region status");
    } finally {
      setToggling(null);
    }
  }

  async function handleCreate(code: string, name: string) {
    const created = await createRegion(code, name);
    setAddOpen(false);
    setRegions((prev) => {
      const next = [...prev, created];
      next.sort((a, b) =>
          a.globalDefault ? -1 : b.globalDefault ? 1 : a.name.localeCompare(b.name)
      );
      return next;
    });
  }

  const activeCount = regions.filter((r) => r.active).length;

  // ── Render ─────────────────────────────────────────────────────────────────

  return (
      <div className="pb-10">

        {/* ── Header ─────────────────────────────────────────────────────────── */}
        <div className="pb-2 pt-4">
          <div className="flex items-center justify-between">
            <div>
              <div className="flex items-center gap-2">
                <Shield className="h-4 w-4 text-[--primary]"/>
                <h1 className="text-lg font-bold tracking-tight text-foreground">
                  Compliance Matrix Builder
                </h1>
              </div>
              <p className="mt-0.5 text-xs text-muted-foreground">
                {loading
                    ? "Loading…"
                    : `${regions.length} regions · ${activeCount} active`}
              </p>
            </div>

            <div className="flex items-center gap-2">
              <button
                  onClick={() => router.push("/admin/compliance/global-fallbacks")}
                  className="flex items-center gap-1.5 rounded-xl border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground"
              >
                <Globe2 className="h-3.5 w-3.5"/>
                Global Fallbacks
              </button>
              <button
                  onClick={() => load(true)}
                  disabled={refreshing}
                  className="flex items-center gap-1.5 rounded-xl border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40"
              >
                <RefreshCw className={cn("h-3.5 w-3.5", refreshing && "animate-spin")}/>
                Refresh
              </button>
              <button
                  onClick={() => setAddOpen(true)}
                  className="flex items-center gap-1.5 rounded-xl bg-primary px-3 py-1.5 text-xs font-semibold text-primary-foreground hover:bg-primary/90"
              >
                <Plus className="h-3.5 w-3.5"/>
                Add Region
              </button>
            </div>
          </div>
        </div>

        <div className="space-y-4 py-3">

          {/* ── Error ────────────────────────────────────────────────────────── */}
          {error && (
              <div
                  className="flex items-center gap-3 rounded-xl border border-red-900/50 bg-red-950/30 px-4 py-3 text-sm text-red-400">
                <AlertCircle className="h-4 w-4 shrink-0"/>
                <span>{error}</span>
                <button
                    onClick={() => load()}
                    className="ml-auto text-xs text-red-400 hover:text-red-300"
                >
                  Retry
                </button>
              </div>
          )}

          {/* ── Loading ──────────────────────────────────────────────────────── */}
          {loading && (
              <div className="flex items-center justify-center py-16">
                <Loader2 className="h-6 w-6 animate-spin text-muted-foreground"/>
              </div>
          )}

          {/* ── Table ────────────────────────────────────────────────────────── */}
          {!loading && !error && (
              <div className="overflow-hidden rounded-xl border border-border bg-card">
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                    <tr className="border-b border-border bg-secondary/30 text-left text-xs text-muted-foreground">
                      <th className="px-4 py-2.5 font-normal">Region</th>
                      <th className="px-3 py-2.5 font-normal">Code</th>
                      <th className="px-3 py-2.5 font-normal">Status</th>
                      <th className="px-3 py-2.5 font-normal">Type</th>
                      <th className="w-10 px-3 py-2.5"/>
                    </tr>
                    </thead>
                    <tbody>
                    {regions.map((region) => (
                        <RegionRow
                            key={`${region.id}-${region.active}`}
                            region={region}
                            isToggling={toggling === region.id}
                            onToggle={() => setPendingToggle(region)}
                            onNavigate={() => router.push(`/admin/compliance/${region.id}`)}
                        />
                    ))}
                    {regions.length === 0 && (
                        <tr>
                          <td
                              colSpan={5}
                              className="px-4 py-10 text-center text-sm text-muted-foreground"
                          >
                            No regions configured. Click &ldquo;Add Region&rdquo; to get started.
                          </td>
                        </tr>
                    )}
                    </tbody>
                  </table>
                </div>
              </div>
          )}
        </div>

        {/* ── Toggle Confirm Modal ─────────────────────────────────────────────── */}
        {pendingToggle && (
            <ConfirmModal
                title="Change Region Status"
                description={`Are you sure you want to ${pendingToggle.active ? "disable" : "enable"} compliance scanning for ${pendingToggle.name}?`}
                confirmLabel={pendingToggle.active ? "Disable" : "Enable"}
                variant={pendingToggle.active ? "danger" : "primary"}
                onConfirm={() => {
                  const region = pendingToggle;
                  setPendingToggle(null);
                  handleActiveToggle(region);
                }}
                onCancel={() => setPendingToggle(null)}
            />
        )}

        {/* ── Add Region Modal ─────────────────────────────────────────────────── */}
        {addOpen && (
            <AddRegionModal
                onSave={handleCreate}
                onClose={() => setAddOpen(false)}
            />
        )}
      </div>
  );
}
