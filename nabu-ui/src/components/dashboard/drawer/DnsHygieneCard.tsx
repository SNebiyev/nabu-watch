"use client";

import {useState} from "react";
import type {MouseEvent} from "react";
import {AlertTriangle, CheckCircle2, ChevronDown, Loader2, Mail, XCircle} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {scoreTone} from "./utils";
import {remediationFor} from "./securityRemediation";
import {RemediationBlock} from "./RemediationBlock";

interface DnsCheck {
  label: string;
  status: "pass" | "warn" | "fail";
  message: string;
  weight: number;
  maxWeight: number;
}

interface DnsResult {
  success: boolean;
  apex?: string;
  score?: number;
  verdict?: string;
  checks?: DnsCheck[];
  error?: string;
}

export function DnsHygieneCard({websiteId}: {websiteId: string}) {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<DnsResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);
  const hasDetails = !!(result || error);

  async function onRunScan(ev: MouseEvent) {
    ev.stopPropagation();
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      const res = await apiClient(`/api/v1/security/websites/${websiteId}/dns-hygiene-scan`, {method: "POST"});
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
          <Mail className="h-4 w-4 text-amber-400" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-foreground">DNS Hygiene</p>
          <p className="text-[11px] sm:text-xs text-muted-foreground line-clamp-2">
            SPF, DMARC, DKIM, CAA — email spoofing exposure.
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

function ResultPanel({result, websiteId}: {result: DnsResult; websiteId: string}) {
  const score = result.score ?? 0;
  const checks = result.checks ?? [];
  const failing = checks.filter((c) => c.status !== "pass");
  return (
    <div className="mt-4 ml-0 sm:ml-11 rounded-lg border border-amber-400/20 bg-slate-900/40 p-2.5 sm:p-3 overflow-hidden">
      <div className="flex items-center justify-between mb-3">
        <p className={`text-2xl font-bold ${scoreTone(score)}`}>
          {score}<span className="text-xs text-slate-500">/100</span>
        </p>
        <span className="text-xs text-slate-300">{result.verdict}</span>
      </div>
      <div className="space-y-1.5">
        {checks.map((c) => <Row key={c.label} check={c} />)}
      </div>
      {failing.map((c) => {
        const rem = remediationFor("DNS_HYGIENE", {label: c.label, status: c.status});
        if (!rem) return null;
        return (
          <RemediationBlock
            key={`rem-${c.label}`}
            websiteId={websiteId}
            scan="DNS_HYGIENE"
            findingKey={`${c.label}:${c.status}`}
            findingContext={`${c.label} DNS check is ${c.status}: ${c.message}`}
            remediation={rem}
          />
        );
      })}
    </div>
  );
}

function Row({check}: {check: DnsCheck}) {
  const icon = check.status === "pass"
    ? <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400 shrink-0" />
    : check.status === "warn"
    ? <AlertTriangle className="h-3.5 w-3.5 text-amber-400 shrink-0" />
    : <XCircle className="h-3.5 w-3.5 text-red-400 shrink-0" />;
  return (
    <div className="flex items-start gap-2 text-xs">
      <span className="pt-0.5">{icon}</span>
      <div className="min-w-0 flex-1">
        <span className="font-medium text-white">{check.label}</span>
        <span className="text-slate-400 break-words"> — {check.message}</span>
      </div>
    </div>
  );
}
