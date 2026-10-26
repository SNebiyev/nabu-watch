"use client";

import {useState} from "react";
import type {MouseEvent} from "react";
import {AlertTriangle, ChevronDown, Loader2, ShieldCheck} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {remediationFor} from "./securityRemediation";
import {RemediationBlock} from "./RemediationBlock";

interface CertInfo {
  subject: string;
  issuer: string;
  notBefore: string;
  notAfter: string;
  daysUntilExpiry: number;
  signatureAlgorithm: string;
}

interface TlsResult {
  success: boolean;
  host?: string;
  protocol?: string;
  cipher?: string;
  chainLength?: number;
  cert?: CertInfo | null;
  grade?: string;
  score?: number;
  error?: string;
}

export function TlsGradeCard({websiteId}: {websiteId: string}) {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<TlsResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);
  const hasDetails = !!(result || error);

  async function onRunScan(ev: MouseEvent) {
    ev.stopPropagation();
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      const res = await apiClient(`/api/v1/security/websites/${websiteId}/tls-grade-scan`, {method: "POST"});
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
          <ShieldCheck className="h-4 w-4 text-amber-400" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-foreground">TLS Grade</p>
          <p className="text-[11px] sm:text-xs text-muted-foreground line-clamp-2">
            Protocol, cipher suite and certificate chain.
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

      {expanded && result && result.success && <TlsPanel result={result} websiteId={websiteId} />}
      {expanded && result && !result.success && (
        <p className="mt-3 pl-11 text-xs text-red-400">{result.error}</p>
      )}
    </div>
  );
}

function TlsPanel({result, websiteId}: {result: TlsResult; websiteId: string}) {
  const gradeTone = (() => {
    switch (result.grade) {
      case "A": return "text-emerald-400";
      case "B": return "text-emerald-300";
      case "C": return "text-amber-300";
      case "D": return "text-orange-400";
      default:  return "text-red-400";
    }
  })();
  const rem = result.grade ? remediationFor("TLS_GRADE", {grade: result.grade}) : null;
  return (
    <div className="mt-4 ml-0 sm:ml-11 rounded-lg border border-amber-400/20 bg-slate-900/40 p-2.5 sm:p-3 space-y-2 overflow-hidden">
      <div className="flex items-center justify-between">
        <p className={`text-3xl font-bold ${gradeTone}`}>
          {result.grade ?? "?"}
          <span className="text-xs font-normal text-slate-500 ml-2">{result.score ?? 0}/100</span>
        </p>
        <span className="text-xs text-slate-300">{result.protocol}</span>
      </div>
      <InfoRow label="Cipher" value={result.cipher ?? "—"} />
      <InfoRow label="Chain" value={`${result.chainLength ?? 0} certificate${result.chainLength === 1 ? "" : "s"}`} />
      {result.cert && (
        <>
          <InfoRow label="Issuer" value={result.cert.issuer} />
          <InfoRow label="Expires in" value={`${result.cert.daysUntilExpiry} days`} />
          <InfoRow label="Signature" value={result.cert.signatureAlgorithm} />
        </>
      )}
      {rem && (
        <RemediationBlock
          websiteId={websiteId}
          scan="TLS_GRADE"
          findingKey={`grade:${result.grade}`}
          findingContext={`TLS grade ${result.grade} (protocol=${result.protocol ?? "?"}, cipher=${result.cipher ?? "?"}).`}
          remediation={rem}
        />
      )}
    </div>
  );
}

function InfoRow({label, value}: {label: string; value: string}) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-start gap-0.5 sm:gap-2 text-xs">
      <span className="w-16 sm:w-20 shrink-0 text-slate-500">{label}</span>
      <span className="min-w-0 flex-1 text-slate-200 break-all">{value}</span>
    </div>
  );
}
