"use client";

import {useState} from "react";
import {AlertCircle, CheckCircle2, Loader2, Save} from "lucide-react";
import {Toggle} from "@/components/ui/Toggle";
import {ConfirmModal} from "@/components/ui/ConfirmModal";
import {TagInput} from "@/components/admin/compliance/TagInput";
import {deleteRegionRule, upsertRegionRule} from "@/lib/complianceApi";
import type {MonitorRuleCardState} from "@/lib/complianceTypes";

const SUB_TYPE_ICON: Record<string, string> = {
  PRIVACY_POLICY: "🔒",
  TERMS_OF_SERVICE: "📄",
  IMPRESSUM: "⚖️",
  COOKIE_POLICY: "🍪",
  GDPR_REQUEST: "🛡️",
};

interface Props {
  regionId: string;
  initial: MonitorRuleCardState;
  onSaved: (updated: MonitorRuleCardState) => void;
}

export function MonitorRuleCard({regionId, initial, onSaved}: Props) {
  const [state, setState] = useState<MonitorRuleCardState>(initial);
  const [saving, setSaving] = useState(false);
  const [toggling, setToggling] = useState(false);
  const [pendingEnabled, setPendingEnabled] = useState<boolean | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saveStatus, setSaveStatus] = useState<"idle" | "saved" | "error">("idle");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // ── Toggle on/off ──────────────────────────────────────────────────────────
  async function handleToggle(enabled: boolean) {
    setToggling(true);
    setSaveStatus("idle");
    setErrorMsg(null);
    try {
      if (enabled) {
        const result = await upsertRegionRule(regionId, state.monitorTypeId, {
          active: true,
          mandatory: true,
          mandatoryKeywords: state.mandatoryKeywords,
          optionalKeywords: state.optionalKeywords,
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
        setDirty(false);
        onSaved(next);
      }
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Toggle failed");
      setSaveStatus("error");
    } finally {
      setToggling(false);
    }
  }

  // ── Save keywords ──────────────────────────────────────────────────────────
  async function handleSave() {
    setSaving(true);
    setSaveStatus("idle");
    setErrorMsg(null);
    try {
      const result = await upsertRegionRule(regionId, state.monitorTypeId, {
        active: state.enabled,
        mandatory: state.mandatory,
        mandatoryKeywords: state.mandatoryKeywords,
        optionalKeywords: state.optionalKeywords,
      });
      const next: MonitorRuleCardState = {
        ...state,
        ruleId: result.ruleId,
        enabled: state.enabled,
        mandatoryKeywords: result.mandatoryKeywords,
        optionalKeywords: result.optionalKeywords,
      };
      setState(next);
      setDirty(false);
      setSaveStatus("saved");
      onSaved(next);
      setTimeout(() => setSaveStatus("idle"), 2500);
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Save failed");
      setSaveStatus("error");
    } finally {
      setSaving(false);
    }
  }

  function updateKeywords(field: "mandatoryKeywords" | "optionalKeywords", tags: string[]) {
    setState((prev) => ({...prev, [field]: tags}));
    setDirty(true);
    setSaveStatus("idle");
  }

  const icon = SUB_TYPE_ICON[state.subTypeCode] ?? "📋";

  return (
      <div
          className="overflow-hidden rounded-xl border border-white/5 bg-card transition-all"
      >
        {/* ── Card header ─────────────────────────────────────────────────────── */}
        <div className="flex items-center gap-3 px-4 py-3">
          <span className="shrink-0 text-xl leading-none">{icon}</span>

          {/* Title + description */}
          <div className="min-w-0 flex-1">
            <span className="text-sm font-semibold text-foreground">{state.defaultName}</span>
            {state.description && (
                <p className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">
                  {state.description}
                </p>
            )}
          </div>

          {/* Badge + Toggle */}
          <div className="flex shrink-0 items-center gap-2">
            {state.enabled && (
                <span
                    className="rounded-full bg-green-950/60 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-green-400 ring-1 ring-green-900/40">
              Required
            </span>
            )}
            <Toggle
                checked={state.enabled}
                onChange={(next) => setPendingEnabled(next)}
                disabled={toggling}
                aria-label={`${state.enabled ? "Disable" : "Enable"} ${state.defaultName} monitoring`}
            />
          </div>
        </div>

        {/* ── Keyword editor — visible for all cards so keywords can be pre-configured ── */}
        <div className="space-y-3 border-t border-white/5 px-4 py-3">

          {/* Required keywords */}
          <div className="space-y-1.5">
            <p className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
              <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-blue-400"/>
              Required Keywords
              <span className="font-normal normal-case tracking-normal text-muted-foreground/60">
            — finding any one triggers PASSING
          </span>
            </p>
            <TagInput
                tags={state.mandatoryKeywords}
                onChange={(tags) => updateKeywords("mandatoryKeywords", tags)}
                placeholder="e.g. Datenschutzerklärung"
                variant="primary"
                disabled={saving}
            />
          </div>

          {/* Optional keywords */}
          <div className="space-y-1.5">
            <p className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
              <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-amber-400"/>
              Optional Keywords
              <span className="font-normal normal-case tracking-normal text-muted-foreground/60">
            — boosts score; absence is silent
          </span>
            </p>
            <TagInput
                tags={state.optionalKeywords}
                onChange={(tags) => updateKeywords("optionalKeywords", tags)}
                placeholder="e.g. Datenschutz"
                variant="amber"
                disabled={saving}
            />
          </div>

          {/* Save footer */}
          {(dirty || saveStatus !== "idle") && (
              <div className="flex items-center justify-between gap-2 border-t border-white/5 pt-3">
                <div className="flex items-center gap-1.5 text-xs">
                  {saveStatus === "saved" && (
                      <>
                        <CheckCircle2 className="h-3.5 w-3.5 text-green-400"/>
                        <span className="text-green-400">Saved</span>
                      </>
                  )}
                  {saveStatus === "error" && errorMsg && (
                      <>
                        <AlertCircle className="h-3.5 w-3.5 text-red-400"/>
                        <span className="line-clamp-1 text-red-400">{errorMsg}</span>
                      </>
                  )}
                  {dirty && saveStatus === "idle" && (
                      <span className="text-amber-400">Unsaved changes</span>
                  )}
                </div>
                <button
                    onClick={handleSave}
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

        {/* Toggle error when card is disabled */}
        {saveStatus === "error" && errorMsg && !state.enabled && (
            <div className="border-t border-white/5 px-4 py-2">
              <p className="flex items-center gap-1.5 text-xs text-red-400">
                <AlertCircle className="h-3.5 w-3.5 shrink-0"/>
                {errorMsg}
              </p>
            </div>
        )}

        {/* Confirm toggle modal */}
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
