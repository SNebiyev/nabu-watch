/**
 * Centralized API client with automatic JWT attachment and 401/403 interception.
 *
 * Design notes
 * ────────────────────────────────────────────────────────────────────────────
 * Token read is intentionally duplicated from authService.ts (a few lines) to
 * avoid a circular import: authService → apiClient → authService.
 *
 * All application fetch calls should go through `apiClient`.
 * Auth-endpoint calls (login, change-password) that expect a 401 as a normal
 * "wrong password" response should pass `skipAuthRedirect: true`.
 */

const TOKEN_COOKIE = "nabu_token";
const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "";

/**
 * Paths where a 401/403 response is expected and must NOT trigger a redirect.
 * These are auth endpoints where an error response is part of normal flow
 * (e.g. wrong password → 401 that the UI should surface as an error message).
 */
const AUTH_PATHS = ["/api/v1/auth/login", "/api/v1/auth/change-password"];

// ── Token helpers (client-side only) ─────────────────────────────────────────

function readToken(): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie
    .split("; ")
    .find((row) => row.startsWith(`${TOKEN_COOKIE}=`));
  // substring instead of split("=")[1] — avoids truncating values with "=" in them
  return match ? match.substring(TOKEN_COOKIE.length + 1) : null;
}

function clearToken(): void {
  if (typeof document === "undefined") return;
  document.cookie = `${TOKEN_COOKIE}=; path=/; max-age=0; SameSite=Lax`;
}

// ── Session expiry error ──────────────────────────────────────────────────────

/**
 * Thrown by `apiClient` after it triggers the login redirect.
 * Callers may catch it to abort further processing gracefully, but they don't
 * need to — the page navigation is already in-flight and will unmount the tree.
 */
export class SessionExpiredError extends Error {
  constructor() {
    super("Session expired");
    this.name = "SessionExpiredError";
  }
}

/**
 * Thrown by `apiClient` when the server returns HTTP 429 (rate limit / domain cooldown).
 * Callers should surface a toast or inline message and NOT redirect to login.
 */
export class RateLimitError extends Error {
    retryAfter: number;
    errorCode: string;
    /** Human-readable retry message from the server (e.g. "Retry in 23 minutes."). */
    detail: string | null;

    constructor(retryAfter: number, errorCode: string, detail: string | null = null) {
        super(detail ?? "Rate limit exceeded");
        this.name = "RateLimitError";
        this.retryAfter = retryAfter;
        this.errorCode = errorCode;
        this.detail = detail;
    }
}

/**
 * Thrown by `apiClient` when the server returns HTTP 403 with
 * `errorCode: "MFA_CHALLENGE_REQUIRED"` or `"MFA_INVALID"`.
 * Callers should show the TOTP challenge modal rather than redirecting to login.
 */
export class MfaChallengeError extends Error {
    constructor() {
        super("MFA step-up required");
        this.name = "MfaChallengeError";
    }
}

/**
 * Clears the stored JWT and navigates to `/login?error=session_expired`.
 *
 * Uses `window.location.replace` so the expired page is not added to browser
 * history (pressing Back won't return to the broken state).
 *
 * Declared as `never` so TypeScript knows callers can treat it as a terminal
 * operation when catching the thrown `SessionExpiredError`.
 */
export function redirectToLogin(reason = "session_expired"): never {
  clearToken();
  if (typeof window !== "undefined") {
    console.log(`[apiClient] Redirecting due to: ${reason}`);
    window.location.replace(`/login?error=${reason}`);
  }
  throw new SessionExpiredError();
}

// ── Main client ───────────────────────────────────────────────────────────────

export interface ApiClientOptions extends RequestInit {
  /**
   * When `true`, 401 and 403 responses are returned to the caller without
   * triggering a redirect.  Use this for auth endpoints where 401 is a
   * legitimate "wrong credentials" signal that the UI should display.
   *
   * @default false
   */
  skipAuthRedirect?: boolean;
}

/**
 * Drop-in replacement for `fetch` that:
 *  1. Reads the JWT from the cookie and attaches it as `Authorization: Bearer`.
 *  2. On a **401** or **403** response, clears the token and redirects to
 *     `/login?error=session_expired` — unless `skipAuthRedirect` is `true`
 *     OR the path is one of the known auth endpoints (AUTH_PATHS).
 *
 * @param path   API path relative to `NEXT_PUBLIC_API_URL` (e.g. `/api/v1/websites`)
 * @param options Standard `RequestInit` plus the `skipAuthRedirect` flag
 */
export async function apiClient(
  path: string,
  options: ApiClientOptions = {}
): Promise<Response> {
  const { skipAuthRedirect = false, headers: extraHeaders, ...rest } = options;

  const token = readToken();
  const isAuthEndpoint = AUTH_PATHS.some((p) => path.includes(p));

  console.log(
    `[apiClient] ${options.method ?? "GET"} ${path} | token_found=${token ? "yes" : "no"}`
  );

  const authHeader: Record<string, string> = token
    ? { Authorization: `Bearer ${token}` }
    : {};

  const response = await fetch(`${API_URL}${path}`, {
    ...rest,
    headers: {
      ...authHeader,
      ...(extraHeaders as Record<string, string> | undefined),
    },
  });

  console.log(
    `[apiClient] ${options.method ?? "GET"} ${path} | status=${response.status}`
  );

    // ── Rate limit (429) — throw instead of redirecting ──────────────────────
    if (response.status === 429) {
        const retryAfter = parseInt(response.headers.get("Retry-After") ?? "60", 10);
        const body = await response
            .clone()
            .json()
            .catch(() => ({}));
        const detail: string | null = body?.detail ?? null;
        throw new RateLimitError(retryAfter, body?.errorCode ?? "RATE_LIMIT_EXCEEDED", detail);
    }

    // ── MFA step-up (403 with specific errorCode) ───────
    if (response.status === 403 && !skipAuthRedirect && !isAuthEndpoint) {
        const body = await response
            .clone()
            .json()
            .catch(() => null);
        if (
            body?.errorCode === "MFA_CHALLENGE_REQUIRED" ||
            body?.errorCode === "MFA_INVALID"
        ) {
            throw new MfaChallengeError();
        }
    }

  // Redirect on auth failure UNLESS:
  //  (a) the caller set skipAuthRedirect=true, OR
  //  (b) this is an auth endpoint where 401 is a normal "bad credentials" signal
  if (
    !skipAuthRedirect &&
    !isAuthEndpoint &&
    (response.status === 401 || response.status === 403)
  ) {
    // redirectToLogin throws SessionExpiredError — no code below this line runs
    redirectToLogin();
  }

  return response;
}
