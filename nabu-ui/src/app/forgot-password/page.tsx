"use client";

import {Suspense, useState} from "react";
import {AlertCircle, Loader2, Mail, Shield} from "lucide-react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {extractErrorMessage} from "@/lib/utils";

// ── Forgot password card ───────────────────────────────────────────────────────

function ForgotPasswordCard() {
    const [email, setEmail] = useState("");
    const [loading, setLoading] = useState(false);
    const [sent, setSent] = useState(false);
    const [error, setError] = useState<string | null>(null);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);
        setLoading(true);
        try {
            const res = await fetch("/api/v1/auth/forgot-password", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({email}),
            });

            // Always show success — backend must not leak whether email exists.
            // We only hard-fail on unexpected server errors (5xx).
            if (res.status >= 500) {
                throw new Error(await extractErrorMessage(res, "Failed to send reset link. Please try again."));
            }

            setSent(true);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to send reset link. Please try again.");
        } finally {
            setLoading(false);
        }
    }

    if (sent) {
        return (
            <div className="rounded-2xl border border-[--border] bg-[--card] p-6 shadow-xl">
                <div className="flex flex-col items-center gap-4 py-4 text-center">
                    <div
                        className="flex h-12 w-12 items-center justify-center rounded-2xl bg-green-950/60 ring-1 ring-green-800/40">
                        <Mail className="h-6 w-6 text-green-400"/>
                    </div>
                    <div>
                        <p className="font-semibold text-[--foreground]">Check your inbox</p>
                        <p className="mt-1 text-sm text-[--muted-foreground]">
                            If an account exists for{" "}
                            <span className="font-medium text-[--foreground]">{email}</span>, you&apos;ll
                            receive a password reset link shortly.
                        </p>
                    </div>
                    <button
                        onClick={() => {
                            setSent(false);
                            setEmail("");
                        }}
                        className="mt-1 text-xs text-[--muted-foreground] underline underline-offset-2 hover:text-[--foreground]"
                    >
                        Try a different email
                    </button>
                </div>
            </div>
        );
    }

    return (
        <div className="rounded-2xl border border-[--border] bg-[--card] p-6 shadow-xl">
            <div className="mb-5">
                <h2 className="text-base font-semibold text-[--foreground]">Reset your password</h2>
                <p className="mt-1 text-sm text-[--muted-foreground]">
                    Enter your email address and we&apos;ll send you a reset link.
                </p>
            </div>

            <form onSubmit={handleSubmit} className="space-y-4">
                <div className="space-y-1.5">
                    <label htmlFor="email" className="text-xs font-medium text-[--muted-foreground]">
                        Email address
                    </label>
                    <div className="relative">
                        <Mail
                            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[--muted-foreground]"/>
                        <Input
                            id="email"
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
                        className="flex items-start gap-2.5 rounded-xl border border-red-900/50 bg-red-950/50 px-3 py-2.5">
                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-400"/>
                        <p className="text-sm text-red-400">{error}</p>
                    </div>
                )}

                <Button type="submit" disabled={loading} className="mt-2 w-full" size="lg">
                    {loading ? (
                        <>
                            <Loader2 className="h-4 w-4 animate-spin"/>
                            Sending reset link…
                        </>
                    ) : (
                        "Send Reset Link"
                    )}
                </Button>
            </form>
        </div>
    );
}

// ── Page shell ─────────────────────────────────────────────────────────────────

export default function ForgotPasswordPage() {
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
                    <ForgotPasswordCard/>
                </Suspense>

                <p className="mt-5 text-center text-sm text-[--muted-foreground]">
                    Remembered it?{" "}
                    <a href="/login" className="font-semibold text-[--foreground] hover:text-[--primary]">
                        Back to sign in
                    </a>
                </p>

            </div>
        </div>
    );
}
