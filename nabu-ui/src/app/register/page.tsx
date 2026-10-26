"use client";

import {Suspense, useState} from "react";
import {useRouter} from "next/navigation";
import {AlertCircle, CheckCircle2, Eye, EyeOff, Loader2, Shield, User,} from "lucide-react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {extractErrorMessage} from "@/lib/utils";

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

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

// ── Social register buttons ────────────────────────────────────────────────────

function SocialRegisterButtons() {
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

// ── Register form ──────────────────────────────────────────────────────────────

function RegisterForm() {
    const router = useRouter();
    const [name, setName] = useState("");
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [confirmPassword, setConfirmPassword] = useState("");
    const [showPassword, setShowPassword] = useState(false);
    const [showConfirmPassword, setShowConfirmPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState(false);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);

        if (password.length < 8) {
            setError("Password must be at least 8 characters.");
            return;
        }
        if (password !== confirmPassword) {
            setError("Passwords do not match.");
            return;
        }

        setLoading(true);
        try {
            const res = await fetch("/api/v1/auth/register", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({
                    email,
                    password,
                    name: name || undefined,
                }),
            });

            if (!res.ok) {
                throw new Error(await extractErrorMessage(res, "Registration failed. Please try again."));
            }

            setSuccess(true);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Registration failed. Please try again.");
        } finally {
            setLoading(false);
        }
    }

    if (success) {
        return (
            <div className="flex flex-col items-center gap-3 py-4 text-center">
                <div
                    className="flex h-12 w-12 items-center justify-center rounded-2xl bg-green-950/60 ring-1 ring-green-800/40">
                    <CheckCircle2 className="h-6 w-6 text-green-400"/>
                </div>
                <div>
                    <p className="font-semibold text-[--foreground]">Account created!</p>
                    <p className="mt-1 text-sm text-[--muted-foreground]">
                        Check your email for a verification link.
                    </p>
                </div>
                <a
                    href="/login"
                    className="mt-1 text-xs text-[--muted-foreground] underline underline-offset-2 hover:text-[--foreground]"
                >
                    Back to sign in
                </a>
            </div>
        );
    }

    return (
        <form onSubmit={handleSubmit} className="space-y-4">
            {/* Name (optional) */}
            <div className="space-y-1.5">
                <label htmlFor="name" className="text-xs font-medium text-[--muted-foreground]">
                    Full name <span className="text-[--muted-foreground]/60">(optional)</span>
                </label>
                <div className="relative">
                    <User
                        className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[--muted-foreground]"/>
                    <Input
                        id="name"
                        type="text"
                        placeholder="Jane Doe"
                        autoComplete="name"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        className="pl-9"
                    />
                </div>
            </div>

            {/* Email */}
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

            {/* Password */}
            <div className="space-y-1.5">
                <label htmlFor="password" className="text-xs font-medium text-[--muted-foreground]">
                    Password
                </label>
                <div className="relative">
                    <Input
                        id="password"
                        type={showPassword ? "text" : "password"}
                        placeholder="••••••••"
                        autoComplete="new-password"
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        required
                        minLength={8}
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
                <p className="text-xs text-[--muted-foreground]/70">Minimum 8 characters.</p>
            </div>

            {/* Confirm Password */}
            <div className="space-y-1.5">
                <label htmlFor="confirm-password" className="text-xs font-medium text-[--muted-foreground]">
                    Confirm password
                </label>
                <div className="relative">
                    <Input
                        id="confirm-password"
                        type={showConfirmPassword ? "text" : "password"}
                        placeholder="••••••••"
                        autoComplete="new-password"
                        value={confirmPassword}
                        onChange={(e) => setConfirmPassword(e.target.value)}
                        required
                        className="pr-10"
                    />
                    <button
                        type="button"
                        onClick={() => setShowConfirmPassword((v) => !v)}
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-[--muted-foreground] hover:text-[--foreground]"
                        tabIndex={-1}
                    >
                        {showConfirmPassword ? <EyeOff className="h-4 w-4"/> : <Eye className="h-4 w-4"/>}
                    </button>
                </div>
            </div>

            {error && (
                <div className="flex items-start gap-2.5 rounded-xl border border-red-900/50 bg-red-950/50 px-3 py-2.5">
                    <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-400"/>
                    <p className="text-sm text-red-400">{error}</p>
                </div>
            )}

            <Button type="submit" disabled={loading} className="mt-2 w-full" size="lg">
                {loading ? (
                    <>
                        <Loader2 className="h-4 w-4 animate-spin"/>
                        Creating account…
                    </>
                ) : (
                    "Create Account"
                )}
            </Button>
        </form>
    );
}

// ── Register card ──────────────────────────────────────────────────────────────

function RegisterCard() {
    return (
        <div className="rounded-2xl border border-[--border] bg-[--card] p-6 shadow-xl">
            {/* Social buttons */}
            <SocialRegisterButtons/>

            {/* Divider */}
            <div className="my-5 flex items-center gap-3">
                <div className="h-px flex-1 bg-[--border]"/>
                <span className="text-xs font-medium text-[--muted-foreground]">or</span>
                <div className="h-px flex-1 bg-[--border]"/>
            </div>

            <RegisterForm/>
        </div>
    );
}

// ── Page shell ─────────────────────────────────────────────────────────────────

export default function RegisterPage() {
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
                        <Shield className="h-7 w-7 text-white"/>
                    </a>
                    <div>
                        <h1 className="text-2xl font-bold tracking-tight text-[--foreground]">
                            nabu<span className="text-[--primary]">.watch</span>
                        </h1>
                        <p className="mt-1 text-sm text-[--muted-foreground]">
                            Create your free account
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
                    <RegisterCard/>
                </Suspense>

                <p className="mt-5 text-center text-sm text-[--muted-foreground]">
                    Already have an account?{" "}
                    <a href="/login" className="font-semibold text-[--foreground] hover:text-[--primary]">
                        Sign in
                    </a>
                </p>

            </div>
        </div>
    );
}
