"use client";

import {Suspense, useState} from "react";
import {useRouter, useSearchParams} from "next/navigation";
import {AlertCircle, ArrowLeft, CheckCircle2, Eye, EyeOff, Loader2, Lock, Shield,} from "lucide-react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {extractErrorMessage} from "@/lib/utils";

// ── Inner component (needs useSearchParams → must be inside <Suspense>) ────────

function ResetPasswordInner() {
    const router = useRouter();
    const searchParams = useSearchParams();
    const token = searchParams.get("token");

    const [newPassword, setNewPassword] = useState("");
    const [confirmPassword, setConfirmPassword] = useState("");
    const [showNewPassword, setShowNewPassword] = useState(false);
    const [showConfirmPassword, setShowConfirmPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState(false);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);

        if (newPassword.length < 8) {
            setError("Password must be at least 8 characters.");
            return;
        }
        if (newPassword !== confirmPassword) {
            setError("Passwords do not match.");
            return;
        }
        if (!token) {
            setError("Missing reset token. Please use the link from your email.");
            return;
        }

        setLoading(true);
        try {
            const res = await fetch("/api/v1/auth/reset-password", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({token, newPassword}),
            });

            if (!res.ok) {
                throw new Error(await extractErrorMessage(res, "Failed to reset password. The link may have expired."));
            }

            setSuccess(true);

            setTimeout(() => {
                router.replace("/login");
            }, 2000);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to reset password. Please try again.");
        } finally {
            setLoading(false);
        }
    }

    if (!token) {
        return (
            <div className="rounded-2xl border border-[--border] bg-[--card] p-6 shadow-xl">
                <div className="flex flex-col items-center gap-4 py-4 text-center">
                    <div
                        className="flex items-start gap-2.5 w-full rounded-xl border border-red-900/50 bg-red-950/50 px-3 py-2.5">
                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-400"/>
                        <p className="text-sm text-red-400 text-left">
                            Invalid reset link. Please request a new one.
                        </p>
                    </div>
                    <a
                        href="/forgot-password"
                        className="text-xs text-[--muted-foreground] underline underline-offset-2 hover:text-[--foreground]"
                    >
                        Request a new reset link
                    </a>
                </div>
            </div>
        );
    }

    if (success) {
        return (
            <div className="rounded-2xl border border-[--border] bg-[--card] p-6 shadow-xl">
                <div className="flex flex-col items-center gap-4 py-6 text-center">
                    <div
                        className="flex h-12 w-12 items-center justify-center rounded-2xl bg-green-950/60 ring-1 ring-green-800/40">
                        <CheckCircle2 className="h-6 w-6 text-green-400"/>
                    </div>
                    <div>
                        <p className="font-semibold text-[--foreground]">Password reset successfully!</p>
                        <p className="mt-1 text-sm text-[--muted-foreground]">
                            You can now sign in with your new password.
                        </p>
                    </div>
                    <div className="flex items-center gap-1.5 text-xs text-[--muted-foreground]">
                        <Loader2 className="h-3 w-3 animate-spin"/>
                        Redirecting to sign in…
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div className="rounded-2xl border border-[--border] bg-[--card] p-6 shadow-xl">
            <div className="mb-5">
                <h2 className="text-base font-semibold text-[--foreground]">Choose a new password</h2>
                <p className="mt-1 text-sm text-[--muted-foreground]">
                    Enter and confirm your new password below.
                </p>
            </div>

            <form onSubmit={handleSubmit} className="space-y-4">
                {/* New Password */}
                <div className="space-y-1.5">
                    <label htmlFor="new-password" className="text-xs font-medium text-[--muted-foreground]">
                        New password
                    </label>
                    <div className="relative">
                        <Lock
                            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[--muted-foreground]"/>
                        <Input
                            id="new-password"
                            type={showNewPassword ? "text" : "password"}
                            placeholder="••••••••"
                            autoComplete="new-password"
                            value={newPassword}
                            onChange={(e) => setNewPassword(e.target.value)}
                            required
                            minLength={8}
                            className="pl-9 pr-10"
                        />
                        <button
                            type="button"
                            onClick={() => setShowNewPassword((v) => !v)}
                            className="absolute right-3 top-1/2 -translate-y-1/2 text-[--muted-foreground] hover:text-[--foreground]"
                            tabIndex={-1}
                        >
                            {showNewPassword ? <EyeOff className="h-4 w-4"/> : <Eye className="h-4 w-4"/>}
                        </button>
                    </div>
                    <p className="text-xs text-[--muted-foreground]/70">Minimum 8 characters.</p>
                </div>

                {/* Confirm New Password */}
                <div className="space-y-1.5">
                    <label htmlFor="confirm-password" className="text-xs font-medium text-[--muted-foreground]">
                        Confirm new password
                    </label>
                    <div className="relative">
                        <Lock
                            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[--muted-foreground]"/>
                        <Input
                            id="confirm-password"
                            type={showConfirmPassword ? "text" : "password"}
                            placeholder="••••••••"
                            autoComplete="new-password"
                            value={confirmPassword}
                            onChange={(e) => setConfirmPassword(e.target.value)}
                            required
                            className="pl-9 pr-10"
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
                            Resetting password…
                        </>
                    ) : (
                        "Reset Password"
                    )}
                </Button>
            </form>
        </div>
    );
}

// ── Page shell ─────────────────────────────────────────────────────────────────

export default function ResetPasswordPage() {
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
                    <ResetPasswordInner/>
                </Suspense>

                <p className="mt-5 text-center text-sm text-[--muted-foreground]">
                    <a
                        href="/login"
                        className="inline-flex items-center gap-1 text-xs text-[--muted-foreground] hover:text-[--primary]"
                    >
                        <ArrowLeft className="h-3 w-3"/>
                        Back to sign in
                    </a>
                </p>

            </div>
        </div>
    );
}
