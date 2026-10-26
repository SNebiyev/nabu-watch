"use client";

import {useState} from "react";
import {AlertTriangle, CheckCircle2, ChevronDown, Loader2, ShieldCheck, XCircle} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {scoreTone} from "./utils";
import {remediationFor} from "./securityRemediation";
import {RemediationBlock} from "./RemediationBlock";

interface HeaderCheck {
  status: "pass" | "warn" | "fail";
  message: string;
  earnedWeight: number;
  maxWeight: number;
}

interface SecurityResult {
  success: boolean;
  host?: string;
  httpStatus?: number;
  score?: number;
  verdict?: string;
  checks?: Record<string, HeaderCheck>;
  error?: string;
}

export function SecurityHeadersCard({websiteId}: {websiteId: string}) {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<SecurityResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);

  const hasDetails = !!(result || error);

  async function onRunScan(ev: React.MouseEvent) {
    ev.stopPropagation();
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      const res = await apiClient(`/api/v1/security/websites/${websiteId}/headers-scan`, {
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
          <ShieldCheck className="h-4 w-4 text-amber-400" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-[--foreground]">Security Headers</p>
          <p className="text-[11px] sm:text-xs text-[--muted-foreground] line-clamp-2">
            CSP, HSTS, X-Frame-Options and friends — manual scan.
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

      {expanded && result && result.success && (
        <ResultPanel result={result} websiteId={websiteId} />
      )}
      {expanded && result && !result.success && (
        <p className="mt-3 pl-11 text-xs text-red-400">{result.error}</p>
      )}
    </div>
  );
}

function ResultPanel({result, websiteId}: {result: SecurityResult; websiteId: string}) {
  const score = result.score ?? 0;
  const entries = result.checks ? Object.entries(result.checks) : [];
  const failing = entries.filter(([, c]) => c.status !== "pass");
  return (
    <div className="mt-4 ml-0 sm:ml-11 rounded-lg border border-amber-400/20 bg-slate-900/40 p-2.5 sm:p-3 overflow-hidden">
      <div className="flex items-center justify-between mb-3">
        <p className={`text-2xl font-bold ${scoreTone(score)}`}>
          {score}<span className="text-xs text-slate-500">/100</span>
        </p>
        <span className="text-xs text-slate-300">{result.verdict}</span>
      </div>
      <div className="space-y-1.5">
        {entries.map(([label, c]) => (
          <CheckRow key={label} label={label} check={c} />
        ))}
      </div>
      {failing.map(([label, c]) => {
        const rem = remediationFor("SECURITY_HEADERS", {label, status: c.status});
        if (!rem) return null;
        return (
          <RemediationBlock
            key={`rem-${label}`}
            websiteId={websiteId}
            scan="SECURITY_HEADERS"
            findingKey={`${label}:${c.status}`}
            findingContext={`Header "${label}" is ${c.status}: ${c.message}`}
            remediation={rem}
          />
        );
      })}
    </div>
  );
}

function CheckRow({label, check}: {label: string; check: HeaderCheck}) {
  const icon = check.status === "pass"
    ? <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400 shrink-0" />
    : check.status === "warn"
    ? <AlertTriangle className="h-3.5 w-3.5 text-amber-400 shrink-0" />
    : <XCircle className="h-3.5 w-3.5 text-red-400 shrink-0" />;
  return (
    <div className="flex items-start gap-2 text-xs">
      <span className="pt-0.5">{icon}</span>
      <div className="min-w-0 flex-1">
        <span className="font-medium text-white break-words">{label}</span>
        <span className="text-slate-400 break-words"> — {check.message}</span>
      </div>
    </div>
  );
}
