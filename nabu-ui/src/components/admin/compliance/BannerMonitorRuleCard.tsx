"use client";

import {useCallback, useEffect, useState} from "react";
import {AlertCircle, CheckCircle2, Loader2, Save} from "lucide-react";
import {Toggle} from "@/components/ui/Toggle";
import {ConfirmModal} from "@/components/ui/ConfirmModal";
import {TagInput} from "@/components/admin/compliance/TagInput";
import {
    deleteRegionRule,
    fetchBannerKeywordsForRegion,
    updateBannerKeywordGroup,
    upsertRegionRule,
} from "@/lib/complianceApi";
import type {
    BannerKeywordGroup,
    BannerKeywordType,
    MonitorRuleCardState,
} from "@/lib/complianceTypes";

// ── Keyword type presentation ────────────────────────────────────────────────

const TYPE_ORDER: BannerKeywordType[] = ["BANNER_PHRASE", "ACCEPT", "REJECT", "PREF"];

const TYPE_LABEL: Record<BannerKeywordType, string> = {
    BANNER_PHRASE: "Banner phrases",
    ACCEPT: "Accept button labels",
    REJECT: "Reject / decline button labels",
    PREF: "Preferences / settings button labels",
};

const TYPE_HINT: Record<BannerKeywordType, string> = {
    BANNER_PHRASE: "Text fragments whose presence signals a cookie banner",
    ACCEPT: "Button labels meaning “accept cookies”",
    REJECT: "Button labels meaning “reject / decline cookies”",
    PREF: "Button labels that open a preferences / settings panel",
};

const TYPE_VARIANT: Record<BannerKeywordType, "primary" | "amber" | "violet"> = {
    BANNER_PHRASE: "violet",
    ACCEPT: "primary",
    REJECT: "amber",
    PREF: "primary",
};

// Empty scaffold so the editor renders all four groups even on a fresh region.
function emptyGroups(): Record<BannerKeywordType, string[]> {
    return {
        BANNER_PHRASE: [],
        ACCEPT: [],
        REJECT: [],
        PREF: [],
    };
}

type SaveStatus = "idle" | "saved" | "error";

interface Props {
    regionId: string;
    initial: MonitorRuleCardState;
    onSaved: (updated: MonitorRuleCardState) => void;
}

export function BannerMonitorRuleCard({regionId, initial, onSaved}: Props) {
    const [state, setState] = useState<MonitorRuleCardState>(initial);
    const [keywords, setKeywords] = useState<Record<BannerKeywordType, string[]>>(emptyGroups);
    const [dirtyTypes, setDirtyTypes] = useState<Set<BannerKeywordType>>(new Set());
    const [loading, setLoading] = useState(true);
    const [toggling, setToggling] = useState(false);
    const [savingType, setSavingType] = useState<BannerKeywordType | null>(null);
    const [pendingEnabled, setPendingEnabled] = useState<boolean | null>(null);
    const [saveStatus, setSaveStatus] = useState<SaveStatus>("idle");
    const [lastSavedType, setLastSavedType] = useState<BannerKeywordType | null>(null);
    const [errorMsg, setErrorMsg] = useState<string | null>(null);

    const loadKeywords = useCallback(async () => {
        setLoading(true);
        setErrorMsg(null);
        try {
            const groups: BannerKeywordGroup[] = await fetchBannerKeywordsForRegion(regionId);
            const next = emptyGroups();
            for (const g of groups) {
                next[g.keywordType] = g.keywords;
            }
            setKeywords(next);
            setDirtyTypes(new Set());
        } catch (err) {
            setErrorMsg(err instanceof Error ? err.message : "Failed to load banner keywords");
        } finally {
            setLoading(false);
        }
    }, [regionId]);

    useEffect(() => {
        loadKeywords();
    }, [loadKeywords]);

    // ── Toggle requirement (reuses compliance_region_rules like other cards) ─
    async function handleToggle(enabled: boolean) {
        setToggling(true);
        setSaveStatus("idle");
        setErrorMsg(null);
        try {
            if (enabled) {
                const result = await upsertRegionRule(regionId, state.monitorTypeId, {
                    active: true,
                    mandatory: true,
                    mandatoryKeywords: [],
                    optionalKeywords: [],
                });
                const next: MonitorRuleCardState = {
                    ...state,
                    ruleId: result.ruleId,
                    enabled: true,
                    mandatory: result.mandatory,
                };
                setState(next);
                onSaved(next);
            } else {
                await deleteRegionRule(regionId, state.monitorTypeId);
                const next: MonitorRuleCardState = {
                    ...state,
                    ruleId: null,
                    enabled: false,
                    mandatory: false,
                };
                setState(next);
                onSaved(next);
            }
        } catch (err) {
            setErrorMsg(err instanceof Error ? err.message : "Toggle failed");
            setSaveStatus("error");
        } finally {
            setToggling(false);
        }
    }

    function updateGroup(type: BannerKeywordType, tags: string[]) {
        setKeywords((prev) => ({...prev, [type]: tags}));
        setDirtyTypes((prev) => {
            const next = new Set(prev);
            next.add(type);
            return next;
        });
        setSaveStatus("idle");
    }

    async function saveGroup(type: BannerKeywordType) {
        setSavingType(type);
        setSaveStatus("idle");
        setErrorMsg(null);
        try {
            const result = await updateBannerKeywordGroup(regionId, type, keywords[type]);
            setKeywords((prev) => ({...prev, [type]: result.keywords}));
            setDirtyTypes((prev) => {
                const next = new Set(prev);
                next.delete(type);
                return next;
            });
            setSaveStatus("saved");
            setLastSavedType(type);
            setTimeout(() => setSaveStatus("idle"), 2500);
        } catch (err) {
            setErrorMsg(err instanceof Error ? err.message : "Save failed");
            setSaveStatus("error");
        } finally {
            setSavingType(null);
        }
    }

    return (
        <div className="overflow-hidden rounded-xl border border-white/5 bg-card transition-all">
            {/* Header */}
            <div className="flex items-center gap-3 px-4 py-3">
                <span className="shrink-0 text-xl leading-none">🍪</span>
                <div className="min-w-0 flex-1">
                    <span className="text-sm font-semibold text-foreground">{state.defaultName}</span>
                    {state.description && (
                        <p className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">
                            {state.description}
                        </p>
                    )}
                </div>
                <div className="flex shrink-0 items-center gap-2">
                    {state.enabled && (
                        <span className="rounded-full bg-green-950/60 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-green-400 ring-1 ring-green-900/40">
                            Required
                        </span>
                    )}
                    <Toggle
                        checked={state.enabled}
                        onChange={(next) => setPendingEnabled(next)}
                        disabled={toggling}
                        aria-label={`${state.enabled ? "Disable" : "Enable"} cookie banner monitoring`}
                    />
                </div>
            </div>

            {/* Banner keyword editor */}
            <div className="space-y-4 border-t border-white/5 px-4 py-4">
                {loading ? (
                    <div className="flex items-center justify-center py-6">
                        <Loader2 className="h-4 w-4 animate-spin text-muted-foreground"/>
                    </div>
                ) : (
                    TYPE_ORDER.map((type) => {
                        const dirty = dirtyTypes.has(type);
                        const saving = savingType === type;
                        const justSaved = saveStatus === "saved" && lastSavedType === type;
                        return (
                            <div key={type} className="space-y-1.5">
                                <p className="flex flex-wrap items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
                                    <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-violet-400"/>
                                    {TYPE_LABEL[type]}
                                    <span className="font-normal normal-case tracking-normal text-muted-foreground/60">
                                        — {TYPE_HINT[type]}
                                    </span>
                                </p>
                                <TagInput
                                    tags={keywords[type]}
                                    onChange={(tags) => updateGroup(type, tags)}
                                    placeholder="add keyword…"
                                    variant={TYPE_VARIANT[type]}
                                    disabled={saving}
                                />
                                {(dirty || justSaved) && (
                                    <div className="flex items-center justify-between gap-2 pt-1">
                                        <div className="flex items-center gap-1.5 text-xs">
                                            {justSaved && (
                                                <>
                                                    <CheckCircle2 className="h-3.5 w-3.5 text-green-400"/>
                                                    <span className="text-green-400">Saved</span>
                                                </>
                                            )}
                                            {dirty && !justSaved && (
                                                <span className="text-amber-400">Unsaved changes</span>
                                            )}
                                        </div>
                                        <button
                                            onClick={() => saveGroup(type)}
                                            disabled={saving || !dirty}
                                            className="flex items-center gap-1.5 rounded-xl bg-primary px-3 py-1.5 text-xs font-semibold text-primary-foreground hover:bg-primary/90 disabled:opacity-40"
                                        >
                                            {saving ? (
                                                <Loader2 className="h-3.5 w-3.5 animate-spin"/>
                                            ) : (
                                                <Save className="h-3.5 w-3.5"/>
                                            )}
                                            {saving ? "Saving…" : "Save"}
                                        </button>
                                    </div>
                                )}
                            </div>
                        );
                    })
                )}

                {saveStatus === "error" && errorMsg && (
                    <p className="flex items-center gap-1.5 text-xs text-red-400">
                        <AlertCircle className="h-3.5 w-3.5 shrink-0"/>
                        {errorMsg}
                    </p>
                )}
            </div>

            {pendingEnabled !== null && (
                <ConfirmModal
                    title="Change Monitor Requirement"
                    description={`Are you sure you want to make ${state.defaultName} ${pendingEnabled ? "required" : "not required"} for this region?`}
                    confirmLabel={pendingEnabled ? "Make Required" : "Remove Requirement"}
                    variant={pendingEnabled ? "primary" : "danger"}
                    onConfirm={() => {
                        const next = pendingEnabled;
                        setPendingEnabled(null);
                        handleToggle(next);
                    }}
                    onCancel={() => setPendingEnabled(null)}
                />
            )}
        </div>
    );
}
