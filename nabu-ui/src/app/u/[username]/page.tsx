import { Shield, BadgeCheck, Globe } from "lucide-react";
import { notFound } from "next/navigation";
import { safeExternalUrl } from "@/lib/utils";
import { SITE_NAME } from "@/lib/siteConfig";

export const dynamic = "force-dynamic";

type PublicProfile = {
    username: string;
    displayName: string;
    verificationStatus: "UNVERIFIED" | "VERIFIED" | "VERIFIED_PENDING";
    verifiedAt: string | null;
    websites: {
        url: string;
        regionCode: string;
        complianceGrade: string | null;
        verifiedAt: string | null;
    }[];
};

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "";

async function loadProfile(username: string): Promise<PublicProfile | null> {
    const res = await fetch(`${API_URL}/api/v1/public/users/${encodeURIComponent(username)}`, {
        cache: "no-store",
    });
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`profile fetch failed: ${res.status}`);
    return (await res.json()) as PublicProfile;
}

function formatDate(iso: string | null): string {
    if (!iso) return "—";
    return new Date(iso).toLocaleDateString(undefined, {
        year: "numeric",
        month: "short",
        day: "numeric",
    });
}

export async function generateMetadata({
    params,
}: {
    params: Promise<{ username: string }>;
}) {
    const { username } = await params;
    return {
        title: `@${username} — Verified on ${SITE_NAME}`,
        description: `Trust profile for @${username} on ${SITE_NAME} — verified domain ownership and compliance posture.`,
    };
}

export default async function PublicProfilePage({
    params,
}: {
    params: Promise<{ username: string }>;
}) {
    const { username } = await params;
    const profile = await loadProfile(username);
    if (!profile) notFound();

    const verified = profile.verificationStatus === "VERIFIED";
    const pending = profile.verificationStatus === "VERIFIED_PENDING";

    return (
        <div className="min-h-screen bg-[--background]">
            <nav className="border-b border-[--border] px-6 py-4">
                <a
                    href="/"
                    className="flex items-center gap-2 text-[--foreground] hover:opacity-80 transition-opacity w-fit"
                >
                    <Shield className="h-5 w-5 text-sky-400" />
                    <span className="font-semibold">{SITE_NAME}</span>
                </a>
            </nav>

            <main className="mx-auto max-w-3xl px-6 py-12">
                <div className="flex items-start gap-4 mb-8">
                    <div className="flex-1">
                        <h1 className="text-2xl font-bold text-[--foreground]">
                            {profile.displayName}
                        </h1>
                        <p className="text-sm text-[--muted-foreground]">@{profile.username}</p>
                    </div>
                    {verified && (
                        <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-3 py-1 text-sm font-medium text-emerald-400 ring-1 ring-emerald-500/20">
                            <BadgeCheck className="h-4 w-4" />
                            Verified
                        </span>
                    )}
                    {pending && (
                        <span className="inline-flex items-center gap-1.5 rounded-full bg-amber-500/10 px-3 py-1 text-sm font-medium text-amber-400 ring-1 ring-amber-500/20">
                            <BadgeCheck className="h-4 w-4" />
                            Verified · grace period
                        </span>
                    )}
                </div>

                {profile.verifiedAt && (
                    <p className="text-sm text-[--muted-foreground] mb-8">
                        Verified since {formatDate(profile.verifiedAt)}
                    </p>
                )}

                <h2 className="text-sm font-semibold uppercase tracking-widest text-[--muted-foreground] mb-3">
                    Verified websites ({profile.websites.length})
                </h2>

                {profile.websites.length === 0 ? (
                    <p className="text-sm text-[--muted-foreground]">
                        No verified websites yet.
                    </p>
                ) : (
                    <ul className="space-y-2">
                        {profile.websites.map((w) => (
                            <li
                                key={w.url}
                                className="flex items-center justify-between gap-4 rounded-xl border border-[--border] bg-[--card] px-5 py-4"
                            >
                                <div className="min-w-0 flex-1">
                                    <a
                                        href={safeExternalUrl(w.url) ?? "#"}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                        className="flex items-center gap-2 text-sm font-medium text-sky-400 hover:text-sky-300 truncate"
                                    >
                                        <Globe className="h-4 w-4 flex-shrink-0" />
                                        <span className="truncate">{w.url}</span>
                                    </a>
                                    <p className="text-xs text-[--muted-foreground] mt-0.5">
                                        {w.regionCode} · verified {formatDate(w.verifiedAt)}
                                    </p>
                                </div>
                                {w.complianceGrade && (
                                    <span className="rounded-md bg-[--background] px-2.5 py-1 text-xs font-semibold text-[--foreground] ring-1 ring-[--border]">
                                        {w.complianceGrade}
                                    </span>
                                )}
                            </li>
                        ))}
                    </ul>
                )}
            </main>
        </div>
    );
}
