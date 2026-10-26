"use client";

/**
 * OAuth2 callback handler.
 *
 * Spring Security's OAuth2AuthenticationSuccessHandler redirects the browser
 * to {frontendUrl}/auth/callback?token=<jwt> after a successful Google / GitHub
 * login.  This page:
 *   1. Reads the JWT from the query parameter.
 *   2. Stores it in the `nabu_token` cookie (same format as email/password login).
 *   3. Calls GET /api/v1/auth/me to check `firstLogin`.
 *   4. Redirects to /onboarding (first login) or /dashboard (returning user).
 */

import {Suspense, useEffect, useState} from "react";
import {useRouter, useSearchParams} from "next/navigation";
import {AlertCircle, Loader2, Shield} from "lucide-react";
import {fetchMe, storeToken} from "@/lib/authService";

function CallbackHandler() {
    const router = useRouter();
    const searchParams = useSearchParams();
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        const token = searchParams.get("token");

        if (!token) {
            // No token in URL — either a direct navigation or an OAuth failure
            router.replace("/login?error=oauth_failed");
            return;
        }

        storeToken(token);

        fetchMe()
            .then((me) => {
                router.replace(me.firstLogin ? "/onboarding" : "/dashboard");
            })
            .catch(() => {
                // Token was stored but /me failed — still redirect to dashboard
                // (the backend will handle any edge cases)
                router.replace("/dashboard");
            });
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    if (error) {
        return (
            <div className="flex min-h-screen flex-col items-center justify-center bg-[--background] px-4">
                <div className="w-full max-w-sm">
                    <div className="rounded-2xl border border-red-900/50 bg-red-950/30 p-6 text-center">
                        <AlertCircle className="mx-auto mb-3 h-8 w-8 text-red-400"/>
                        <p className="text-sm font-medium text-red-300">{error}</p>
                        <a
                            href="/login"
                            className="mt-4 inline-block text-xs text-[--muted-foreground] underline underline-offset-2 hover:text-[--foreground]"
                        >
                            Back to sign in
                        </a>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div className="flex min-h-screen flex-col items-center justify-center bg-[--background]">
            <div className="flex flex-col items-center gap-4">
                <div
                    className="flex h-14 w-14 items-center justify-center rounded-2xl bg-[--primary] shadow-lg shadow-blue-900/40">
                    <Shield className="h-7 w-7 text-white"/>
                </div>
                <div className="flex items-center gap-2 text-sm text-[--muted-foreground]">
                    <Loader2 className="h-4 w-4 animate-spin"/>
                    Completing sign-in…
                </div>
            </div>
        </div>
    );
}

export default function AuthCallbackPage() {
    return (
        <Suspense
            fallback={
                <div className="flex min-h-screen items-center justify-center bg-[--background]">
                    <Loader2 className="h-5 w-5 animate-spin text-[--muted-foreground]"/>
                </div>
            }
        >
            <CallbackHandler/>
        </Suspense>
    );
}
