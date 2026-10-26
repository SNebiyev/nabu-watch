"use client";

import {useState} from "react";
import {AlertTriangle, CheckCircle2, Loader2, ShieldAlert, ShieldCheck, XCircle} from "lucide-react";
import {TURNSTILE_SITE_KEY} from "@/lib/siteConfig";
import {Turnstile} from "@/components/tools/Turnstile";
import {readToolError} from "@/components/tools/toolErrors";
import {usePrefilledUrl} from "@/components/tools/usePrefilledUrl";

interface Check {
  status: "pass" | "warn" | "fail";
  message: string;
}

interface GdprResult {
  success: boolean;
  host?: string;
  scorePercent?: number;
  verdict?: string;
  checks?: Record<string, Check>;
  error?: string;
}

const CHECK_LABELS: Record<string, string> = {
  ssl: "SSL / TLS",
  impressum: "Impressum / Legal notice",
  privacy: "Privacy policy",
  cookieBanner: "Cookie banner",
};

export function GdprScanForm() {
  const [url, setUrl] = usePrefilledUrl();
  const [token, setToken] = useState("");
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<GdprResult | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const turnstileEnabled = !!TURNSTILE_SITE_KEY;
  const canSubmit = url.trim().length > 0 && (!turnstileEnabled || token.length > 0) && !loading;

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setErrorMsg(null);
    setResult(null);
    try {
      const res = await fetch("/api/v1/tools/gdpr-scan", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({url: url.trim(), turnstileToken: token}),
      });
      if (!res.ok) throw new Error(await readToolError(res));
      setResult(await res.json());
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Could not run scan");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="rounded-2xl border border-slate-800 bg-slate-900/50 p-6">
      <form onSubmit={onSubmit} className="flex flex-col gap-4">
        <div>
          <label htmlFor="url" className="block text-sm font-medium text-slate-300 mb-1.5">Website URL</label>
          <input
            id="url"
            type="url"
            required
            placeholder="https://example.com"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            disabled={loading}
            className="w-full rounded-lg border border-slate-700 bg-slate-900 px-3 py-2.5 text-sm text-slate-100 placeholder:text-slate-600 focus:border-sky-500 focus:outline-none disabled:opacity-60"
          />
        </div>
        {turnstileEnabled && <Turnstile siteKey={TURNSTILE_SITE_KEY} onToken={setToken} theme="dark" />}
        <button
          type="submit"
          disabled={!canSubmit}
          className="flex items-center justify-center gap-2 rounded-xl bg-sky-500 hover:bg-sky-400 py-2.5 text-sm font-semibold text-white disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {loading && <Loader2 className="h-4 w-4 animate-spin" />}
          Run GDPR scan
        </button>
        {errorMsg && (
          <p className="text-sm text-red-400 flex items-center gap-2">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            {errorMsg}
          </p>
        )}
      </form>
      {result && <ResultPanel result={result} />}
    </div>
  );
}

function ResultPanel({result}: {result: GdprResult}) {
  if (!result.success) {
    return (
      <div className="mt-6 rounded-xl border border-red-500/30 bg-red-500/5 p-4 text-sm text-red-300 flex items-start gap-3">
        <ShieldAlert className="h-5 w-5 shrink-0 mt-0.5" />
        <div>
          <p className="font-semibold mb-1">Could not run scan</p>
          <p className="text-red-300/80">{result.error}</p>
        </div>
      </div>
    );
  }

  const score = result.scorePercent ?? 0;
  const scoreTone = score >= 75 ? "text-emerald-400" : score >= 50 ? "text-amber-400" : "text-red-400";

  return (
    <div className="mt-6 rounded-xl border border-slate-800 bg-slate-900/40 p-5">
      <div className="flex items-center justify-between mb-5">
        <div>
          <p className={`text-3xl font-bold ${scoreTone}`}>{score}<span className="text-base text-slate-500">/100</span></p>
          <p className="mt-1 text-sm text-slate-300">{result.verdict}</p>
        </div>
        <span className="text-xs text-slate-500">{result.host}</span>
      </div>

      <div className="space-y-2">
        {result.checks && Object.entries(result.checks).map(([key, check]) => (
          <CheckRow key={key} label={CHECK_LABELS[key] ?? key} check={check} />
        ))}
      </div>

      <div className="mt-5 flex items-center gap-2 text-xs text-slate-400">
        <CheckCircle2 className="h-4 w-4 text-emerald-400" />
        Want regional regulator coverage, AI policy review, and continuous alerts?
        <a href="/register" className="text-sky-400 hover:text-sky-300 font-medium">Sign up free</a>
      </div>
    </div>
  );
}

function CheckRow({label, check}: {label: string; check: Check}) {
  const tone =
    check.status === "pass"
      ? {icon: <ShieldCheck className="h-4 w-4 text-emerald-400" />, badge: "bg-emerald-500/10 text-emerald-300 ring-emerald-500/30"}
      : check.status === "warn"
      ? {icon: <AlertTriangle className="h-4 w-4 text-amber-400" />, badge: "bg-amber-500/10 text-amber-300 ring-amber-500/30"}
      : {icon: <XCircle className="h-4 w-4 text-red-400" />, badge: "bg-red-500/10 text-red-300 ring-red-500/30"};

  return (
    <div className="flex items-start gap-3 rounded-lg border border-slate-800 bg-slate-900/40 p-3">
      <div className="mt-0.5">{tone.icon}</div>
      <div className="flex-1 min-w-0">
        <p className="text-sm font-medium text-white">{label}</p>
        <p className="mt-0.5 text-xs text-slate-400">{check.message}</p>
      </div>
      <span className={`text-[10px] uppercase tracking-wide font-semibold rounded-full px-2 py-0.5 ring-1 ${tone.badge}`}>
        {check.status}
      </span>
    </div>
  );
}
