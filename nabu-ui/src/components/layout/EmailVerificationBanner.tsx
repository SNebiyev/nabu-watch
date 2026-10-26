"use client";

import {useState} from "react";
import {AlertTriangle, Loader2, Mail} from "lucide-react";
import {useAuth} from "@/lib/authContext";
import {apiClient} from "@/lib/apiClient";

export function EmailVerificationBanner() {
    const {emailVerified, email} = useAuth();
    const [resendState, setResendState] = useState<"idle" | "sending" | "sent" | "error">("idle");

    if (emailVerified === null || emailVerified) return null;

    async function handleResend() {
        setResendState("sending");
        try {
            const res = await apiClient("/api/v1/auth/resend-verification", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({email}),
                skipAuthRedirect: true,
            });
            if (!res.ok) throw new Error();
            setResendState("sent");
        } catch {
            setResendState("error");
        }
    }

    return (
        <div className="bg-amber-950/80 border-b border-amber-800/50">
            <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-10 py-2.5 flex items-center justify-between gap-3">
                <div className="flex items-center gap-2.5 min-w-0">
                    <AlertTriangle className="h-4 w-4 shrink-0 text-amber-400"/>
                    <p className="text-sm text-amber-200 truncate">
                        Please verify your email address to access all features.
                    </p>
                </div>
                <div className="shrink-0">
                    {resendState === "idle" && (
                        <button
                            onClick={handleResend}
                            className="inline-flex items-center gap-1.5 rounded-md bg-amber-800/60 px-3 py-1 text-xs font-medium text-amber-100 hover:bg-amber-800/80 transition-colors"
                        >
                            <Mail className="h-3.5 w-3.5"/>
                            Resend email
                        </button>
                    )}
                    {resendState === "sending" && (
                        <span className="inline-flex items-center gap-1.5 text-xs text-amber-300">
                            <Loader2 className="h-3.5 w-3.5 animate-spin"/>
                            Sending...
                        </span>
                    )}
                    {resendState === "sent" && (
                        <span className="text-xs text-green-400">
                            Verification email sent!
                        </span>
                    )}
                    {resendState === "error" && (
                        <button
                            onClick={handleResend}
                            className="text-xs text-red-400 hover:text-red-300 underline"
                        >
                            Failed. Try again
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
}
