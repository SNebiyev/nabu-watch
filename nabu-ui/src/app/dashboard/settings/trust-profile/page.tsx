"use client";

import { useCallback, useEffect, useState } from "react";
import { BadgeCheck, ExternalLink, Loader2, AlertCircle, ShieldOff } from "lucide-react";
import { apiClient } from "@/lib/apiClient";
import { SITE_URL } from "@/lib/siteConfig";

const SITE_HOST = SITE_URL.replace(/^https?:\/\//, "");

type VerificationStatus = "UNVERIFIED" | "VERIFIED" | "VERIFIED_PENDING";

type Profile = {
    publicUsername: string | null;
    publicProfileEnabled: boolean;
    verificationStatus: VerificationStatus;
    verifiedAt: string | null;
    verificationRevokedAt: string | null;
};

const SLUG_RE = /^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])?$/;

export default function TrustProfileSettingsPage() {
    const [profile, setProfile] = useState<Profile | null>(null);
    const [draftSlug, setDraftSlug] = useState("");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [toast, setToast] = useState<string | null>(null);

    const load = useCallback(async () => {
        try {
            const res = await apiClient("/api/v1/users/me/public-profile");
            if (!res.ok) throw new Error("load failed");
            const p = (await res.json()) as Profile;
            setProfile(p);
            setDraftSlug(p.publicUsername ?? "");
        } catch {
            setError("Failed to load profile settings.");
        }
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    async function save(body: { publicUsername?: string | null; publicProfileEnabled?: boolean }) {
        setSaving(true);
        setError(null);
        try {
            const res = await apiClient("/api/v1/users/me/public-profile", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            if (!res.ok) {
                const msg = (await res.json().catch(() => null))?.detail ?? `Save failed (${res.status})`;
                throw new Error(msg);
            }
            const p = (await res.json()) as Profile;
            setProfile(p);
            setDraftSlug(p.publicUsername ?? "");
            setToast("Saved");
            setTimeout(() => setToast(null), 2000);
        } catch (e) {
            setError(e instanceof Error ? e.message : "Save failed");
        } finally {
            setSaving(false);
        }
    }

    const slugDirty = (profile?.publicUsername ?? "") !== draftSlug.trim().toLowerCase();
    const slugValid = draftSlug === "" || SLUG_RE.test(draftSlug.trim().toLowerCase());

    if (!profile) {
        return (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" /> Loading…
            </div>
        );
    }

    return (
        <div className="space-y-6">
            <div>
                <h2 className="text-lg font-semibold text-foreground">Trust Profile</h2>
                <p className="mt-1 text-sm text-muted-foreground">
                    Once you verify at least one website, this instance can host a public trust page
                    at <span className="font-mono">{SITE_HOST}/u/your-handle</span> that shows your
                    verified domains and their compliance grades. Ideal for a security page or
                    footer badge.
                </p>
            </div>

            <BadgeCard profile={profile} />

            <div className="rounded-xl border border-border bg-card p-5 space-y-4">
                <div>
                    <label className="block text-sm font-medium text-foreground">Public handle</label>
                    <p className="mt-1 text-xs text-muted-foreground">
                        Lowercase letters, digits, and hyphens. 3–40 characters. Must start and end
                        with a letter or digit.
                    </p>
                </div>
                <div className="flex items-center gap-2">
                    <span className="text-sm text-muted-foreground font-mono">{SITE_HOST}/u/</span>
                    <input
                        type="text"
                        value={draftSlug}
                        onChange={(e) => setDraftSlug(e.target.value.toLowerCase())}
                        placeholder="your-handle"
                        className="flex-1 rounded-md border border-border bg-background px-3 py-2 text-sm font-mono text-foreground outline-none focus:ring-2 focus:ring-primary"
                    />
                </div>
                {!slugValid && (
                    <p className="flex items-center gap-1 text-xs text-destructive">
                        <AlertCircle className="h-3.5 w-3.5" /> Invalid handle format.
                    </p>
                )}
                <div className="flex gap-2">
                    <button
                        type="button"
                        onClick={() =>
                            save({
                                publicUsername: draftSlug.trim() === "" ? null : draftSlug.trim().toLowerCase(),
                            })
                        }
                        disabled={!slugDirty || !slugValid || saving}
                        className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 disabled:opacity-50"
                    >
                        {saving ? "Saving…" : "Save handle"}
                    </button>
                    {profile.publicUsername && profile.publicProfileEnabled && (
                        <a
                            href={`/u/${profile.publicUsername}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1.5 rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground hover:bg-accent"
                        >
                            <ExternalLink className="h-4 w-4" /> Open public page
                        </a>
                    )}
                </div>
            </div>

            <div className="rounded-xl border border-border bg-card p-5 space-y-3">
                <div className="flex items-start justify-between gap-4">
                    <div>
                        <h3 className="text-sm font-medium text-foreground">Publish trust page</h3>
                        <p className="mt-1 text-xs text-muted-foreground">
                            When enabled, anyone with the handle can view your verified sites and
                            their grades. Disable anytime — the handle stays reserved.
                        </p>
                    </div>
                    <button
                        type="button"
                        disabled={!profile.publicUsername || saving}
                        onClick={() => save({ publicProfileEnabled: !profile.publicProfileEnabled })}
                        className={`inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors ${
                            profile.publicProfileEnabled ? "bg-primary" : "bg-muted"
                        } disabled:opacity-50`}
                        aria-label="Toggle public profile"
                    >
                        <span
                            className={`inline-block h-5 w-5 transform rounded-full bg-white shadow transition-transform ${
                                profile.publicProfileEnabled ? "translate-x-5" : "translate-x-0.5"
                            }`}
                        />
                    </button>
                </div>
                {!profile.publicUsername && (
                    <p className="text-xs text-muted-foreground">
                        Set a handle above before enabling the profile.
                    </p>
                )}
            </div>

            {error && (
                <p className="flex items-center gap-2 text-sm text-destructive">
                    <AlertCircle className="h-4 w-4" /> {error}
                </p>
            )}
            {toast && <p className="text-sm text-emerald-500">{toast}</p>}
        </div>
    );
}

function BadgeCard({ profile }: { profile: Profile }) {
    if (profile.verificationStatus === "VERIFIED") {
        return (
            <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/5 p-5">
                <div className="flex items-center gap-2.5">
                    <BadgeCheck className="h-5 w-5 text-emerald-400" />
                    <p className="text-sm font-medium text-foreground">Verified user</p>
                </div>
                {profile.verifiedAt && (
                    <p className="mt-1 text-xs text-muted-foreground">
                        All your non-deleted websites have completed DNS TXT verification. Badge
                        issued {new Date(profile.verifiedAt).toLocaleDateString()}.
                    </p>
                )}
            </div>
        );
    }
    if (profile.verificationStatus === "VERIFIED_PENDING") {
        return (
            <div className="rounded-xl border border-amber-500/30 bg-amber-500/5 p-5">
                <div className="flex items-center gap-2.5">
                    <BadgeCheck className="h-5 w-5 text-amber-400" />
                    <p className="text-sm font-medium text-foreground">Verified · grace period</p>
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                    A freshly added website is still inside its 7-day verification window. Complete
                    the DNS TXT challenge to lock in the badge.
                </p>
            </div>
        );
    }
    return (
        <div className="rounded-xl border border-border bg-card p-5">
            <div className="flex items-center gap-2.5">
                <ShieldOff className="h-5 w-5 text-muted-foreground" />
                <p className="text-sm font-medium text-foreground">Not verified yet</p>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
                Verify every website you&apos;ve added via the DNS TXT challenge on each site&apos;s
                detail page. Once they&apos;re all verified, your trust badge turns on
                automatically.
            </p>
            {profile.verificationRevokedAt && (
                <p className="mt-2 text-xs text-muted-foreground">
                    Badge last revoked on{" "}
                    {new Date(profile.verificationRevokedAt).toLocaleDateString()}.
                </p>
            )}
        </div>
    );
}
