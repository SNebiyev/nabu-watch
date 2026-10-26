"use client";

import {useState} from "react";
import {AlertTriangle, CheckCircle2, Info, Loader2, ShieldAlert} from "lucide-react";
import {TURNSTILE_SITE_KEY} from "@/lib/siteConfig";
import {Turnstile} from "@/components/tools/Turnstile";
import {readToolError} from "@/components/tools/toolErrors";
import {usePrefilledUrl} from "@/components/tools/usePrefilledUrl";

interface CookieBannerResult {
  success: boolean;
  host?: string;
  detected?: boolean;
  platforms?: string[];
  hint?: string;
  error?: string;
}

export function CookieBannerForm() {
  const [url, setUrl] = usePrefilledUrl();
  const [token, setToken] = useState("");
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<CookieBannerResult | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const turnstileEnabled = !!TURNSTILE_SITE_KEY;
  const canSubmit = url.trim().length > 0 && (!turnstileEnabled || token.length > 0) && !loading;

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setErrorMsg(null);
    setResult(null);
    try {
      const res = await fetch("/api/v1/tools/cookie-banner-checker", {
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
          Check cookie banner
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

function ResultPanel({result}: {result: CookieBannerResult}) {
  if (!result.success) {
    return (
      <div className="mt-6 rounded-xl border border-red-500/30 bg-red-500/5 p-4 text-sm text-red-300 flex items-start gap-3">
        <ShieldAlert className="h-5 w-5 shrink-0 mt-0.5" />
        <div>
          <p className="font-semibold mb-1">Could not run check</p>
          <p className="text-red-300/80">{result.error}</p>
        </div>
      </div>
    );
  }

  const tone = result.detected
    ? {border: "border-emerald-500/30", bg: "bg-emerald-500/5", icon: <CheckCircle2 className="h-5 w-5 text-emerald-400" />, label: "Detected"}
    : {border: "border-amber-500/30", bg: "bg-amber-500/5", icon: <AlertTriangle className="h-5 w-5 text-amber-400" />, label: "Not detected"};

  return (
    <div className={`mt-6 rounded-xl border ${tone.border} ${tone.bg} p-5`}>
      <div className="flex items-center gap-2 mb-3">
        {tone.icon}
        <p className="font-semibold text-white">{tone.label}</p>
        <span className="ml-auto text-xs text-slate-400">{result.host}</span>
      </div>
      {result.detected && result.platforms && result.platforms.length > 0 && (
        <p className="text-sm text-slate-300">
          Consent platform{result.platforms.length > 1 ? "s" : ""}: <span className="font-medium text-white">{result.platforms.join(", ")}</span>
        </p>
      )}
      {result.hint && (
        <div className="mt-3 flex items-start gap-2 rounded-lg border border-sky-500/20 bg-sky-500/5 p-3 text-xs text-sky-200">
          <Info className="h-4 w-4 shrink-0 mt-0.5 text-sky-400" />
          <p>{result.hint}</p>
        </div>
      )}
      <div className="mt-4 flex items-center gap-2 text-xs text-slate-400">
        <CheckCircle2 className="h-4 w-4 text-emerald-400" />
        Want full headless-browser scans + AI consent classification?
        <a href="/register" className="text-sky-400 hover:text-sky-300 font-medium">Sign up free</a>
      </div>
    </div>
  );
}
