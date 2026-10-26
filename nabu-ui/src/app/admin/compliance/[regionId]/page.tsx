"use client";

import {useCallback, useEffect, useMemo, useState} from "react";
import {useParams, useRouter} from "next/navigation";
import {AlertCircle, ArrowLeft, ChevronDown, ChevronUp, Info, Loader2, RefreshCw,} from "lucide-react";
import {BannerMonitorRuleCard} from "@/components/admin/compliance/BannerMonitorRuleCard";
import {MonitorRuleCard} from "@/components/admin/compliance/MonitorRuleCard";
import {cn} from "@/lib/utils";
import {fetchMonitorTypes, fetchRegionRules, fetchRegions} from "@/lib/complianceApi";
import type {
  ComplianceMonitorType,
  ComplianceRegion,
  MonitorRuleCardState,
  RegionRuleDetail,
} from "@/lib/complianceTypes";

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

function buildCardStates(
    monitorTypes: ComplianceMonitorType[],
    rules: RegionRuleDetail[]
): MonitorRuleCardState[] {
  const ruleMap = new Map(rules.map((r) => [r.monitorTypeId, r]));
  return monitorTypes.map((mt) => {
    const rule = ruleMap.get(mt.id);
    return {
      monitorTypeId: mt.id,
      subTypeCode: mt.subTypeCode,
      defaultName: mt.defaultName,
      description: mt.description,
      ruleId: rule?.ruleId ?? null,
      enabled: rule?.active ?? false,
      mandatory: rule?.mandatory ?? false,
      mandatoryKeywords: rule?.mandatoryKeywords ?? [],
      optionalKeywords: rule?.optionalKeywords ?? [],
    };
  });
}

// ── Page ───────────────────────────────────────────────────────────────────────

export default function RegionRuleBuilderPage() {
  const params = useParams();
  const router = useRouter();
  const regionId = params.regionId as string;

  const [region, setRegion] = useState<ComplianceRegion | null>(null);
  const [cardStates, setCardStates] = useState<MonitorRuleCardState[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showInfo, setShowInfo] = useState(false);

  const load = useCallback(async (silent = false) => {
    if (silent) setRefreshing(true);
    else setLoading(true);
    setError(null);
    try {
      const [regions, monitorTypes, rules] = await Promise.all([
        fetchRegions(),
        fetchMonitorTypes(),
        fetchRegionRules(regionId),
      ]);
      const found = regions.find((r) => r.id === regionId);
      if (!found) {
        router.push("/admin/compliance");
        return;
      }
      setRegion(found);
      setCardStates(buildCardStates(monitorTypes, rules));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load data");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [regionId, router]);

  useEffect(() => {
    load();
  }, [load]);

  function handleCardSaved(updated: MonitorRuleCardState) {
    setCardStates((prev) =>
        prev.map((s) => (s.monitorTypeId === updated.monitorTypeId ? updated : s))
    );
  }

  const enabledCount = useMemo(
      () => cardStates.filter((s) => s.enabled).length,
      [cardStates]
  );

  const enabledCards = cardStates.filter((s) => s.enabled);
  const disabledCards = cardStates.filter((s) => !s.enabled);

  // ── Render ─────────────────────────────────────────────────────────────────

  return (
      <div className="pb-10">

        {/* ── Breadcrumb ───────────────────────────────────────────────────────── */}
        <div className="flex items-center gap-2 pb-2 pt-4 text-xs text-muted-foreground">
          <button
              onClick={() => router.push("/admin/compliance")}
              className="flex items-center gap-1 transition-colors hover:text-foreground"
          >
            <ArrowLeft className="h-3.5 w-3.5"/>
            Compliance Matrix
          </button>
          <span>/</span>
          <span className="font-medium text-foreground">
          {region ? region.name : "Loading…"}
        </span>
        </div>

        {/* ── Header ───────────────────────────────────────────────────────────── */}
        {region && (
            <div className="flex items-start justify-between gap-4 pb-3">
              <div className="flex items-center gap-4">
                <span className="text-5xl leading-none">{getCountryFlag(region.code)}</span>
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <h1 className="text-lg font-bold tracking-tight text-foreground">{region.name}</h1>
                    <span
                        className="rounded bg-secondary px-2 py-0.5 text-xs font-bold uppercase tracking-widest text-muted-foreground">
                  {region.code}
                </span>
                    {region.globalDefault && (
                        <span
                            className="rounded-full bg-amber-950/60 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-amber-400 ring-1 ring-amber-900/40">
                    Global Default
                  </span>
                    )}
                    <span
                        className={cn(
                            "rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ring-1",
                            region.active
                                ? "bg-green-950/60 text-green-400 ring-green-900/40"
                                : "bg-red-950/60 text-red-400 ring-red-900/40"
                        )}
                    >
                  {region.active ? "Active" : "Inactive"}
                </span>
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {enabledCount} of {cardStates.length} document types required
                  </p>
                </div>
              </div>

              <button
                  onClick={() => load(true)}
                  disabled={refreshing}
                  className="flex shrink-0 items-center gap-1.5 rounded-xl border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40"
              >
                <RefreshCw className={cn("h-3.5 w-3.5", refreshing && "animate-spin")}/>
                Refresh
              </button>
            </div>
        )}

        <div className="space-y-4">

          {/* ── Info callout ─────────────────────────────────────────────────────── */}
          <div className="overflow-hidden rounded-xl border border-blue-500/20 bg-blue-500/8">
            <button
                type="button"
                onClick={() => setShowInfo((v) => !v)}
                className="flex w-full items-center gap-2 px-4 py-3 text-left text-sm text-blue-400 transition-colors hover:bg-blue-500/5"
            >
              <Info className="h-4 w-4 shrink-0"/>
              <span className="font-medium">How compliance rules work</span>
              {showInfo ? (
                  <ChevronUp className="ml-auto h-3.5 w-3.5"/>
              ) : (
                  <ChevronDown className="ml-auto h-3.5 w-3.5"/>
              )}
            </button>
            {showInfo && (
                <div className="space-y-1.5 px-4 pb-4 pt-1 text-xs leading-relaxed text-blue-300/80">
                  <p>
                    <strong className="text-blue-300">Toggle ON</strong> a monitor type to require it
                    for websites in this region. This immediately creates the rule in the database.
                  </p>
                  <p>
                    <strong className="text-blue-300">Required keywords</strong> are native-language
                    terms the Go scanner searches for. Finding any one means the document is PRESENT.
                  </p>
                  <p>
                    <strong className="text-blue-300">Optional keywords</strong> boost the confidence
                    score — their absence does not trigger a warning.
                  </p>
                  <p>
                    <strong className="text-blue-300">English fallbacks</strong> are managed globally
                    on the{" "}
                    <a
                        href="/admin/compliance/global-fallbacks"
                        className="underline hover:no-underline"
                    >
                      Global Fallbacks
                    </a>{" "}
                    page to avoid duplicating the same English words across 50 regions.
                  </p>
                </div>
            )}
          </div>

          {/* ── Error ────────────────────────────────────────────────────────────── */}
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

          {/* ── Loading ──────────────────────────────────────────────────────────── */}
          {loading && (
              <div className="flex items-center justify-center py-16">
                <Loader2 className="h-6 w-6 animate-spin text-muted-foreground"/>
              </div>
          )}

          {/* ── Monitor cards ─────────────────────────────────────────────────────── */}
          {!loading && !error && (
              <>
                {enabledCards.length > 0 && (
                    <section className="space-y-3">
                      <h2 className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">
                        Required for {region?.name}
                      </h2>
                      <div className="grid gap-3 sm:grid-cols-2">
                        {enabledCards.map((s) =>
                            s.subTypeCode === "COOKIE_BANNER" ? (
                                <BannerMonitorRuleCard
                                    key={s.monitorTypeId}
                                    regionId={regionId}
                                    initial={s}
                                    onSaved={handleCardSaved}
                                />
                            ) : (
                                <MonitorRuleCard
                                    key={s.monitorTypeId}
                                    regionId={regionId}
                                    initial={s}
                                    onSaved={handleCardSaved}
                                />
                            )
                        )}
                      </div>
                    </section>
                )}

                {disabledCards.length > 0 && (
                    <section className="space-y-3">
                      <h2 className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">
                        Not Required
                      </h2>
                      <div className="grid gap-3 sm:grid-cols-2">
                        {disabledCards.map((s) =>
                            s.subTypeCode === "COOKIE_BANNER" ? (
                                <BannerMonitorRuleCard
                                    key={s.monitorTypeId}
                                    regionId={regionId}
                                    initial={s}
                                    onSaved={handleCardSaved}
                                />
                            ) : (
                                <MonitorRuleCard
                                    key={s.monitorTypeId}
                                    regionId={regionId}
                                    initial={s}
                                    onSaved={handleCardSaved}
                                />
                            )
                        )}
                      </div>
                    </section>
                )}

                {cardStates.length === 0 && (
                    <div className="rounded-xl border border-border bg-card px-4 py-12 text-center">
                      <p className="text-sm text-muted-foreground">
                        No compliance document types found. Add monitor types in the database first.
                      </p>
                    </div>
                )}
              </>
          )}
        </div>
      </div>
  );
}
