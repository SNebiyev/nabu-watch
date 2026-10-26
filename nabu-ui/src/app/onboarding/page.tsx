"use client";

/**
 * Onboarding page — shown to every user whose `firstLogin` flag is still true.
 *
 * Single job: get the user to add their first website so monitoring can begin.
 * No sidebar, no nav — nothing to distract from the one action that matters.
 *
 * After a successful POST /api/v1/websites the backend flips `isFirstLogin`
 * to false and the user is sent to /dashboard.
 */

import {useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {AlertCircle, ArrowRight, CheckCircle2, FileText, Globe, Loader2, Lock, Shield, Zap,} from "lucide-react";
import {useAuth} from "@/lib/authContext";
import {apiClient} from "@/lib/apiClient";
import {extractErrorMessage} from "@/lib/utils";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";

// ── URL helpers ────────────────────────────────────────────────────────────────

const TLD_TO_REGION: Record<string, string> = {
    de: "DE", at: "AT", ch: "CH", fr: "FR",
    uk: "GB", gb: "GB", es: "ES", it: "IT",
    nl: "NL", pl: "PL", ca: "CA", br: "BR",
};

function detectRegion(raw: string): string {
    try {
        const trimmed = raw.trim();
        const withProto = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
        const hostname = new URL(withProto).hostname.toLowerCase();
        const parts = hostname.split(".");
        if (parts.length >= 3) {
            const last2 = parts[parts.length - 2] + "." + parts[parts.length - 1];
            if (last2 === "co.uk" || last2 === "co.jp") return TLD_TO_REGION[parts[parts.length - 1]] ?? "US";
        }
        const tld = parts[parts.length - 1];
        return tld ? (TLD_TO_REGION[tld] ?? "US") : "US";
    } catch {
        return "US";
    }
}

function normalizeUrl(raw: string): string {
    let url = raw.trim().replace(/\/+$/, "");
    if (url && !/^https?:\/\//i.test(url)) url = "https://" + url;
    return url;
}

// ── Feature pills shown below the input ───────────────────────────────────────

const FEATURES = [
    {icon: Lock, label: "SSL expiry monitoring"},
    {icon: FileText, label: "Legal compliance checks"},
    {icon: Zap, label: "First scan in < 60 s"},
];

// ── Page ──────────────────────────────────────────────────────────────────────

export default function OnboardingPage() {
    const router = useRouter();
    const {email, firstLogin, loading: authLoading} = useAuth();

    const [url, setUrl] = useState("");
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState(false);
    const [ownershipAttestation, setOwnershipAttestation] = useState(false);

    // Guard: if user already onboarded (firstLogin === false), send to dashboard
    useEffect(() => {
        if (!authLoading && firstLogin === false) {
            router.replace("/dashboard");
        }
    }, [authLoading, firstLogin, router]);

    // Guard: no token at all
    useEffect(() => {
        if (!authLoading && !email) {
            router.replace("/login");
        }
    }, [authLoading, email, router]);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        const normalized = normalizeUrl(url);
        if (!normalized) return;

        setError(null);
        setLoading(true);
        try {
            const res = await apiClient("/api/v1/websites", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({
                    url: normalized,
                    region: detectRegion(url),
                    ownershipAttestation: true,
                }),
            });

            if (!res.ok) {
                throw new Error(await extractErrorMessage(res, "Failed to add website"));
            }

            setSuccess(true);
            // Short delay so user sees the success state, then redirect to dashboard
            setTimeout(() => router.replace("/dashboard"), 1200);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to add website");
        } finally {
            setLoading(false);
        }
    }

    // Show a spinner while auth state resolves
    if (authLoading || (!authLoading && (!email || firstLogin === false))) {
        return (
            <div className="flex min-h-screen items-center justify-center bg-[--background]">
                <Loader2 className="h-5 w-5 animate-spin text-[--muted-foreground]"/>
            </div>
        );
    }

    return (
        <div className="flex min-h-screen flex-col items-center justify-center bg-[--background] px-4">
            {/* Ambient glow */}
            <div
                className="pointer-events-none absolute left-1/2 top-1/2 h-[700px] w-[700px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-blue-900/10 blur-3xl"/>
            <div
                className="pointer-events-none absolute left-1/4 top-1/3 h-[300px] w-[300px] rounded-full bg-green-900/8 blur-3xl"/>

            <div className="relative w-full max-w-lg">
                {/* Logo */}
                <div className="mb-10 flex flex-col items-center gap-3 text-center">
                    <div
                        className="flex h-14 w-14 items-center justify-center rounded-2xl bg-[--primary] shadow-lg shadow-blue-900/40">
                        <Shield className="h-7 w-7 text-white"/>
                    </div>
                    <div>
                        <p className="text-xs font-semibold uppercase tracking-widest text-[--primary]">
                            Welcome to nabu-watch
                        </p>
                        <h1 className="mt-2 text-3xl font-bold tracking-tight text-[--foreground]">
                            Add your first website
                        </h1>
                        <p className="mt-2 text-sm text-[--muted-foreground]">
                            We&apos;ll start monitoring it for SSL issues and legal compliance immediately.
                        </p>
                    </div>
                </div>

                {/* Card */}
                <div className="rounded-2xl border border-[--border] bg-[--card] p-8 shadow-xl">
                    {success ? (
                        <div className="flex flex-col items-center gap-4 py-4 text-center">
                            <div
                                className="flex h-14 w-14 items-center justify-center rounded-2xl bg-green-950/60 ring-1 ring-green-800/40">
                                <CheckCircle2 className="h-7 w-7 text-green-400"/>
                            </div>
                            <div>
                                <p className="text-lg font-semibold text-[--foreground]">Scan started!</p>
                                <p className="mt-1 text-sm text-[--muted-foreground]">
                                    Taking you to your dashboard…
                                </p>
                            </div>
                            <Loader2 className="h-4 w-4 animate-spin text-[--muted-foreground]"/>
                        </div>
                    ) : (
                        <form onSubmit={handleSubmit} className="space-y-5">
                            <div className="space-y-2">
                                <label
                                    htmlFor="site-url"
                                    className="text-sm font-medium text-[--foreground]"
                                >
                                    Website URL
                                </label>
                                <div className="relative">
                                    <Globe
                                        className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-[--muted-foreground]"/>
                                    <Input
                                        id="site-url"
                                        type="text"
                                        placeholder="example.com"
                                        autoComplete="url"
                                        autoFocus
                                        value={url}
                                        onChange={(e) => {
                                            setUrl(e.target.value);
                                            setError(null);
                                        }}
                                        required
                                        className="h-12 pl-10 text-base"
                                    />
                                </div>
                                <p className="text-xs text-[--muted-foreground]">
                                    Enter a domain like <span className="font-mono">example.com</span> — we&apos;ll
                                    handle the rest.
                                </p>
                            </div>

                            <label className="flex items-start gap-2.5 cursor-pointer rounded-xl border border-[--border] bg-[--background]/40 px-3 py-2.5 text-xs leading-relaxed text-[--muted-foreground]">
                                <input
                                    type="checkbox"
                                    checked={ownershipAttestation}
                                    onChange={(e) => setOwnershipAttestation(e.target.checked)}
                                    className="mt-0.5 h-4 w-4 shrink-0 rounded border-[--border] bg-[--background] text-[--primary] focus:ring-2 focus:ring-[--primary]/40"
                                    required
                                />
                                <span>
                                    I own this website or have permission to scan it. See the{" "}
                                    <a href="https://github.com/SNebiyev/nabu-watch/blob/main/docs/RESPONSIBLE_USE.md"
                                       target="_blank" rel="noopener noreferrer"
                                       className="text-sky-600 dark:text-sky-400 underline underline-offset-2 hover:opacity-80">
                                        responsible use guidelines
                                    </a>
                                    .
                                </span>
                            </label>

                            {error && (
                                <div
                                    className="flex items-start gap-2.5 rounded-xl border border-red-900/50 bg-red-950/50 px-3 py-2.5">
                                    <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-400"/>
                                    <p className="text-sm text-red-400">{error}</p>
                                </div>
                            )}

                            <Button
                                type="submit"
                                disabled={loading || !url.trim() || !ownershipAttestation}
                                size="lg"
                                className="w-full gap-2 text-base"
                            >
                                {loading ? (
                                    <>
                                        <Loader2 className="h-4 w-4 animate-spin"/>
                                        Starting scan…
                                    </>
                                ) : (
                                    <>
                                        Start First Scan
                                        <ArrowRight className="h-4 w-4"/>
                                    </>
                                )}
                            </Button>
                        </form>
                    )}
                </div>

                {/* Feature pills */}
                {!success && (
                    <div className="mt-6 flex flex-wrap justify-center gap-2">
                        {FEATURES.map(({icon: Icon, label}) => (
                            <div
                                key={label}
                                className="flex items-center gap-1.5 rounded-full border border-[--border] bg-[--card] px-3 py-1.5 text-xs text-[--muted-foreground]"
                            >
                                <Icon className="h-3 w-3 text-[--primary]"/>
                                {label}
                            </div>
                        ))}
                    </div>
                )}

                <p className="mt-6 text-center text-xs text-[--muted-foreground]">
                    You can add more websites and adjust monitors from your dashboard.
                </p>
            </div>
        </div>
    );
}
