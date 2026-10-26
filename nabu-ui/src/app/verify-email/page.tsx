"use client";

import {Suspense, useEffect, useState} from "react";
import {useRouter, useSearchParams} from "next/navigation";
import {AlertCircle, CheckCircle2, Loader2, Shield} from "lucide-react";
import {storeToken} from "@/lib/authService";
import {extractErrorMessage} from "@/lib/utils";

// ── Inner component (needs useSearchParams → must be inside <Suspense>) ────────

function VerifyEmailInner() {
    const router = useRouter();
    const searchParams = useSearchParams();
    const token = searchParams.get("token");

    const [status, setStatus] = useState<"loading" | "success" | "error">("loading");
    const [errorMessage, setErrorMessage] = useState<string>("Invalid or expired verification link.");

    useEffect(() => {
        if (!token) {
            setErrorMessage("No verification token found. Please check your email link.");
            setStatus("error");
            return;
        }

        async function verify() {
            try {
                const res = await fetch("/api/v1/auth/verify-email", {
                    method: "POST",
                    headers: {"Content-Type": "application/json"},
                    body: JSON.stringify({token}),
                });

                if (!res.ok) {
                    throw new Error(await extractErrorMessage(res, "Invalid or expired verification link."));
                }

                const data = await res.json();
                if (data?.token) {
                    storeToken(data.token);
                }

                setStatus("success");

                setTimeout(() => {
                    router.replace("/dashboard");
                }, 2000);
            } catch (err) {
                setErrorMessage(
                    err instanceof Error ? err.message : "Invalid or expired verification link."
                );
                setStatus("error");
            }
        }

        verify();
    }, [token, router]);

    return (
        <div className="rounded-2xl border border-[--border] bg-[--card] p-6 shadow-xl">
            {status === "loading" && (
                <div className="flex flex-col items-center gap-4 py-6 text-center">
                    <Loader2 className="h-10 w-10 animate-spin text-[--primary]"/>
                    <div>
                        <p className="font-semibold text-[--foreground]">Verifying your email…</p>
                        <p className="mt-1 text-sm text-[--muted-foreground]">
                            This only takes a moment.
                        </p>
                    </div>
                </div>
            )}

            {status === "success" && (
                <div className="flex flex-col items-center gap-4 py-6 text-center">
                    <div
                        className="flex h-12 w-12 items-center justify-center rounded-2xl bg-green-950/60 ring-1 ring-green-800/40">
                        <CheckCircle2 className="h-6 w-6 text-green-400"/>
                    </div>
                    <div>
                        <p className="font-semibold text-[--foreground]">Email verified!</p>
                        <p className="mt-1 text-sm text-[--muted-foreground]">
                            Redirecting you to your dashboard…
                        </p>
                    </div>
                    <div className="flex items-center gap-1.5 text-xs text-[--muted-foreground]">
                        <Loader2 className="h-3 w-3 animate-spin"/>
                        Redirecting in 2 seconds
                    </div>
                </div>
            )}

            {status === "error" && (
                <div className="flex flex-col items-center gap-4 py-4 text-center">
                    <div
                        className="flex items-start gap-2.5 w-full rounded-xl border border-red-900/50 bg-red-950/50 px-3 py-2.5">
                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-400"/>
                        <p className="text-sm text-red-400 text-left">{errorMessage}</p>
                    </div>
                    <div className="text-center">
                        <p className="text-sm text-[--muted-foreground]">
                            Need a new link?{" "}
                            <a
                                href="/register"
                                className="font-semibold text-[--foreground] hover:text-[--primary]"
                            >
                                Resend verification
                            </a>
                        </p>
                    </div>
                </div>
            )}
        </div>
    );
}

// ── Page shell ─────────────────────────────────────────────────────────────────

export default function VerifyEmailPage() {
    return (
        <div className="flex min-h-screen flex-col items-center justify-center bg-[--background] px-4">
            {/* Background glow */}
            <div
                className="pointer-events-none absolute left-1/2 top-1/2 h-[600px] w-[600px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-blue-900/10 blur-3xl"/>

            <div className="relative w-full max-w-sm">
                {/* Logo */}
                <div className="mb-8 flex flex-col items-center gap-3 text-center">
                    <div
                        className="flex h-14 w-14 items-center justify-center rounded-2xl bg-[--primary] shadow-lg shadow-blue-900/40">
                        <Shield className="h-7 w-7 text-white"/>
                    </div>
                    <div>
                        <h1 className="text-2xl font-bold tracking-tight text-[--foreground]">
                            nabu<span className="text-[--primary]">.watch</span>
                        </h1>
                        <p className="mt-1 text-sm text-[--muted-foreground]">
                            Email verification
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
                    <VerifyEmailInner/>
                </Suspense>

            </div>
        </div>
    );
}
