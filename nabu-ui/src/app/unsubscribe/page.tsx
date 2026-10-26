"use client";

import {Suspense, useCallback, useEffect, useState} from "react";
import {useSearchParams} from "next/navigation";
import {AlertCircle, BellOff, CheckCircle2, Loader2, Shield} from "lucide-react";
import {extractErrorMessage} from "@/lib/utils";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

type Status = "loading" | "confirm" | "success" | "error";

function UnsubscribeInner() {
    const searchParams = useSearchParams();
    const token = searchParams.get("token");

    const [status, setStatus] = useState<Status>("loading");
    const [label, setLabel] = useState("");
    const [errorMessage, setErrorMessage] = useState("");
    const [submitting, setSubmitting] = useState(false);

    useEffect(() => {
        if (!token) {
            setErrorMessage("No unsubscribe token found. Please check your email link.");
            setStatus("error");
            return;
        }

        async function fetchInfo() {
            try {
                const res = await fetch(
                    `${API_URL}/api/v1/unsubscribe?token=${encodeURIComponent(token!)}`,
                );
                if (!res.ok) {
                    throw new Error(await extractErrorMessage(res, "Invalid or expired unsubscribe link."));
                }
                const data = await res.json();
                setLabel(data.label);
                setStatus("confirm");
            } catch (err) {
                setErrorMessage(err instanceof Error ? err.message : "Invalid or expired link.");
                setStatus("error");
            }
        }

        fetchInfo();
    }, [token]);

    const handleUnsubscribe = useCallback(async () => {
        if (!token) return;
        setSubmitting(true);
        try {
            const res = await fetch(
                `${API_URL}/api/v1/unsubscribe?token=${encodeURIComponent(token)}`,
                {method: "POST"},
            );
            if (!res.ok) {
                throw new Error(await extractErrorMessage(res, "Unsubscribe failed."));
            }
            setStatus("success");
        } catch (err) {
            setErrorMessage(err instanceof Error ? err.message : "Unsubscribe failed.");
            setStatus("error");
        } finally {
            setSubmitting(false);
        }
    }, [token]);

    return (
        <div className="rounded-2xl border border-[--border] bg-[--card] p-6 shadow-xl">
            {status === "loading" && (
                <div className="flex flex-col items-center gap-4 py-6 text-center">
                    <Loader2 className="h-10 w-10 animate-spin text-[--primary]"/>
                    <p className="text-sm text-[--muted-foreground]">Loading...</p>
                </div>
            )}

            {status === "confirm" && (
                <div className="flex flex-col items-center gap-4 py-4 text-center">
                    <div
                        className="flex h-12 w-12 items-center justify-center rounded-2xl bg-amber-950/60 ring-1 ring-amber-800/40">
                        <BellOff className="h-6 w-6 text-amber-400"/>
                    </div>
                    <div>
                        <p className="font-semibold text-[--foreground]">Unsubscribe from {label}?</p>
                        <p className="mt-1 text-sm text-[--muted-foreground]">
                            You will no longer receive {label}. You can re-enable them
                            anytime in your notification preferences.
                        </p>
                    </div>
                    <button
                        onClick={handleUnsubscribe}
                        disabled={submitting}
                        className="mt-2 inline-flex items-center gap-2 rounded-lg bg-red-600 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-red-700 disabled:opacity-50"
                    >
                        {submitting && <Loader2 className="h-4 w-4 animate-spin"/>}
                        Unsubscribe
                    </button>
                </div>
            )}

            {status === "success" && (
                <div className="flex flex-col items-center gap-4 py-6 text-center">
                    <div
                        className="flex h-12 w-12 items-center justify-center rounded-2xl bg-green-950/60 ring-1 ring-green-800/40">
                        <CheckCircle2 className="h-6 w-6 text-green-400"/>
                    </div>
                    <div>
                        <p className="font-semibold text-[--foreground]">Unsubscribed</p>
                        <p className="mt-1 text-sm text-[--muted-foreground]">
                            You have been successfully unsubscribed. You can re-enable
                            notifications anytime from your dashboard settings.
                        </p>
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
                </div>
            )}
        </div>
    );
}

export default function UnsubscribePage() {
    return (
        <div className="flex min-h-screen flex-col items-center justify-center bg-[--background] px-4">
            <div
                className="pointer-events-none absolute left-1/2 top-1/2 h-[600px] w-[600px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-blue-900/10 blur-3xl"/>

            <div className="relative w-full max-w-sm">
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
                            Email preferences
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
                    <UnsubscribeInner/>
                </Suspense>
            </div>
        </div>
    );
}
