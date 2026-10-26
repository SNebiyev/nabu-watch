import {type ClassValue, clsx} from "clsx";
import {twMerge} from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * Parses a Spring Boot ProblemDetail / RFC 7807 error response and returns
 * the human-readable message string.
 *
 * Priority: `detail` → `message` → `errors[0]` → raw text → fallback.
 * Never leaks raw JSON to the UI.
 */
export async function extractErrorMessage(
  res: Response,
  fallback = "Something went wrong"
): Promise<string> {
  const text = await res.text();
  if (!text) return fallback;
  try {
    const body = JSON.parse(text);
    if (typeof body.detail === "string" && body.detail) return body.detail;
    if (typeof body.message === "string" && body.message) return body.message;
    if (Array.isArray(body.errors) && body.errors.length > 0)
      return String(body.errors[0]);
  } catch {
    // not JSON — return as-is if it looks like a plain message
  }
  return text || fallback;
}

export function formatRelativeTime(dateStr: string | null): string {
  if (!dateStr) return "Never";
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  if (diffMins < 1) return "Just now";
  if (diffMins < 60) return `${diffMins}m ago`;
  const diffHours = Math.floor(diffMins / 60);
  if (diffHours < 24) return `${diffHours}h ago`;
  const diffDays = Math.floor(diffHours / 24);
  return `${diffDays}d ago`;
}

/**
 * Formats a future date as a countdown string.
 * Returns null if the date is in the past or invalid.
 */
export function formatCountdown(dateStr: string | null | undefined): string | null {
    if (!dateStr) return null;
    const target = new Date(dateStr).getTime();
    const now = Date.now();
    const diffMs = target - now;
    if (diffMs <= 0) return null;
    const totalMins = Math.floor(diffMs / 60000);
    if (totalMins < 1) return "<1m";
    const hours = Math.floor(totalMins / 60);
    const mins = totalMins % 60;
    if (hours >= 24) {
        const days = Math.floor(hours / 24);
        const remHours = hours % 24;
        return remHours > 0 ? `${days}d ${remHours}h` : `${days}d`;
    }
    if (hours > 0) return mins > 0 ? `${hours}h ${mins}m` : `${hours}h`;
    return `${mins}m`;
}

/** Severity badge CSS classes, shared across admin pages. */
export const SEVERITY_STYLES: Record<string, string> = {
    INFO: "bg-sky-500/15 text-sky-400 border-sky-500/30",
    LOW: "bg-green-500/15 text-green-400 border-green-500/30",
    MEDIUM: "bg-amber-500/15 text-amber-400 border-amber-500/30",
    HIGH: "bg-orange-500/15 text-orange-400 border-orange-500/30",
    CRITICAL: "bg-red-500/15 text-red-400 border-red-500/30",
};

/**
 * Returns the URL as-is if it parses and uses http(s); otherwise returns
 * {@code null}. Use this to guard every {@code <a href=...>} that renders a
 * user-supplied URL so a stored {@code javascript:} payload cannot become
 * one-click XSS.
 */
export function safeExternalUrl(url: string | null | undefined): string | null {
    if (!url) return null;
    try {
        const parsed = new URL(url);
        return (parsed.protocol === "http:" || parsed.protocol === "https:") ? url : null;
    } catch {
        return null;
    }
}

/** Formats an ISO datetime string for display in admin tables. */
export function formatAdminDate(iso: string): string {
    return new Date(iso).toLocaleString(undefined, {
        year: "numeric", month: "short", day: "numeric",
        hour: "2-digit", minute: "2-digit",
    });
}
