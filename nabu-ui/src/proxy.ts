import {type NextRequest, NextResponse} from "next/server";

/**
 * Next.js Edge Middleware (proxy.ts).
 *
 * Provides server-side route protection BEFORE any page is rendered.
 * This is the primary auth gate — `apiClient`'s 401 redirect is a secondary
 * client-side fallback for expired tokens discovered during API calls.
 *
 * Route rules
 * ─────────────────────────────────────────────────────────────────────────────
 * PUBLIC_PATHS  → always accessible (no token check)
 * All others    → require a valid, non-expired JWT in the `nabu_token` cookie
 *
 * Possible redirects
 * ─────────────────────────────────────────────────────────────────────────────
 * No token           → /login?redirect=<path>
 * Expired token      → /login?redirect=<path>&error=session_expired
 * pwdChangeRequired  → /auth/change-password  (unless already there)
 * Non-admin on /admin → /dashboard
 * Logged in on /login → /dashboard (or /admin/overview for admins)
 */

const TOKEN_COOKIE = "nabu_token";

const PUBLIC_PATHS = ["/login", "/auth/callback", "/onboarding", "/register", "/verify-email", "/forgot-password", "/reset-password", "/u/", "/tools", "/site.webmanifest", "/sitemap.xml", "/robots.txt", "/apple-icon", "/opengraph-image"];

// ── JWT helpers (Edge-safe — no Node.js crypto) ───────────────────────────────

interface JwtClaims {
  sub?: string;
  role?: string;
  pwdChangeRequired?: boolean;
  exp?: number;
}

/**
 * Decodes the JWT payload without verifying the signature.
 * The Spring Security filter chain is the authoritative verifier;
 * here we only need claims for UX routing decisions.
 */
function decodeJwtPayload(token: string): JwtClaims | null {
  try {
    const [, payload] = token.split(".");
    if (!payload) return null;
    // base64url → base64 (pad to multiple of 4 if needed, swap URL-safe chars)
    const padded = payload.padEnd(
      payload.length + ((4 - (payload.length % 4)) % 4),
      "="
    );
    const json = atob(padded.replace(/-/g, "+").replace(/_/g, "/"));
    return JSON.parse(json) as JwtClaims;
  } catch {
    return null;
  }
}

function isExpired(claims: JwtClaims | null): boolean {
  if (!claims?.exp) return false; // no exp claim → treat as valid
  return Math.floor(Date.now() / 1000) >= claims.exp;
}

// ── Middleware logic ──────────────────────────────────────────────────────────

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  const rawToken = request.cookies.get(TOKEN_COOKIE)?.value ?? null;
  const tokenFound = rawToken !== null && rawToken.length > 0;

  const isPublic =
    pathname === "/" ||
      pathname.startsWith("/api/") ||
    PUBLIC_PATHS.some((p) => pathname.startsWith(p));

  console.log(
    `[Proxy] ${pathname} | token_found=${tokenFound ? "yes" : "no"}`
  );

  // ── Unauthenticated request to a protected route ──────────────────────────
  if (!tokenFound && !isPublic) {
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("redirect", pathname);
    console.log(`[Proxy] Redirecting due to: missing token | target=${pathname}`);
    return NextResponse.redirect(loginUrl);
  }

  // ── Decode and validate the token ─────────────────────────────────────────
  if (tokenFound) {
    const claims = decodeJwtPayload(rawToken!);

    // Expired token — send to login with the session_expired banner.
    // If the user is already on a public route (e.g. /login), let them through
    // so we don't create an infinite redirect loop.
    if (isExpired(claims)) {
      if (isPublic) {
        // Drop the dead cookie so the browser arrives clean, then let the
        // public page render normally.
        const passthrough = NextResponse.next();
        passthrough.cookies.delete(TOKEN_COOKIE);
        return passthrough;
      }
      const loginUrl = new URL("/login", request.url);
      loginUrl.searchParams.set("redirect", pathname);
      loginUrl.searchParams.set("error", "session_expired");
      console.log(`[Proxy] Redirecting due to: expired token | target=${pathname}`);
      const response = NextResponse.redirect(loginUrl);
      response.cookies.delete(TOKEN_COOKIE);
      return response;
    }

    // Already authenticated — don't show login page again
    if (pathname === "/login") {
      const dest = claims?.role === "ADMIN" ? "/admin/overview" : "/dashboard";
      console.log(`[Proxy] Logged-in user on /login → redirecting to ${dest}`);
      return NextResponse.redirect(new URL(dest, request.url));
    }

    // Password change required — gate everything except the change-password page
    if (
      claims?.pwdChangeRequired === true &&
      pathname !== "/auth/change-password"
    ) {
      console.log(`[Proxy] Redirecting due to: pwdChangeRequired`);
      return NextResponse.redirect(
        new URL("/auth/change-password", request.url)
      );
    }

    // Password already changed — don't let the user revisit change-password
    if (!claims?.pwdChangeRequired && pathname === "/auth/change-password") {
      const dest =
        claims?.role === "ADMIN" ? "/admin/overview" : "/dashboard";
      console.log(`[Proxy] pwdChange complete → redirecting to ${dest}`);
      return NextResponse.redirect(new URL(dest, request.url));
    }

    // Admin routes — require the ADMIN role
    if (pathname.startsWith("/admin") && claims?.role !== "ADMIN") {
      console.log(
        `[Proxy] Redirecting due to: insufficient role (${claims?.role}) for /admin`
      );
      return NextResponse.redirect(new URL("/dashboard", request.url));
    }
  }

  return NextResponse.next();
}

// ── Matcher ───────────────────────────────────────────────────────────────────

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|.*\\.svg).*)"],
};
