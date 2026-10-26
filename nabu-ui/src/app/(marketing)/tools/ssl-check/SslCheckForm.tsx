"use client";

import {useState} from "react";
import {AlertTriangle, CheckCircle2, Loader2, ShieldAlert, ShieldCheck} from "lucide-react";
import {TURNSTILE_SITE_KEY} from "@/lib/siteConfig";
import {Turnstile} from "@/components/tools/Turnstile";
import {readToolError} from "@/components/tools/toolErrors";
import {usePrefilledUrl} from "@/components/tools/usePrefilledUrl";

interface SslResult {
  success: boolean;
  host?: string;
  port?: number;
  tlsVersion?: string;
  subject?: string;
  issuer?: string;
  notAfter?: string;
  daysRemaining?: number;
  severity?: "ok" | "warning" | "critical";
  error?: string;
}

export function SslCheckForm() {
  const [url, setUrl] = usePrefilledUrl();
  const [token, setToken] = useState("");
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<SslResult | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const turnstileEnabled = !!TURNSTILE_SITE_KEY;
  const canSubmit = url.trim().length > 0 && (!turnstileEnabled || token.length > 0) && !loading;

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setErrorMsg(null);
    setResult(null);
    try {
      const res = await fetch("/api/v1/tools/ssl-check", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({url: url.trim(), turnstileToken: token}),
      });
      if (!res.ok) {
        throw new Error(await readToolError(res));
      }
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
          <label htmlFor="url" className="block text-sm font-medium text-slate-300 mb-1.5">
            Website URL
          </label>
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

        {turnstileEnabled && (
          <Turnstile siteKey={TURNSTILE_SITE_KEY} onToken={setToken} theme="dark" />
        )}

        <button
          type="submit"
          disabled={!canSubmit}
          className="flex items-center justify-center gap-2 rounded-xl bg-sky-500 hover:bg-sky-400 py-2.5 text-sm font-semibold text-white disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {loading && <Loader2 className="h-4 w-4 animate-spin" />}
          Check certificate
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

function ResultPanel({result}: {result: SslResult}) {
  if (!result.success) {
    return (
      <div className="mt-6 rounded-xl border border-red-500/30 bg-red-500/5 p-4 text-sm text-red-300 flex items-start gap-3">
        <ShieldAlert className="h-5 w-5 shrink-0 mt-0.5" />
        <div>
          <p className="font-semibold mb-1">Could not inspect certificate</p>
          <p className="text-red-300/80">{result.error}</p>
        </div>
      </div>
    );
  }

  const tone = result.severity === "ok"
    ? {border: "border-emerald-500/30", bg: "bg-emerald-500/5", icon: <ShieldCheck className="h-5 w-5 text-emerald-400" />, label: "Healthy"}
    : result.severity === "warning"
    ? {border: "border-amber-500/30", bg: "bg-amber-500/5", icon: <AlertTriangle className="h-5 w-5 text-amber-400" />, label: "Expiring soon"}
    : {border: "border-red-500/30", bg: "bg-red-500/5", icon: <ShieldAlert className="h-5 w-5 text-red-400" />, label: "Critical"};

  return (
    <div className={`mt-6 rounded-xl border ${tone.border} ${tone.bg} p-5`}>
      <div className="flex items-center gap-2 mb-4">
        {tone.icon}
        <p className="font-semibold text-white">{tone.label}</p>
        <span className="ml-auto text-xs text-slate-400">
          {result.host}:{result.port}
        </span>
      </div>

      <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3 text-sm">
        <Field label="Days remaining" value={`${result.daysRemaining}`} />
        <Field label="Expires" value={result.notAfter ?? ""} />
        <Field label="TLS version" value={result.tlsVersion ?? ""} />
        <Field label="Issuer" value={result.issuer ?? ""} className="sm:col-span-2 break-words" />
        <Field label="Subject" value={result.subject ?? ""} className="sm:col-span-2 break-words" />
      </dl>

      <div className="mt-5 flex items-center gap-2 text-xs text-slate-400">
        <CheckCircle2 className="h-4 w-4 text-emerald-400" />
        Want continuous monitoring across all your domains with email + Slack alerts?
        <a href="/register" className="text-sky-400 hover:text-sky-300 font-medium">Sign up free</a>
      </div>
    </div>
  );
}

function Field({label, value, className = ""}: {label: string; value: string; className?: string}) {
  return (
    <div className={className}>
      <dt className="text-xs uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-0.5 text-slate-200 font-medium">{value}</dd>
    </div>
  );
}
