"use client";

import {Suspense, useState} from "react";
import {useRouter, useSearchParams} from "next/navigation";
import {AlertCircle, CheckCircle2, Eye, EyeOff, Loader2, Mail, Shield} from "lucide-react";
import {EmailNotVerifiedError, fetchMe, login} from "@/lib/authService";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {extractErrorMessage} from "@/lib/utils";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

// ── SVG icons for OAuth providers ─────────────────────────────────────────────

function GoogleIcon() {
    return (
        <svg viewBox="0 0 24 24" className="h-4 w-4 shrink-0" aria-hidden="true">
            <path
                d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"
                fill="#4285F4"
            />
            <path
                d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
                fill="#34A853"
            />
            <path
                d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
                fill="#FBBC05"
            />
            <path
                d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
                fill="#EA4335"
            />
        </svg>
    );
}

function GitHubIcon() {
    return (
        <svg viewBox="0 0 24 24" className="h-4 w-4 shrink-0" aria-hidden="true">
            <path
                fill="currentColor"
                d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"
            />
        </svg>
    );
}

// ── Social login buttons ───────────────────────────────────────────────────────

function SocialLoginButtons() {
    return (
        <div className="grid grid-cols-2 gap-2.5">
            <a
                href={`${API_URL}/oauth2/authorization/google`}
                className="flex items-center justify-center gap-2 rounded-xl border border-[--border] bg-[--card] px-4 py-2.5 text-sm font-medium text-[--foreground] transition-all hover:bg-[--secondary] hover:shadow-sm"
            >
                <GoogleIcon/>
                Google
            </a>
            <a
                href={`${API_URL}/oauth2/authorization/github`}
                className="flex items-center justify-center gap-2 rounded-xl border border-[--border] bg-[--card] px-4 py-2.5 text-sm font-medium text-[--foreground] transition-all hover:bg-[--secondary] hover:shadow-sm"
            >
                <GitHubIcon/>
                GitHub
            </a>
        </div>
    );
}

// ── Password form ─────────────────────────────────────────────────────────────

function PasswordForm({redirect}: { redirect: string }) {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
    const [needsVerification, setNeedsVerification] = useState(false);
    const [resendState, setResendState] = useState<"idle" | "sending" | "sent">("idle");

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
      setNeedsVerification(false);
    setLoading(true);
    try {
      const response = await login({ email, password });
      if (response.requirePasswordChange) {
        router.replace("/auth/change-password");
          return;
      }
        try {
            const me = await fetchMe();
            router.replace(me.firstLogin ? "/onboarding" : redirect);
        } catch {
        router.replace(redirect);
      }
    } catch (err) {
        if (err instanceof EmailNotVerifiedError) {
            setNeedsVerification(true);
            setError(err.message);
        } else {
            setError(err instanceof Error ? err.message : "Invalid email or password");
        }
    } finally {
      setLoading(false);
    }
  }

    async function handleResendVerification() {
        setResendState("sending");
        try {
            await fetch(`${API_URL}/api/v1/auth/resend-verification`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({email}),
            });
            setResendState("sent");
        } catch {
            setResendState("idle");
        }
    }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="space-y-1.5">
          <label htmlFor="email" className="text-xs font-medium text-[--muted-foreground]">
          Email address
        </label>
        <Input
          id="email"
          type="email"
          placeholder="you@example.com"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
      </div>

      <div className="space-y-1.5">
          <label htmlFor="password" className="text-xs font-medium text-[--muted-foreground]">
          Password
        </label>
        <div className="relative">
          <Input
            id="password"
            type={showPassword ? "text" : "password"}
            placeholder="••••••••"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            className="pr-10"
          />
          <button
            type="button"
            onClick={() => setShowPassword((v) => !v)}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-[--muted-foreground] hover:text-[--foreground]"
            tabIndex={-1}
          >
              {showPassword ? <EyeOff className="h-4 w-4"/> : <Eye className="h-4 w-4"/>}
          </button>
        </div>
      </div>

        <div className="flex justify-end">
            <a
                href="/forgot-password"
                className="text-xs text-[--muted-foreground] hover:text-[--primary] cursor-pointer"
            >
                Forgot password?
            </a>
        </div>

        {error && !needsVerification && (
          <div className="flex items-start gap-2.5 rounded-xl border border-red-900/50 bg-red-950/50 px-3 py-2.5">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-400"/>
              <p className="text-sm text-red-400">{error}</p>
        </div>
      )}

        {needsVerification && (
            <div className="rounded-xl border border-amber-800/50 bg-amber-950/40 px-3 py-3 space-y-2.5">
                <div className="flex items-start gap-2.5">
                    <Mail className="mt-0.5 h-4 w-4 shrink-0 text-amber-400"/>
                    <p className="text-sm text-amber-200">{error}</p>
                </div>
                <div className="pl-6">
                    {resendState === "idle" && (
                        <button
                            type="button"
                            onClick={handleResendVerification}
                            className="text-xs font-medium text-amber-300 underline underline-offset-2 hover:text-amber-200"
                        >
                            Resend verification email
                        </button>
                    )}
                    {resendState === "sending" && (
                        <span className="inline-flex items-center gap-1.5 text-xs text-amber-400">
                          <Loader2 className="h-3 w-3 animate-spin"/>Sending...
                      </span>
                    )}
                    {resendState === "sent" && (
                        <span className="text-xs text-green-400">Verification email sent! Check your inbox.</span>
                    )}
                </div>
            </div>
        )}

      <Button type="submit" disabled={loading} className="mt-2 w-full" size="lg">
        {loading ? (
          <>
            <Loader2 className="h-4 w-4 animate-spin" />
            Signing in…
          </>
        ) : (
          "Sign in"
        )}
      </Button>
    </form>
  );
}

// ── Magic-link form ───────────────────────────────────────────────────────────

function MagicLinkForm() {
    const [email, setEmail] = useState("");
    const [loading, setLoading] = useState(false);
    const [sent, setSent] = useState(false);
    const [error, setError] = useState<string | null>(null);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);
        setLoading(true);
        try {
            const res = await fetch(`${API_URL}/api/v1/auth/magic-link`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({email}),
            });
            if (res.status === 404) {
                setError("Magic links are not yet enabled. Please use password sign-in or a social provider above.");
                return;
            }
            if (!res.ok) {
                throw new Error(await extractErrorMessage(res, "Failed to send magic link"));
            }
            setSent(true);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to send magic link");
        } finally {
            setLoading(false);
        }
    }

    if (sent) {
        return (
            <div className="flex flex-col items-center gap-3 py-4 text-center">
                <div
                    className="flex h-12 w-12 items-center justify-center rounded-2xl bg-green-950/60 ring-1 ring-green-800/40">
                    <CheckCircle2 className="h-6 w-6 text-green-400"/>
                </div>
                <div>
                    <p className="font-semibold text-[--foreground]">Check your inbox</p>
                    <p className="mt-1 text-sm text-[--muted-foreground]">
                        We sent a sign-in link to <span className="font-medium text-[--foreground]">{email}</span>.
                        It expires in 15 minutes.
                    </p>
                </div>
                <button
                    onClick={() => {
                        setSent(false);
                        setEmail("");
                    }}
                    className="mt-1 text-xs text-[--muted-foreground] underline underline-offset-2 hover:text-[--foreground]"
                >
                    Use a different email
                </button>
            </div>
        );
    }

    return (
        <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-1.5">
                <label htmlFor="magic-email" className="text-xs font-medium text-[--muted-foreground]">
                    Email address
                </label>
                <div className="relative">
                    <Mail
                        className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[--muted-foreground]"/>
                    <Input
                        id="magic-email"
                        type="email"
                        placeholder="you@example.com"
                        autoComplete="email"
                        value={email}
                        onChange={(e) => setEmail(e.target.value)}
                        required
                        className="pl-9"
                    />
                </div>
            </div>

            {error && (
                <div
                    className="flex items-start gap-2.5 rounded-xl border border-yellow-900/50 bg-yellow-950/40 px-3 py-2.5">
                    <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-yellow-400"/>
                    <p className="text-sm text-yellow-300">{error}</p>
                </div>
            )}

            <Button type="submit" disabled={loading} className="mt-2 w-full" size="lg">
                {loading ? (
                    <>
                        <Loader2 className="h-4 w-4 animate-spin"/>
                        Sending link…
                    </>
                ) : (
                    "Send magic link"
                )}
            </Button>
        </form>
    );
}

// ── Tab-switching login card ───────────────────────────────────────────────────

type Tab = "password" | "magic";

function LoginCard({redirect, bannerMessage}: { redirect: string; bannerMessage: string | null }) {
    const [activeTab, setActiveTab] = useState<Tab>("password");

    return (
        <div className="rounded-2xl border border-[--border] bg-[--card] p-6 shadow-xl">
            {bannerMessage && (
                <div
                    className="mb-4 flex items-start gap-2.5 rounded-xl border border-yellow-800/50 bg-yellow-950/40 px-3 py-2.5">
                    <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-yellow-400"/>
                    <p className="text-sm text-yellow-300">{bannerMessage}</p>
                </div>
            )}

            {/* Social login */}
            <SocialLoginButtons/>

            {/* Divider */}
            <div className="my-5 flex items-center gap-3">
                <div className="h-px flex-1 bg-[--border]"/>
                <span className="text-xs font-medium text-[--muted-foreground]">or continue with</span>
                <div className="h-px flex-1 bg-[--border]"/>
            </div>

            {/* Tabs */}
            <div className="mb-5 flex rounded-xl border border-[--border] bg-[--muted] p-1">
                {(["password", "magic"] as Tab[]).map((tab) => (
                    <button
                        key={tab}
                        onClick={() => setActiveTab(tab)}
                        className={`flex-1 rounded-lg py-1.5 text-xs font-semibold transition-all duration-150 ${
                            activeTab === tab
                                ? "bg-[--card] text-[--foreground] shadow-sm"
                                : "text-[--muted-foreground] hover:text-[--foreground]"
                        }`}
                    >
                        {tab === "password" ? "Password" : "Magic link"}
                    </button>
                ))}
            </div>

            {activeTab === "password" ? (
                <PasswordForm redirect={redirect}/>
            ) : (
                <MagicLinkForm/>
            )}
        </div>
    );
}

// ── Inner component (needs useSearchParams → must be inside <Suspense>) ───────

function LoginInner() {
    const searchParams = useSearchParams();
    const rawRedirect = searchParams.get("redirect");
    // Reject protocol-relative (`//evil.com`), scheme-based (`https://evil.com`),
    // and backslash-prefixed (`\/\/evil.com`) inputs so a crafted `?redirect=`
    // can never bounce the user off-origin after authentication.
    const redirect =
        rawRedirect && rawRedirect.startsWith("/") && !rawRedirect.startsWith("//") && !rawRedirect.startsWith("/\\")
            ? rawRedirect
            : "/dashboard";
    const errorParam = searchParams.get("error");

    const errorMessages: Record<string, string> = {
        session_expired: "Your session has expired. Please sign in again.",
        oauth_failed: "Social sign-in failed. Please try again.",
    };
    const bannerMessage = errorParam ? (errorMessages[errorParam] ?? "Please sign in to continue.") : null;

    return (
        <>
            <LoginCard redirect={redirect} bannerMessage={bannerMessage}/>
            <p className="mt-5 text-center text-sm text-[--muted-foreground]">
                Don&apos;t have an account?{" "}
                <a href="/register"
                   className="font-semibold text-sky-400 hover:text-sky-300 underline underline-offset-2">
                    Sign up
                </a>
            </p>
        </>
    );
}

// ── Page shell ────────────────────────────────────────────────────────────────

export default function LoginPage() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-[--background] px-4">
      {/* Background glow */}
        <div
            className="pointer-events-none absolute left-1/2 top-1/2 h-[600px] w-[600px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-blue-900/10 blur-3xl"/>

      <div className="relative w-full max-w-sm">
        {/* Logo */}
        <div className="mb-8 flex flex-col items-center gap-3 text-center">
            <a href="/"
               className="flex h-14 w-14 items-center justify-center rounded-2xl bg-[--primary] shadow-lg shadow-blue-900/40 hover:scale-105 transition-transform">
            <Shield className="h-7 w-7 text-white" />
            </a>
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-[--foreground]">
              nabu<span className="text-[--primary]">.watch</span>
            </h1>
            <p className="mt-1 text-sm text-[--muted-foreground]">
              Monitor compliance &amp; security
            </p>
          </div>
        </div>

          <Suspense
              fallback={
                  <div className="flex justify-center py-12">
                      <Loader2 className="h-5 w-5 animate-spin text-[--muted-foreground]"/>
                  </div>
              }
          >
              <LoginInner/>
          </Suspense>

      </div>
    </div>
  );
}
