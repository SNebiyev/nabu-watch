"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {AlertCircle, ArrowLeft, CheckCircle2, Globe2, Info, Loader2, RefreshCw, Save,} from "lucide-react";
import {Button} from "@/components/ui/button";
import {TagInput} from "@/components/admin/compliance/TagInput";
import {cn} from "@/lib/utils";
import {fetchGlobalFallbacks, updateGlobalFallback} from "@/lib/complianceApi";
import type {GlobalFallbackItem} from "@/lib/complianceTypes";

// ── Sub-type cosmetics ─────────────────────────────────────────────────────────

const SUB_TYPE_ICON: Record<string, string> = {
    PRIVACY_POLICY: "🔒",
    TERMS_OF_SERVICE: "📄",
    IMPRESSUM: "⚖️",
    COOKIE_POLICY: "🍪",
    GDPR_REQUEST: "🛡️",
};

// ── Local card state ──────────────────────────────────────────────────────────

interface CardState extends GlobalFallbackItem {
    dirty: boolean;
    saving: boolean;
    saveStatus: "idle" | "saved" | "error";
    errorMsg: string | null;
}

function toCardState(item: GlobalFallbackItem): CardState {
    return {...item, dirty: false, saving: false, saveStatus: "idle", errorMsg: null};
}

// ── Page ───────────────────────────────────────────────────────────────────────

export default function GlobalFallbacksPage() {
    const router = useRouter();

    const [cards, setCards] = useState<CardState[]>([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async (silent = false) => {
        if (silent) setRefreshing(true);
        else setLoading(true);
        setError(null);
        try {
            const items = await fetchGlobalFallbacks();
            setCards(items.map(toCardState));
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to load fallback keywords");
        } finally {
            setLoading(false);
            setRefreshing(false);
        }
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    // ── Per-card handlers ────────────────────────────────────────────────────

    function updateKeywords(monitorTypeId: string, keywords: string[]) {
        setCards((prev) =>
            prev.map((c) =>
                c.monitorTypeId === monitorTypeId
                    ? {...c, fallbackKeywords: keywords, dirty: true, saveStatus: "idle"}
                    : c
            )
        );
    }

    async function saveCard(card: CardState) {
        setCards((prev) =>
            prev.map((c) =>
                c.monitorTypeId === card.monitorTypeId
                    ? {...c, saving: true, saveStatus: "idle", errorMsg: null}
                    : c
            )
        );
        try {
            const updated = await updateGlobalFallback(card.monitorTypeId, card.fallbackKeywords);
            setCards((prev) =>
                prev.map((c) =>
                    c.monitorTypeId === card.monitorTypeId
                        ? {...c, ...updated, saving: false, dirty: false, saveStatus: "saved", errorMsg: null}
                        : c
                )
            );
            setTimeout(() => {
                setCards((prev) =>
                    prev.map((c) =>
                        c.monitorTypeId === card.monitorTypeId && c.saveStatus === "saved"
                            ? {...c, saveStatus: "idle"}
                            : c
                    )
                );
            }, 2500);
        } catch (err) {
            setCards((prev) =>
                prev.map((c) =>
                    c.monitorTypeId === card.monitorTypeId
                        ? {
                            ...c,
                            saving: false,
                            saveStatus: "error",
                            errorMsg: err instanceof Error ? err.message : "Save failed",
                        }
                        : c
                )
            );
        }
    }

    // ── Render ─────────────────────────────────────────────────────────────────

    return (
        <div className="pb-10">
            <div className="space-y-6">

                {/* ── Breadcrumb ────────────────────────────────────────────────────── */}
                <div className="flex items-center gap-2 pb-2 pt-4 text-xs text-muted-foreground">
                    <button
                        onClick={() => router.push("/admin/compliance")}
                        className="flex items-center gap-1 transition-colors hover:text-foreground"
                    >
                        <ArrowLeft className="h-3.5 w-3.5"/>
                        Compliance Matrix
                    </button>
                    <span>/</span>
                    <span className="font-medium text-foreground">Global Fallbacks</span>
                </div>

                {/* ── Header ───────────────────────────────────────────────────────── */}
                <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
                    <div>
                        <div className="flex items-center gap-2 mb-1">
                            <Globe2 className="h-5 w-5 text-amber-400"/>
                            <h1 className="text-xl font-bold text-[--foreground]">Global Fallback Keywords</h1>
                        </div>
                        <p className="text-sm text-[--muted-foreground]">
                            English-language equivalents used for language-consistency checks across all regions.
                        </p>
                    </div>
                    <Button
                        variant="outline"
                        size="sm"
                        onClick={() => load(true)}
                        disabled={refreshing}
                        className="shrink-0 gap-1.5"
                    >
                        <RefreshCw className={cn("h-3.5 w-3.5", refreshing && "animate-spin")}/>
                        Refresh
                    </Button>
                </div>

                {/* ── Explanation banner ────────────────────────────────────────────── */}
                <div className="rounded-xl border border-amber-500/25 bg-amber-500/8 p-4 text-sm">
                    <div className="flex items-start gap-3">
                        <Info className="h-4 w-4 text-amber-400 shrink-0 mt-0.5"/>
                        <div className="space-y-1.5 text-amber-300/80 leading-relaxed">
                            <p>
                                <strong className="text-amber-300">What are fallback keywords?</strong> When a
                                website operates in a non-English market (e.g. Germany, France) but only exposes
                                its legal pages in English, the scanner uses these fallback keywords to detect them.
                            </p>
                            <p>
                                If only English keywords are found (not native-language ones),{" "}
                                <code className="rounded bg-amber-500/15 px-1 py-0.5 text-xs font-mono">language_inconsistency:
                                    true</code>{" "}
                                is flagged and the monitor is assigned{" "}
                                <strong className="text-amber-300">MEDIUM</strong> severity.
                            </p>
                            <p>
                                These keywords are{" "}
                                <strong className="text-amber-300">universal</strong> — they apply to every region
                                automatically. This prevents duplicating the same English words across 50 different
                                country configurations.{" "}
                                <strong className="text-amber-300">GB and US</strong> already use English as their
                                native keywords, so fallbacks are automatically suppressed for them.
                            </p>
                        </div>
                    </div>
                </div>

                {/* ── Error ────────────────────────────────────────────────────────── */}
                {error && (
                    <div
                        className="flex items-center gap-3 rounded-xl border border-red-500/25 bg-red-500/10 p-4 text-sm text-red-400">
                        <AlertCircle className="h-4 w-4 shrink-0"/>
                        <span>{error}</span>
                        <Button variant="ghost" size="sm" onClick={() => load()} className="ml-auto text-red-400">
                            Retry
                        </Button>
                    </div>
                )}

                {/* ── Loading ──────────────────────────────────────────────────────── */}
                {loading && (
                    <div className="flex items-center justify-center py-20">
                        <Loader2 className="h-8 w-8 animate-spin text-[--muted-foreground]"/>
                    </div>
                )}

                {/* ── Fallback cards ────────────────────────────────────────────────── */}
                {!loading && !error && (
                    <div className="space-y-3">
                        {cards.map((card) => (
                            <FallbackCard
                                key={card.monitorTypeId}
                                card={card}
                                onKeywordsChange={(kws) => updateKeywords(card.monitorTypeId, kws)}
                                onSave={() => saveCard(card)}
                            />
                        ))}

                        {cards.length === 0 && (
                            <div className="rounded-2xl border border-border bg-card/60 p-12 text-center">
                                <p className="text-sm text-[--muted-foreground]">
                                    No compliance document types found.
                                </p>
                            </div>
                        )}
                    </div>
                )}
            </div>
        </div>
    );
}

// ── Fallback card ─────────────────────────────────────────────────────────────

function FallbackCard({
                          card,
                          onKeywordsChange,
                          onSave,
                      }: {
    card: CardState;
    onKeywordsChange: (keywords: string[]) => void;
    onSave: () => void;
}) {
    const icon = SUB_TYPE_ICON[card.subTypeCode] ?? "📋";

    return (
        <div className="rounded-2xl border border-border bg-card/60 p-5 shadow-[0_2px_8px_rgba(0,0,0,0.25)]">
            {/* Header */}
            <div className="flex items-start gap-3 mb-4">
                <span className="text-2xl leading-none mt-0.5">{icon}</span>
                <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap">
                        <h3 className="text-sm font-semibold text-[--foreground]">{card.defaultName}</h3>
                        <span
                            className="rounded-full bg-amber-500/15 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-amber-400 border border-amber-500/25">
              All Regions
            </span>
                    </div>
                    {card.description && (
                        <p className="mt-0.5 text-xs text-[--muted-foreground] leading-relaxed">
                            {card.description}
                        </p>
                    )}
                </div>
            </div>

            {/* Tag input */}
            <div className="space-y-1.5">
                <label className="flex items-center gap-1.5 text-xs font-semibold text-[--foreground]">
                    <span className="h-1.5 w-1.5 rounded-full bg-violet-400 shrink-0"/>
                    English Fallback Keywords
                    <span className="text-[--muted-foreground] font-normal ml-1">
            — universal; finding these triggers language_inconsistency
          </span>
                </label>
                <TagInput
                    tags={card.fallbackKeywords}
                    onChange={onKeywordsChange}
                    placeholder="e.g. Privacy Policy"
                    variant="violet"
                    disabled={card.saving}
                />
            </div>

            {/* Footer */}
            {(card.dirty || card.saveStatus !== "idle") && (
                <div className="mt-3 flex items-center justify-between gap-3">
                    <div className="flex items-center gap-1.5 text-xs">
                        {card.saveStatus === "saved" && (
                            <>
                                <CheckCircle2 className="h-3.5 w-3.5 text-green-400"/>
                                <span className="text-green-400">Saved</span>
                            </>
                        )}
                        {card.saveStatus === "error" && card.errorMsg && (
                            <>
                                <AlertCircle className="h-3.5 w-3.5 text-red-400"/>
                                <span className="text-red-400 line-clamp-1">{card.errorMsg}</span>
                            </>
                        )}
                        {card.dirty && card.saveStatus === "idle" && (
                            <span className="text-amber-400">Unsaved changes</span>
                        )}
                    </div>

                    <Button
                        size="sm"
                        onClick={onSave}
                        disabled={card.saving || !card.dirty}
                        className="gap-1.5"
                    >
                        {card.saving ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin"/>
                        ) : (
                            <Save className="h-3.5 w-3.5"/>
                        )}
                        {card.saving ? "Saving…" : "Save keywords"}
                    </Button>
                </div>
            )}
        </div>
    );
}
