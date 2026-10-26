"use client";

import {useEffect, useRef, useState} from "react";
import {CheckCircle2, Loader2, Sparkles, Wrench} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import type {RemediationEntry, ScanKey} from "./securityRemediation";

interface Props {
  websiteId: string;
  scan: ScanKey;
  /** Stable identifier used both for cache and for the AI prompt. */
  findingKey: string;
  /** Human-readable description of the finding for the AI prompt. */
  findingContext: string;
  remediation: RemediationEntry;
}

/**
 * Renders the always-on static remediation for a failing finding plus an
 * "Ask AI" button that fetches a website-tailored recommendation. The AI
 * response is rate-limited on the backend and only available when the server has an AI provider configured.
 */
export function RemediationBlock({
  websiteId,
  scan,
  findingKey,
  findingContext,
  remediation,
}: Props) {
  const [asking, setAsking] = useState(false);
  const [aiText, setAiText] = useState<string | null>(null);
  const [aiError, setAiError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  async function askAi(ev: React.MouseEvent) {
    ev.stopPropagation();
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setAsking(true);
    setAiError(null);
    setAiText(null);
    try {
      const res = await apiClient(
        `/api/v1/security/websites/${websiteId}/remediation`,
        {
          method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify({scan, findingKey, findingContext}),
          signal: controller.signal,
        },
      );
      if (res.status === 503) {
        throw new Error("AI remediation is not configured on this server.");
      }
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `HTTP ${res.status}`);
      }
      const json = await res.json();
      const text = String(json.remediation ?? "").trim();
      if (controller.signal.aborted) return;
      if (!text) {
        setAiError("AI returned an empty response. Please retry.");
        return;
      }
      setAiText(text);
    } catch (err) {
      if (controller.signal.aborted) return;
      setAiError(err instanceof Error ? err.message : "Could not reach AI");
    } finally {
      if (!controller.signal.aborted) setAsking(false);
    }
  }

  return (
    <div className="mt-3 rounded-lg border border-amber-400/15 bg-amber-500/[0.04] p-2.5 sm:p-3">
      <div className="flex items-start gap-2">
        <Wrench className="h-3.5 w-3.5 text-amber-300 shrink-0 mt-0.5" />
        <div className="min-w-0 flex-1">
          <p className="text-xs font-semibold text-amber-200">
            Remediation — {remediation.title}
          </p>
          <ul className="mt-1.5 space-y-1">
            {remediation.steps.map((step, i) => (
              <li key={i} className="flex items-start gap-1.5 text-[11px] text-slate-300">
                <CheckCircle2 className="h-3 w-3 text-amber-300/70 shrink-0 mt-0.5" />
                <span className="break-words">{step}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-amber-400/15 pt-2">
        {!aiText && (
          <button
            type="button"
            onClick={askAi}
            disabled={asking}
            className="inline-flex items-center gap-1.5 rounded-md bg-violet-500/15 px-2.5 py-1 text-[11px] font-medium text-violet-200 ring-1 ring-violet-400/30 hover:bg-violet-500/25 disabled:opacity-60 transition-colors"
          >
            {asking ? (
              <Loader2 className="h-3 w-3 animate-spin" />
            ) : (
              <Sparkles className="h-3 w-3" />
            )}
            Ask AI to help me fix this
          </button>
        )}
        {aiError && (
          <span className="text-[11px] text-red-400">{aiError}</span>
        )}
      </div>

      {aiText && (
        <div className="mt-2 rounded-md bg-violet-500/[0.08] p-2.5 ring-1 ring-violet-400/20">
          <p className="text-[11px] font-semibold text-violet-200 flex items-center gap-1.5">
            <Sparkles className="h-3 w-3" /> AI recommendation
          </p>
          <p className="mt-1 whitespace-pre-wrap text-[11px] text-slate-200 break-words">
            {aiText}
          </p>
        </div>
      )}
    </div>
  );
}
