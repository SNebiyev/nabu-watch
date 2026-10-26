"use client";

import {useCallback, useState} from "react";
import type {ComponentType} from "react";
import {
  ChevronDown,
  Clock,
  Code2,
  FileWarning,
  Fingerprint,
  Loader2,
  Lock,
  Mail,
  Network,
  ShieldCheck,
} from "lucide-react";
import {useVerifiedAt} from "./useVerifiedAt";
import {useSecurityFeatures, type SecurityFeature, type SecurityScanKey} from "./useSecurityFeatures";
import {SecurityHeadersCard} from "./SecurityHeadersCard";
import {ExposedFilesCard} from "./ExposedFilesCard";
import {TlsGradeCard} from "./TlsGradeCard";
import {DnsHygieneCard} from "./DnsHygieneCard";
import {OpenPortsCard} from "./OpenPortsCard";
import {JsLibraryCveCard} from "./JsLibraryCveCard";
import {TyposquatWatchCard} from "./TyposquatWatchCard";

interface Props {
  websiteId: string;
}

interface ScanMeta {
  key: SecurityScanKey;
  name: string;
  desc: string;
  icon: ComponentType<{className?: string}>;
}

/**
 * Canonical catalogue rendered client-side so the Security Audit
 * section can show every scan — enabled or disabled — even if the
 * features endpoint hasn't resolved yet. Order matches the backend
 * {@code SecurityScan} enum.
 */
const CATALOGUE: ScanMeta[] = [
  {key: "SECURITY_HEADERS", name: "Security Headers", icon: ShieldCheck,
    desc: "CSP, HSTS, X-Frame-Options and friends — manual scan."},
  {key: "EXPOSED_FILES", name: "Exposed Files", icon: FileWarning,
    desc: "Probes .env, .git, backup archives and leak-prone paths."},
  {key: "TLS_GRADE", name: "TLS Grade", icon: Lock,
    desc: "Protocol, cipher suite and certificate chain."},
  {key: "DNS_HYGIENE", name: "DNS Hygiene", icon: Mail,
    desc: "SPF, DMARC, DKIM, CAA — email spoofing exposure."},
  {key: "OPEN_PORTS", name: "Open Ports", icon: Network,
    desc: "TCP probe of FTP, SSH, DB and alt-HTTP ports."},
  {key: "JS_LIBRARY_CVE", name: "JS Library CVE", icon: Code2,
    desc: "Matches script URLs against known-vulnerable versions."},
  {key: "TYPOSQUAT_WATCH", name: "Typosquat Watch", icon: Fingerprint,
    desc: "Flags registered look-alike domains for phishing risk."},
];

export function SecurityAuditGroup({websiteId}: Props) {
  const verifiedAt = useVerifiedAt(websiteId);
  const features = useSecurityFeatures(websiteId);
  const storageKey = `security-audit-open:${websiteId}`;
  const [open, setOpenState] = useState<boolean>(() => {
    if (typeof window === "undefined") return false;
    const stored = window.localStorage.getItem(storageKey);
    return stored === "1";
  });
  const setOpen = useCallback((next: boolean) => {
    setOpenState(next);
    try { window.localStorage.setItem(storageKey, next ? "1" : "0"); } catch { /* quota */ }
  }, [storageKey]);

  const loading = verifiedAt === undefined || features === undefined;
  const verified = !!verifiedAt;
  const enabledCount = (features ?? []).filter((f) => f.enabled).length;

  return (
    <div className="rounded-xl border border-amber-400/40 bg-gradient-to-br from-amber-500/[0.04] via-amber-400/[0.02] to-transparent">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex w-full items-center gap-3 px-3 py-3 md:py-3.5 text-left"
        aria-expanded={open}
      >
        <div className="flex h-8 w-8 md:h-9 md:w-9 shrink-0 items-center justify-center rounded-xl bg-amber-500/15 ring-1 ring-amber-400/40">
          {verified ? (
            <ShieldCheck className="h-4 w-4 text-amber-400" />
          ) : (
            <Lock className="h-4 w-4 text-amber-400/80" />
          )}
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-foreground flex items-center gap-2">
            Security Audit
            <span className="rounded-full bg-amber-500/15 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-amber-400 ring-1 ring-amber-400/30">
              {verified ? "Verified only" : "Locked"}
            </span>
          </p>
          <p className="text-[11px] sm:text-xs text-muted-foreground line-clamp-2">
            {loading
              ? "Loading available checks…"
              : verified
                ? `${enabledCount}/${CATALOGUE.length} checks available · manual scan only.`
                : `${CATALOGUE.length} checks unlock after you verify domain ownership.`}
          </p>
        </div>
        <ChevronDown
          className={`h-4 w-4 shrink-0 text-amber-400/70 transition-transform ${open ? "rotate-180" : ""}`}
        />
      </button>
      {open && (
        <div className="border-t border-amber-400/20 px-3 py-3 space-y-2.5">
          {loading ? (
            <div className="flex items-center gap-2 px-2 py-6 text-xs text-muted-foreground">
              <Loader2 className="h-3.5 w-3.5 animate-spin" /> Checking availability…
            </div>
          ) : (
            CATALOGUE.map((meta) => {
              const feature = (features ?? []).find((f) => f.scan === meta.key);
              return (
                <ScanRow
                  key={meta.key}
                  websiteId={websiteId}
                  meta={meta}
                  feature={feature}
                />
              );
            })
          )}
        </div>
      )}
    </div>
  );
}

function ScanRow({websiteId, meta, feature}: {
  websiteId: string;
  meta: ScanMeta;
  feature: SecurityFeature | undefined;
}) {
  // Server hasn't answered yet OR doesn't know this scan → lock by
  // default; better a brief false-lock than a falsely-active Run button.
  if (!feature || !feature.enabled) {
    return <LockedScanRow meta={meta} feature={feature} />;
  }
  switch (meta.key) {
    case "SECURITY_HEADERS": return <SecurityHeadersCard websiteId={websiteId} />;
    case "EXPOSED_FILES":    return <ExposedFilesCard websiteId={websiteId} />;
    case "TLS_GRADE":        return <TlsGradeCard websiteId={websiteId} />;
    case "DNS_HYGIENE":      return <DnsHygieneCard websiteId={websiteId} />;
    case "OPEN_PORTS":       return <OpenPortsCard websiteId={websiteId} />;
    case "JS_LIBRARY_CVE":   return <JsLibraryCveCard websiteId={websiteId} />;
    case "TYPOSQUAT_WATCH":  return <TyposquatWatchCard websiteId={websiteId} />;
  }
}

function LockedScanRow({meta, feature}: {
  meta: ScanMeta;
  feature: SecurityFeature | undefined;
}) {
  const reason = feature?.lockReason ?? "VERIFIED_REQUIRED";
  const {button, hint, chipTone, chipIcon: ChipIcon} = lockPresentation(
      reason);

  return (
    <div
      title={hint}
      className="rounded-xl border border-slate-700/40 bg-slate-900/30 px-3 py-3 md:py-3.5 opacity-80"
    >
      <div className="flex items-center gap-3">
        <div className="flex h-8 w-8 md:h-9 md:w-9 shrink-0 items-center justify-center rounded-xl bg-slate-800/60 ring-1 ring-slate-700/40">
          <meta.icon className="h-4 w-4 text-slate-500" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-slate-300 flex flex-wrap items-center gap-1.5">
            {meta.name}
            <span className={`inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide ring-1 ${chipTone}`}>
              <ChipIcon className="h-2.5 w-2.5" /> {button}
            </span>
          </p>
          <p className="text-[11px] sm:text-xs text-slate-500 line-clamp-2">{meta.desc}</p>
        </div>
        <span className="shrink-0 rounded-lg px-3 py-1.5 text-xs font-semibold bg-slate-800/60 text-slate-400 cursor-not-allowed select-none">
          Locked
        </span>
      </div>
    </div>
  );
}

function lockPresentation(
  reason: SecurityFeature["lockReason"],
): {button: string; hint: string; chipTone: string; chipIcon: ComponentType<{className?: string}>} {
  if (reason === "VERIFIED_REQUIRED") {
    return {
      button: "Verify to unlock",
      hint: "Verify domain ownership (DNS TXT) to unlock this scan.",
      chipTone: "bg-amber-500/10 text-amber-300 ring-amber-400/30",
      chipIcon: Lock,
    };
  }
  if (reason === "NOT_YET_AVAILABLE") {
    return {
      button: "Coming soon",
      hint: "Shipping soon.",
      chipTone: "bg-slate-700/40 text-slate-300 ring-slate-600/40",
      chipIcon: Clock,
    };
  }
  return {
    button: "Unavailable",
    hint: "This scan is not available right now.",
    chipTone: "bg-slate-700/40 text-slate-300 ring-slate-600/40",
    chipIcon: Lock,
  };
}
