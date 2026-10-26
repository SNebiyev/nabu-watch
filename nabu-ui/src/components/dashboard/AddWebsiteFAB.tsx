"use client";

import {useEffect, useMemo, useRef, useState} from "react";
import {createPortal} from "react-dom";
import {Check, ChevronDown, Globe, Loader2, Plus, Settings2, Wand2, X,} from "lucide-react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {apiClient, RateLimitError} from "@/lib/apiClient";
import {extractErrorMessage} from "@/lib/utils";

// ── Region config ─────────────────────────────────────────────────────────────

/**
 * Sentinel values: not real ISO codes, used only by the form.
 *  AUTO   → resolve from TLD; falls back to "US" if TLD is unknown
 *  CUSTOM → user picks monitor sub-types manually
 */
const REGION_AUTO   = "AUTO";
const REGION_CUSTOM = "CUSTOM";
const RESPONSIBLE_USE_URL =
  "https://github.com/SNebiyev/nabu-watch/blob/main/docs/RESPONSIBLE_USE.md";

const KNOWN_REGIONS = [
  { value: "DE", label: "Germany",        flag: "🇩🇪" },
  { value: "AT", label: "Austria",        flag: "🇦🇹" },
  { value: "CH", label: "Switzerland",    flag: "🇨🇭" },
  { value: "FR", label: "France",         flag: "🇫🇷" },
  { value: "GB", label: "United Kingdom", flag: "🇬🇧" },
  { value: "US", label: "United States",  flag: "🇺🇸" },
  {value: "CA", label: "Canada", flag: "🇨🇦"},
  { value: "ES", label: "Spain",          flag: "🇪🇸" },
  { value: "IT", label: "Italy",          flag: "🇮🇹" },
  { value: "NL", label: "Netherlands",    flag: "🇳🇱" },
  { value: "PL", label: "Poland",         flag: "🇵🇱" },
  {value: "BR", label: "Brazil", flag: "🇧🇷"},
] as const;

/**
 * ccTLD → ISO region code mapping used by TLD auto-detection.
 * Extend this list as new regions are added to the backend MarketRegistry.
 */
const TLD_TO_REGION: Record<string, string> = {
  de: "DE",
  at: "AT",
  ch: "CH",
  fr: "FR",
  uk: "GB",
  gb: "GB",
  es: "ES",
  it: "IT",
  nl: "NL",
  pl: "PL",
  us: "US",
  ca: "CA",
  br: "BR",
  az: "AZ",   // Azerbaijan — GLOBAL fallback (no dedicated MarketRegistry entry)
  au: "AU",   // Australia — GLOBAL fallback
  jp: "JP",   // Japan — GLOBAL fallback
};

/**
 * Infer a region code from the TLD of the given URL string.
 * Returns null when the URL cannot be parsed or the TLD is not mapped.
 */
function detectRegionFromUrl(raw: string): string | null {
  if (!raw.trim()) return null;
  try {
    const withProto = /^https?:\/\//i.test(raw.trim()) ? raw.trim() : `https://${raw.trim()}`;
    const hostname = new URL(withProto).hostname.toLowerCase();
    const parts = hostname.split(".");
    // Two-part ccTLDs: .co.uk, .com.br, .co.jp, .com.au, etc.
    if (parts.length >= 3) {
      const last2 = parts[parts.length - 2] + "." + parts[parts.length - 1];
      if (last2 === "co.uk" || last2 === "co.jp") return TLD_TO_REGION[parts[parts.length - 1]] ?? null;
    }
    const tld = parts[parts.length - 1];
    return tld ? (TLD_TO_REGION[tld] ?? null) : null;
  } catch {
    return null;
  }
}

// ── Monitor sub-type config ───────────────────────────────────────────────────

const ALL_MONITOR_SUBTYPES = [
  {
    value: "PRIVACY_POLICY",
    label: "Privacy Policy",
    desc: "Checks that a privacy / data-protection page exists",
  },
  {
    value: "TERMS_OF_SERVICE",
    label: "Terms of Service",
    desc: "Checks that Terms of Service / Use are reachable",
  },
  {
    value: "IMPRESSUM",
    label: "Impressum",
    desc: "Required for DE / AT / CH websites",
  },
  {
    value: "COOKIE_POLICY",
    label: "Cookie Policy",
    desc: "Checks for a cookie-consent / cookie-policy page",
  },
  {
    value: "GDPR_REQUEST",
    label: "GDPR Request",
    desc: "Checks for a data-subject access request page",
  },
];

// ── URL helpers ───────────────────────────────────────────────────────────────

/** Full normalisation for the API payload. */
function normalizeUrlForPayload(raw: string): string {
  let url = raw.trim().replace(/\/+$/, "");
  if (url && !/^https?:\/\//i.test(url)) {
    url = "https://" + url;
  }
  return url;
}

// ── Region dropdown ───────────────────────────────────────────────────────────

interface RegionSelectProps {
  value: string;
  onChange: (v: string) => void;
  /** Live TLD-detected region (may be null). */
  detectedRegion: string | null;
}

/**
 * Portal-based region dropdown.
 *
 * Why a portal?
 * The modal panel has `overflow-hidden` (required for rounded-2xl corner
 * clipping) and the inner scroll div has `overflow-y-auto`. Either of these
 * creates an overflow context that clips absolutely-positioned children
 * regardless of z-index. Rendering the list via `createPortal` into
 * `document.body` escapes both contexts entirely.
 *
 * Positioning uses `getBoundingClientRect()` → `position: fixed` so the list
 * tracks the trigger's viewport-relative location. The dropdown auto-flips
 * upward when there isn't enough space below.
 */
function RegionSelect({ value, onChange, detectedRegion }: RegionSelectProps) {
  const [open, setOpen]                         = useState(false);
  const [dropStyle, setDropStyle]               = useState<React.CSSProperties>({});
  const [regionSearch, setRegionSearch] = useState("");
  const triggerRef                              = useRef<HTMLButtonElement>(null);
  const listRef                                 = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  const knownEntry    = KNOWN_REGIONS.find((r) => r.value === value);
  const detectedEntry = detectedRegion
    ? KNOWN_REGIONS.find((r) => r.value === detectedRegion)
    : null;

  // ── Outside-click: close when clicking outside BOTH trigger and list ──────
  useEffect(() => {
    if (!open) return;
    function handle(e: MouseEvent) {
      const target = e.target as Node;
      if (triggerRef.current?.contains(target)) return;
      if (listRef.current?.contains(target)) return;
      setOpen(false);
    }
    document.addEventListener("mousedown", handle);
    return () => document.removeEventListener("mousedown", handle);
  }, [open]);

  // ── Close/reposition on scroll and resize ────────────────────────────────
  useEffect(() => {
    if (!open) return;

    const handleScroll = (e: Event) => {
      // Scrolling INSIDE the dropdown list is normal — don't close.
      // Only close when the scroll happens outside (e.g. the modal body scrolls).
      if (listRef.current?.contains(e.target as Node)) return;
      setOpen(false);
    };

    // On resize, recalculate position via rAF rather than simply closing.
    // rAF ensures we read layout after the browser has applied the resize,
    // avoiding stale getBoundingClientRect values.
    const handleResize = () => {
      requestAnimationFrame(() => {
        if (!triggerRef.current) { setOpen(false); return; }
        const rect        = triggerRef.current.getBoundingClientRect();
        const GAP         = 4;
        const MAX_H       = 300;
        const spaceBelow  = window.innerHeight - rect.bottom - GAP;
        const openUpward  = spaceBelow < MAX_H && rect.top > MAX_H;
        setDropStyle({
          position : "fixed",
          left     : rect.left,
          width    : rect.width,
          zIndex   : 9999,
          ...(openUpward
            ? { bottom: window.innerHeight - rect.top + GAP }
            : { top   : rect.bottom + GAP }),
        });
      });
    };

    window.addEventListener("scroll", handleScroll, { capture: true });
    window.addEventListener("resize", handleResize);
    return () => {
      window.removeEventListener("scroll", handleScroll, { capture: true });
      window.removeEventListener("resize", handleResize);
    };
  }, [open]);

  function handleToggle() {
    if (!triggerRef.current) {
      setOpen((v) => !v);
      return;
    }

    if (open) {
      setOpen(false);
      return;
    }

    // Snapshot the trigger position at the moment of opening
    const rect           = triggerRef.current.getBoundingClientRect();
    const GAP            = 4;
    const DROPDOWN_MAX_H = 300;
    const spaceBelow     = window.innerHeight - rect.bottom - GAP;
    const openUpward     = spaceBelow < DROPDOWN_MAX_H && rect.top > DROPDOWN_MAX_H;

    setDropStyle({
      position : "fixed",
      left     : rect.left,
      width    : rect.width,
      zIndex   : 9999,
      ...(openUpward
        ? { bottom: window.innerHeight - rect.top + GAP }
        : { top: rect.bottom + GAP }),
    });

    setOpen(true);
    setRegionSearch("");
    // Focus search input after portal renders
    requestAnimationFrame(() => searchInputRef.current?.focus());
  }

  // Filter regions by search query (matches label, code, or flag)
  const filteredRegions = regionSearch.trim()
      ? KNOWN_REGIONS.filter((r) => {
        const q = regionSearch.toLowerCase();
        return r.label.toLowerCase().includes(q) || r.value.toLowerCase().includes(q);
      })
      : KNOWN_REGIONS;

  // ── Portal list ────────────────────────────────────────────────────────────
  const list = open
    ? createPortal(
        <div
          ref={listRef}
          style={dropStyle}
          className="overflow-hidden rounded-xl border border-border bg-card shadow-2xl"
        >
          {/* Search input */}
          <div className="border-b border-border px-2 py-1.5">
            <input
                ref={searchInputRef}
                type="text"
                value={regionSearch}
                onChange={(e) => setRegionSearch(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Escape") {
                    setOpen(false);
                    return;
                  }
                  if (e.key === "Enter" && filteredRegions.length === 1) {
                    onChange(filteredRegions[0].value);
                    setOpen(false);
                  }
                }}
                placeholder="Type to search…"
                className="h-7 w-full rounded-lg bg-secondary/50 px-2.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none"
            />
          </div>
          {/* max-h + overflow-y-auto makes the list scrollable on small viewports */}
          <div className="max-h-[240px] overflow-y-auto py-1">
            {!regionSearch.trim() && (
                <>
                  <DropdownItem
                      flag="✨"
                      label="Auto-detect"
                      description="Inferred from the URL's TLD"
                      code={null}
                      selected={value === REGION_AUTO}
                      onClick={() => {
                        onChange(REGION_AUTO);
                        setOpen(false);
                      }}
                  />
                  <Divider/>
                </>
            )}

            {filteredRegions.length === 0 ? (
                <p className="px-3 py-4 text-center text-xs text-muted-foreground">No regions match</p>
            ) : (
                filteredRegions.map((r) => (
                    <DropdownItem
                        key={r.value}
                        flag={r.flag}
                        label={r.label}
                        description={null}
                        code={r.value}
                        selected={value === r.value}
                        onClick={() => {
                          onChange(r.value);
                          setOpen(false);
                        }}
                    />
                ))
            )}

            {!regionSearch.trim() && (
                <>
                  <Divider/>
                  <DropdownItem
                      flag="⚙️"
                      label="Custom / Manual"
                      description="Choose monitor types individually"
                      code={null}
                      selected={value === REGION_CUSTOM}
                      onClick={() => {
                        onChange(REGION_CUSTOM);
                        setOpen(false);
                      }}
                  />
                </>
            )}
          </div>
        </div>,
        document.body
      )
    : null;

  return (
    // No position:relative needed — the list is portaled, not a child
    <div>
      <button
        ref={triggerRef}
        type="button"
        onClick={handleToggle}
        className="flex h-10 w-full items-center justify-between gap-2 rounded-xl border border-border bg-card px-3 text-sm text-[--foreground] transition-colors hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[--primary]/60"
      >
        <span className="flex min-w-0 items-center gap-2">
          {value === REGION_AUTO && (
            <>
              <span className="text-base leading-none">✨</span>
              <span className="text-[--foreground]">Auto-detect</span>
              {detectedEntry ? (
                <span className="flex shrink-0 items-center gap-1 rounded-md bg-blue-100 dark:bg-blue-950/60 px-1.5 py-0.5 text-[11px] font-medium text-blue-600 dark:text-blue-400 ring-1 ring-blue-200 dark:ring-blue-900/40">
                  → {detectedEntry.flag} {detectedEntry.value}
                </span>
              ) : (
                <span className="shrink-0 rounded-md bg-secondary px-1.5 py-0.5 text-[11px] text-[--muted-foreground]">
                  no TLD match
                </span>
              )}
            </>
          )}
          {value === REGION_CUSTOM && (
            <>
              <span className="text-base leading-none">⚙️</span>
              <span className="text-[--foreground]">Custom / Manual</span>
            </>
          )}
          {value !== REGION_AUTO && value !== REGION_CUSTOM && knownEntry && (
            <>
              <span className="text-base leading-none">{knownEntry.flag}</span>
              <span className="text-[--foreground]">{knownEntry.label}</span>
              <span className="shrink-0 rounded-md bg-secondary px-1.5 py-0.5 text-[11px] font-medium text-[--muted-foreground]">
                {knownEntry.value}
              </span>
            </>
          )}
        </span>
        <ChevronDown
          className={`h-4 w-4 shrink-0 text-[--muted-foreground] transition-transform ${
            open ? "rotate-180" : ""
          }`}
        />
      </button>

      {list}
    </div>
  );
}

function DropdownItem({
  flag, label, description, code, selected, onClick,
}: {
  flag: string;
  label: string;
  description: string | null;
  code: string | null;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-center gap-2.5 px-3 py-2 text-sm transition-colors hover:bg-secondary"
    >
      <span className="text-base leading-none">{flag}</span>
      <span className="flex-1 text-left text-[--foreground]">{label}</span>
      {code && (
        <span className="shrink-0 text-[11px] text-[--muted-foreground]">{code}</span>
      )}
      {description && !code && (
        <span className="shrink-0 text-[11px] text-[--muted-foreground]">{description}</span>
      )}
      {selected && (
        <Check className="h-3.5 w-3.5 shrink-0 text-[--primary]" />
      )}
    </button>
  );
}

function Divider() {
  return <div className="my-1 border-t border-border" />;
}

// ── Custom monitor picker (checkbox grid) ─────────────────────────────────────

interface MonitorPickerProps {
  selected: string[];
  onChange: (v: string[]) => void;
}

function MonitorPicker({ selected, onChange }: MonitorPickerProps) {
  function toggle(value: string) {
    onChange(
      selected.includes(value)
        ? selected.filter((v) => v !== value)
        : [...selected, value]
    );
  }

  return (
    <div className="space-y-1.5">
      {ALL_MONITOR_SUBTYPES.map((m) => {
        const checked = selected.includes(m.value);
        return (
          <label
            key={m.value}
            onClick={() => toggle(m.value)}
            className={`flex cursor-pointer items-center gap-3 rounded-xl border px-3 py-2.5 transition-colors ${
              checked
                ? "border-blue-300 dark:border-blue-800/60 bg-blue-50 dark:bg-blue-950/25"
                : "border-border bg-card hover:bg-secondary"
            }`}
          >
            {/* Custom checkbox */}
            <div
              className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border transition-colors ${
                checked
                  ? "border-blue-500 bg-blue-500"
                  : "border-border bg-transparent"
              }`}
            >
              {checked && (
                <Check className="h-3 w-3 text-white" strokeWidth={3} />
              )}
            </div>

            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-[--foreground]">
                {m.label}
              </p>
              <p className="text-xs text-[--muted-foreground]">{m.desc}</p>
            </div>
          </label>
        );
      })}
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

interface AddWebsiteFABProps {
  onAdded?: () => void;
}

export function AddWebsiteFAB({onAdded}: AddWebsiteFABProps) {
  const [open, setOpen] = useState(false);
  const [url, setUrl] = useState("");
  const [region, setRegion] = useState<string>(REGION_AUTO);
  const [selectedSubTypes, setSelectedSubTypes] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ownershipAttestation, setOwnershipAttestation] = useState(false);

  // Live TLD detection — recomputed on every URL keystroke
  const autoDetectedRegion = useMemo(() => detectRegionFromUrl(url), [url]);

  // The actual region code sent to the API when region == AUTO
  const resolvedAutoRegion = autoDetectedRegion ?? "US";

  // Body scroll lock while modal is open
  useEffect(() => {
    document.body.style.overflow = open ? "hidden" : "";
    return () => { document.body.style.overflow = ""; };
  }, [open]);

  function resetForm() {
    setUrl("");
    setRegion(REGION_AUTO);
    setSelectedSubTypes([]);
    setOwnershipAttestation(false);
    setError(null);
  }

  function handleClose() {
    setOpen(false);
    resetForm();
  }

  function handleUrlChange(e: React.ChangeEvent<HTMLInputElement>) {
    // Store the raw value while typing — trailing slashes are stripped at
    // submit time by normalizeUrlForPayload, so stripping here would prevent
    // the user from ever typing '/' (it would vanish on each keystroke).
    setUrl(e.target.value);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (region === REGION_CUSTOM && selectedSubTypes.length === 0) {
      setError("Select at least one monitor type for custom mode.");
      return;
    }

    setLoading(true);
    try {
      const effectiveRegion =
        region === REGION_AUTO ? resolvedAutoRegion : region;

      const payload: Record<string, unknown> = {
        url: normalizeUrlForPayload(url),
        region: effectiveRegion,
        ownershipAttestation: true,
        // Only include selectedMonitors when CUSTOM — backend ignores it otherwise
        ...(region === REGION_CUSTOM && {
          selectedMonitors: selectedSubTypes,
        }),
      };

      console.log("[AddWebsite] payload:", JSON.stringify(payload));
      const res = await apiClient("/api/v1/websites", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (res.status === 409) {
        setError("This website is already being monitored in your organization.");
        return;
      }
      if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to add website"));

      handleClose();
      onAdded?.();
    } catch (err) {
      if (err instanceof RateLimitError && err.errorCode === "PENDING_VERIFICATION_LIMIT") {
        setError(
          err.detail ??
            "You have too many websites waiting for domain verification. Verify an existing website or wait before adding another."
        );
      } else {
        setError(err instanceof Error ? err.message : "Something went wrong");
      }
    } finally {
      setLoading(false);
    }
  }

  // Hint line below region dropdown — memoized so KNOWN_REGIONS.find() only
  // runs when the relevant inputs change, not on every render.
  const regionHint = useMemo(() => {
    if (region !== REGION_AUTO) return null;
    if (!autoDetectedRegion) return "No TLD match — will default to US compliance rules";
    const entry = KNOWN_REGIONS.find((r) => r.value === autoDetectedRegion);
    return `TLD match — using ${entry?.flag ?? "🌐"} ${autoDetectedRegion} rules`;
  }, [region, autoDetectedRegion]);

  return (
    <>
      <Button onClick={() => setOpen(true)}>
        <Plus className="h-4 w-4"/>
        Add Website
      </Button>

      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center px-4">
          {/* Backdrop — plain dim, no blur (blur here composites the entire page behind it) */}
          <div
            className="absolute inset-0 bg-black/72"
            onClick={handleClose}
          />

          {/* Modal panel — solid near-opaque background instead of backdrop-blur-xl.
              backdrop-filter on a full-screen overlay is one of the most expensive
              GPU operations in CSS; a solid bg-slate-950/95 is visually equivalent
              and removes the repaint entirely. */}
          <div className="relative w-full max-w-md overflow-hidden rounded-2xl border border-border bg-card shadow-xl">
            <div className="max-h-[90vh] overflow-y-auto p-6">
              {/* Header */}
              <div className="mb-5 flex items-center justify-between">
                <div className="flex items-center gap-2.5">
                  <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-blue-100 dark:bg-blue-950/40 ring-1 ring-blue-200 dark:ring-blue-900/40">
                    <Globe className="h-4 w-4 text-blue-600 dark:text-blue-400" />
                  </div>
                  <div>
                    <h2 className="text-sm font-semibold text-[--foreground]">
                      Add Website
                    </h2>
                    <p className="text-xs text-[--muted-foreground]">
                      Start monitoring SSL &amp; compliance
                    </p>
                  </div>
                </div>
                <button
                  onClick={handleClose}
                  className="flex h-7 w-7 items-center justify-center rounded-lg text-[--muted-foreground] transition-colors hover:bg-secondary hover:text-[--foreground]"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>

              <form onSubmit={handleSubmit} className="space-y-4">
                {/* URL field */}
                <div className="space-y-1.5">
                  <label
                    htmlFor="website-url"
                    className="text-xs font-medium text-[--muted-foreground]"
                  >
                    Website URL
                  </label>
                  <Input
                    id="website-url"
                    type="text"
                    inputMode="url"
                    autoComplete="off"
                    placeholder="example.com or https://example.com"
                    value={url}
                    onChange={handleUrlChange}
                    onPaste={(e) => {
                      e.preventDefault();
                      const pasted = e.clipboardData.getData("text").trim();
                      setUrl(pasted);
                    }}
                    required
                    autoFocus
                  />
                  <p className="text-[11px] text-[--muted-foreground]">
                    Protocol is added and trailing slashes are removed automatically on submit.
                  </p>
                </div>

                {/* Region field */}
                <div className="space-y-1.5">
                  <label className="flex items-center gap-1.5 text-xs font-medium text-[--muted-foreground]">
                    <Wand2 className="h-3 w-3" />
                    Compliance Region
                  </label>
                  <RegionSelect
                    value={region}
                    onChange={(v) => {
                      setRegion(v);
                      // Clear custom selection when leaving CUSTOM mode
                      if (v !== REGION_CUSTOM) setSelectedSubTypes([]);
                    }}
                    detectedRegion={autoDetectedRegion}
                  />
                  {regionHint && (
                    <p className="flex items-center gap-1.5 text-[11px] text-[--muted-foreground]">
                      <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-blue-500" />
                      {regionHint}
                    </p>
                  )}
                  {region !== REGION_CUSTOM && (
                    <p className="text-[11px] text-[--muted-foreground]">
                      Determines which legal pages are checked (GDPR, DSGVO,
                      etc.)
                    </p>
                  )}
                </div>

                {/* Custom monitor picker */}
                {region === REGION_CUSTOM && (
                  <div className="space-y-2">
                    <div className="flex items-center justify-between">
                      <label className="flex items-center gap-1.5 text-xs font-medium text-[--muted-foreground]">
                        <Settings2 className="h-3 w-3" />
                        Monitors to enable
                      </label>
                      <span className="text-[11px] text-[--muted-foreground]">
                        {selectedSubTypes.length} selected
                      </span>
                    </div>
                    <MonitorPicker
                      selected={selectedSubTypes}
                      onChange={setSelectedSubTypes}
                    />
                    <p className="text-[11px] text-[--muted-foreground]">
                      SSL certificate monitoring is always included regardless.
                    </p>
                  </div>
                )}

                {/* Ownership attestation (required, not stored) */}
                <label className="flex items-start gap-2.5 cursor-pointer rounded-xl border border-[--border] bg-[--background]/40 px-3 py-2.5 text-[11px] leading-relaxed text-[--muted-foreground]">
                  <input
                    type="checkbox"
                    checked={ownershipAttestation}
                    onChange={(e) => setOwnershipAttestation(e.target.checked)}
                    className="mt-0.5 h-4 w-4 shrink-0 rounded border-[--border] bg-[--background] text-[--primary] focus:ring-2 focus:ring-[--primary]/40"
                    required
                  />
                  <span>
                    I own this website or have permission to scan it. See the{" "}
                    <a href={RESPONSIBLE_USE_URL} target="_blank" rel="noopener noreferrer"
                       className="text-sky-600 dark:text-sky-400 underline underline-offset-2 hover:opacity-80">
                      responsible use guidelines
                    </a>
                    .
                  </span>
                </label>

                {/* Error banner */}
                {error && (
                  <div className="flex items-start gap-2 rounded-xl border border-red-200 dark:border-red-900/40 bg-red-50 dark:bg-red-950/30 px-3 py-2.5">
                    <span className="mt-0.5 h-1.5 w-1.5 shrink-0 rounded-full bg-red-500 dark:bg-red-400" />
                    <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
                  </div>
                )}

                {/* Actions */}
                <div className="flex gap-2 pt-1">
                  <Button
                    type="button"
                    variant="outline"
                    className="flex-1"
                    onClick={handleClose}
                  >
                    Cancel
                  </Button>
                  <Button type="submit" disabled={loading || !ownershipAttestation} className="flex-1">
                    {loading ? (
                      <>
                        <Loader2 className="h-4 w-4 animate-spin" />
                        Adding…
                      </>
                    ) : (
                      <>
                        <Plus className="h-4 w-4" />
                        Add Website
                      </>
                    )}
                  </Button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
