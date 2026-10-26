"use client";

import {useState} from "react";
import {AlertTriangle, CheckCircle2, ChevronDown, FileWarning, Loader2} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {scoreTone} from "./utils";
import {remediationFor} from "./securityRemediation";
import {RemediationBlock} from "./RemediationBlock";

interface Finding {
  path: string;
  label: string;
}

interface ExposedFilesResult {
  success: boolean;
  host?: string;
  score?: number;
  verdict?: string;
  findings?: Finding[];
  error?: string;
}

export function ExposedFilesCard({websiteId}: {websiteId: string}) {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<ExposedFilesResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);

  const hasDetails = !!(result || error);

  async function onRunScan(ev: React.MouseEvent) {
    ev.stopPropagation();
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      const res = await apiClient(`/api/v1/security/websites/${websiteId}/exposed-files-scan`, {
        method: "POST",
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `HTTP ${res.status}`);
      }
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
    <div className="rounded-xl border border-amber-400/60 bg-gradient-to-br from-amber-500/10 via-amber-400/5 to-transparent ring-1 ring-amber-400/30 px-3 py-3 md:py-3.5 transition-colors">
      <div
        className={`flex items-center gap-3 ${hasDetails ? "cursor-pointer" : ""}`}
        onClick={hasDetails ? () => setExpanded((v) => !v) : undefined}
      >
        <div className="flex h-8 w-8 md:h-9 md:w-9 shrink-0 items-center justify-center rounded-xl bg-amber-500/15 ring-1 ring-amber-400/40">
          <FileWarning className="h-4 w-4 text-amber-400" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-[--foreground]">Exposed Files</p>
          <p className="text-[11px] sm:text-xs text-[--muted-foreground] line-clamp-2">
            Probes .env, .git, backup archives and other leak-prone paths.
          </p>
        </div>
        <button
          type="button"
          onClick={onRunScan}
          disabled={running}
          className="shrink-0 rounded-lg px-3 py-1.5 text-xs font-semibold bg-amber-500 text-white hover:bg-amber-400 disabled:opacity-60 transition-colors"
        >
          {running ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : "Run scan"}
        </button>
        {hasDetails && (
          <ChevronDown
            className={`h-4 w-4 shrink-0 text-amber-400/70 transition-transform ${expanded ? "rotate-180" : ""}`}
          />
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

function ResultPanel({result, websiteId}: {result: ExposedFilesResult; websiteId: string}) {
  const findings = result.findings ?? [];
  const score = result.score ?? 0;

  return (
    <div className="mt-4 ml-0 sm:ml-11 rounded-lg border border-amber-400/20 bg-slate-900/40 p-2.5 sm:p-3 overflow-hidden">
      <div className="flex items-center justify-between mb-3">
        <p className={`text-2xl font-bold ${scoreTone(score)}`}>
          {score}
          <span className="text-xs text-slate-500">/100</span>
        </p>
        <span className="text-xs text-slate-300">{result.verdict}</span>
      </div>
      {findings.length === 0 ? (
        <div className="flex items-center gap-2 text-xs text-emerald-400">
          <CheckCircle2 className="h-3.5 w-3.5" />
          <span>No exposed paths detected.</span>
        </div>
      ) : (
        <div className="space-y-1.5">
          {findings.map((f) => (
            <div key={f.path} className="flex items-start gap-2 text-xs">
              <AlertTriangle className="h-3.5 w-3.5 text-red-400 shrink-0 mt-0.5" />
              <div className="min-w-0 flex-1">
                <p className="font-medium text-white break-words">{f.label}</p>
                <code className="text-[11px] text-slate-400 break-all">{f.path}</code>
              </div>
            </div>
          ))}
        </div>
      )}
      {findings.map((f) => {
        const rem = remediationFor("EXPOSED_FILES", {path: f.path});
        if (!rem) return null;
        return (
          <RemediationBlock
            key={`rem-${f.path}`}
            websiteId={websiteId}
            scan="EXPOSED_FILES"
            findingKey={f.path}
            findingContext={`Path "${f.path}" (${f.label}) is publicly reachable.`}
            remediation={rem}
          />
        );
      })}
    </div>
  );
}
