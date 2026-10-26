import {extractErrorMessage} from "@/lib/utils";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const TOKEN_COOKIE = "nabu_token";

// ── Token payload (JWT claims) ────────────────────────────────────────────────

export interface TokenPayload {
  sub: string;               // user email
  role: "USER" | "ADMIN";
  /** Only present in the token when true — omitted when false to keep tokens compact. */
  pwdChangeRequired?: true;
  iat: number;
  exp: number;
}

// ── API response shapes ───────────────────────────────────────────────────────

/** Shape returned by GET /api/v1/auth/me. */
export interface MeResponse {
    id: string;
    email: string;
    fullName: string;
    role: "USER" | "ADMIN";
    /** True until the user adds their first website (drives the onboarding wizard). */
    firstLogin: boolean;
    /** True once the user has clicked the email verification link. */
    emailVerified: boolean;
    currentOrganizationId: string | null;
    organizations: OrgInfo[];
    verificationStatus: "UNVERIFIED" | "VERIFIED" | "VERIFIED_PENDING";
}

export interface OrgInfo {
    id: string;
    name: string;
    role: "OWNER" | "MEMBER";
}

/** Shape returned by POST /api/v1/auth/login and POST /api/v1/auth/change-password. */
export interface AuthResponse {
  token: string;
  tokenType: string;
  expiresAt: string;
  userId: string;
  role: "USER" | "ADMIN";
  requirePasswordChange: boolean;
}

export interface LoginCredentials {
  email: string;
  password: string;
}

// ── Cookie helpers ────────────────────────────────────────────────────────────

const COOKIE_MAX_AGE = 60 * 60 * 24 * 7; // 7 days

/**
 * Writes the JWT into a cookie that is readable both client-side
 * (document.cookie) and server-side (Next.js middleware via request.cookies).
 *
 * SameSite=Lax  — cookie is sent on same-origin navigations and top-level
 *                  cross-site GETs (safe default; does NOT block the middleware).
 * Secure        — added automatically when the page is served over HTTPS so
 *                  the cookie is never sent over plain HTTP in production.
 *                  Omitted on localhost/HTTP to keep local development working.
 */
export function storeToken(token: string): void {
  const isHttps =
    typeof window !== "undefined" && window.location.protocol === "https:";
  const parts = [
    `${TOKEN_COOKIE}=${token}`,
    "path=/",
    `max-age=${COOKIE_MAX_AGE}`,
    "SameSite=Lax",
    ...(isHttps ? ["Secure"] : []),
  ];
  document.cookie = parts.join("; ");
  console.log(
    `[authService] Token stored | cookie_name=${TOKEN_COOKIE} | secure=${isHttps}`
  );
}

// ── Auth operations ───────────────────────────────────────────────────────────

export class EmailNotVerifiedError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "EmailNotVerifiedError";
    }
}

export async function login(credentials: LoginCredentials): Promise<AuthResponse> {
  const res = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(credentials),
  });

  if (!res.ok) {
      const text = await res.text();
      let errorCode: string | undefined;
      let detail: string | undefined;
      try {
          const body = JSON.parse(text);
          errorCode = body.errorCode;
          detail = body.detail ?? body.message;
      } catch { /* not JSON */
      }

      if (errorCode === "EMAIL_NOT_VERIFIED" || res.status === 403) {
          throw new EmailNotVerifiedError(
              detail ?? "Please verify your email before signing in.");
      }
      throw new Error(detail ?? (text || "Invalid credentials"));
  }

  const data: AuthResponse = await res.json();
  storeToken(data.token);
  return data;
}

/**
 * Calls POST /api/v1/auth/change-password with the current and new passwords.
 * On success the server issues a fresh JWT without the `pwdChangeRequired` claim,
 * which is stored in place of the old token.
 */
export async function changePassword(
  currentPassword: string,
  newPassword: string
): Promise<AuthResponse> {
  const res = await fetch(`${API_URL}/api/v1/auth/change-password`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
    },
    body: JSON.stringify({ currentPassword, newPassword }),
  });

  if (!res.ok) {
    throw new Error(await extractErrorMessage(res, "Failed to change password"));
  }

  const data: AuthResponse = await res.json();
  storeToken(data.token); // replace old token — new one has no pwdChangeRequired
  return data;
}

/**
 * Switches the active organization and stores the new JWT.
 * Returns the new AuthResponse with the re-issued token.
 */
export async function switchOrganization(organizationId: string): Promise<AuthResponse> {
    const res = await fetch(`${API_URL}/api/v1/auth/switch-org`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            ...authHeaders(),
        },
        body: JSON.stringify({organizationId}),
    });

    if (!res.ok) {
        throw new Error(await extractErrorMessage(res, "Failed to switch organization"));
    }

    const data: AuthResponse = await res.json();
    storeToken(data.token);
    return data;
}

export function logout(): void {
  document.cookie = `${TOKEN_COOKIE}=; path=/; max-age=0; SameSite=Lax`;
  window.location.href = "/login";
}

// ── Token accessors ───────────────────────────────────────────────────────────

export function getToken(): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie
    .split("; ")
    .find((row) => row.startsWith(`${TOKEN_COOKIE}=`));
  // Use substring instead of split("=")[1] — split would truncate values
  // that happen to contain "=" (base64 padding), which is fragile.
  return match ? match.substring(TOKEN_COOKIE.length + 1) : null;
}

export function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/**
 * Fetches the authenticated user's full profile.
 * Requires a valid JWT in the cookie.
 */
export async function fetchMe(): Promise<MeResponse> {
    const res = await fetch(`${API_URL}/api/v1/auth/me`, {
        headers: authHeaders(),
    });
    if (!res.ok) {
        throw new Error(`Failed to fetch user profile: ${res.status}`);
    }
    return res.json() as Promise<MeResponse>;
}

/**
 * Decodes the JWT payload without verifying the signature.
 * Verification happens server-side; here we only need claims for UI rendering.
 * JWT uses base64url — replace chars before passing to atob.
 */
export function getTokenPayload(): TokenPayload | null {
  const token = getToken();
  if (!token) return null;
  try {
    const [, payload] = token.split(".");
    const base64 = payload.replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(base64)) as TokenPayload;
  } catch {
    return null;
  }
}
