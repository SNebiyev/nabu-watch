"use client";

import {useState} from "react";
import type {MouseEvent} from "react";
import {AlertTriangle, CheckCircle2, ChevronDown, Code2, Loader2} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {scoreTone} from "./utils";
import {remediationFor} from "./securityRemediation";
import {RemediationBlock} from "./RemediationBlock";

interface Finding {
  library: string;
  version: string;
  cve: string;
  severity: string;
  summary: string;
}

interface CveResult {
  success: boolean;
  host?: string;
  score?: number;
  verdict?: string;
  scriptsScanned?: number;
  findings?: Finding[];
  error?: string;
}

export function JsLibraryCveCard({websiteId}: {websiteId: string}) {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<CveResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);
  const hasDetails = !!(result || error);

  async function onRunScan(ev: MouseEvent) {
    ev.stopPropagation();
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      const res = await apiClient(`/api/v1/security/websites/${websiteId}/js-library-cve-scan`, {method: "POST"});
      if (!res.ok) throw new Error((await res.text()) || `HTTP ${res.status}`);
      setResult(await res.json());
      setExpanded(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not run scan");
      setExpanded(true);
    } finally {
      setRunning(false);
    }
  }

  return (
    <div className="rounded-xl border border-amber-400/60 bg-gradient-to-br from-amber-500/10 via-amber-400/5 to-transparent ring-1 ring-amber-400/30 px-3 py-3 md:py-3.5">
      <div
        className={`flex items-center gap-3 ${hasDetails ? "cursor-pointer" : ""}`}
        onClick={hasDetails ? () => setExpanded((v) => !v) : undefined}
      >
        <div className="flex h-8 w-8 md:h-9 md:w-9 shrink-0 items-center justify-center rounded-xl bg-amber-500/15 ring-1 ring-amber-400/40">
          <Code2 className="h-4 w-4 text-amber-400" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-foreground">JS Library CVE</p>
          <p className="text-[11px] sm:text-xs text-muted-foreground line-clamp-2">
            Matches script URLs against known-vulnerable jQuery, Bootstrap, Lodash and friends.
          </p>
        </div>
        <button
          type="button"
          onClick={onRunScan}
          disabled={running}
          className="shrink-0 rounded-lg px-3 py-1.5 text-xs font-semibold bg-amber-500 text-white hover:bg-amber-400 disabled:opacity-60"
        >
          {running ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : "Run scan"}
        </button>
        {hasDetails && (
          <ChevronDown className={`h-4 w-4 shrink-0 text-amber-400/70 transition-transform ${expanded ? "rotate-180" : ""}`} />
        )}
      </div>

      {expanded && error && (
        <p className="mt-2 pl-11 text-xs text-red-400 flex items-center gap-1.5">
          <AlertTriangle className="h-3.5 w-3.5" /> {error}
        </p>
      )}
      {expanded && result && result.success && <ResultPanel result={result} websiteId={websiteId} />}
      {expanded && result && !result.success && (
        <p className="mt-3 pl-11 text-xs text-red-400">{result.error}</p>
      )}
    </div>
  );
}

function ResultPanel({result, websiteId}: {result: CveResult; websiteId: string}) {
  const findings = result.findings ?? [];
  const score = result.score ?? 0;
  return (
    <div className="mt-4 ml-0 sm:ml-11 rounded-lg border border-amber-400/20 bg-slate-900/40 p-2.5 sm:p-3 overflow-hidden">
      <div className="flex items-center justify-between mb-3">
        <p className={`text-2xl font-bold ${scoreTone(score)}`}>
          {score}<span className="text-xs text-slate-500">/100</span>
        </p>
        <span className="text-xs text-slate-300 text-right">
          {result.verdict}
          {typeof result.scriptsScanned === "number" && (
            <span className="block text-[10px] text-slate-500">
              {result.scriptsScanned} script{result.scriptsScanned === 1 ? "" : "s"} scanned
            </span>
          )}
        </span>
      </div>
      {findings.length === 0 ? (
        <div className="flex items-center gap-2 text-xs text-emerald-400">
          <CheckCircle2 className="h-3.5 w-3.5" /> No vulnerable libraries detected.
        </div>
      ) : (
        <div className="space-y-2">
          {findings.map((f) => (
            <div key={`${f.library}|${f.version}|${f.cve}`} className="flex items-start gap-2 text-xs">
              <AlertTriangle className="h-3.5 w-3.5 text-red-400 shrink-0 mt-0.5" />
              <div className="min-w-0 flex-1">
                <p className="break-words">
                  <span className="font-medium text-white">{f.library} {f.version}</span>
                  <span className={`ml-2 text-[10px] uppercase tracking-wide ${severityTone(f.severity)}`}>
                    {f.severity}
                  </span>
                </p>
                <p className="text-[11px] text-slate-400 break-words">{f.cve} — {f.summary}</p>
              </div>
            </div>
          ))}
        </div>
      )}
      {findings.map((f) => {
        const rem = remediationFor("JS_LIBRARY_CVE", {severity: f.severity});
        if (!rem) return null;
        return (
          <RemediationBlock
            key={`rem-${f.library}-${f.version}-${f.cve}`}
            websiteId={websiteId}
            scan="JS_LIBRARY_CVE"
            findingKey={`${f.library}|${f.version}|${f.cve}`}
            findingContext={`${f.library} ${f.version} has ${f.severity} vulnerability ${f.cve}: ${f.summary}`}
            remediation={rem}
          />
        );
      })}
    </div>
  );
}

function severityTone(severity: string): string {
  switch (severity.toUpperCase()) {
    case "HIGH":   return "text-red-400";
    case "MEDIUM": return "text-amber-300";
    default:       return "text-slate-400";
  }
}
