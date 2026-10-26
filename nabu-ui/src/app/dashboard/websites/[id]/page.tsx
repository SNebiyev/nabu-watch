"use client";

import {useCallback, useEffect, useState} from "react";
import {useParams, useRouter} from "next/navigation";
import {ArrowLeft, ChevronLeft, Loader2} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import type {WebsiteHealth} from "@/lib/types";
import {WebsiteExpandPanel} from "@/components/dashboard/WebsiteExpandPanel";
import {extractErrorMessage} from "@/lib/utils";
import {websiteCache} from "@/lib/websiteCache";

export default function WebsiteDetailPage() {
    const params = useParams<{ id: string }>();
    const router = useRouter();
    const websiteId = params.id;

    const cached = websiteCache.get(websiteId)?.website ?? null;
    const [website, setWebsite] = useState<WebsiteHealth | null>(cached);
    const [loading, setLoading] = useState(!cached);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async () => {
        try {
            const res = await apiClient(`/api/v1/websites/${websiteId}`);
            if (!res.ok) throw new Error(await extractErrorMessage(res, "Website not found"));
            const data: WebsiteHealth = await res.json();
            setWebsite(data);
            websiteCache.patch(websiteId, {website: data});
        } catch (e) {
            setError(e instanceof Error ? e.message : "Failed to load website");
        } finally {
            setLoading(false);
        }
    }, [websiteId]);

    useEffect(() => { void load(); }, [load]);

    const handleBack = useCallback(() => {
        // Use back() when we came from within the app so the list's scroll
        // position is restored. Fall back to push() for deep-links.
        if (typeof window !== "undefined" && window.history.length > 1 && document.referrer.includes(window.location.origin)) {
            router.back();
        } else {
            router.push("/dashboard");
        }
    }, [router]);

    if (loading) {
        return (
            <div className="flex items-center justify-center py-20">
                <Loader2 className="h-6 w-6 animate-spin text-muted-foreground"/>
            </div>
        );
    }

    if (error || !website) {
        return (
            <div className="pb-8">
                <button
                    onClick={handleBack}
                    className="mb-4 inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
                >
                    <ArrowLeft className="h-4 w-4"/> Back to websites
                </button>
                <div className="rounded-xl border border-red-300 dark:border-red-800/50 bg-red-50 dark:bg-red-950/30 p-4 text-sm text-red-700 dark:text-red-300">
                    {error ?? "Website not found"}
                </div>
            </div>
        );
    }

    return (
        <div className="pb-8">
            {/* Mobile back bar */}
            <button
                onClick={handleBack}
                className="md:hidden mb-3 inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
            >
                <ArrowLeft className="h-4 w-4"/> Back
            </button>

            <div className="flex glass-card">
                {/* Desktop vertical back strip */}
                <button
                    onClick={handleBack}
                    aria-label="Back to websites"
                    title="Back to websites"
                    className="hidden md:flex group shrink-0 w-10 items-center justify-center border-r border-border hover:bg-secondary/50 transition-colors rounded-l-[inherit]"
                >
                    <ChevronLeft className="h-8 w-8 text-muted-foreground/60 group-hover:text-sky-500 transition-colors"/>
                </button>

                <div className="flex-1 min-w-0">
                    <WebsiteExpandPanel
                        website={website}
                        onClose={handleBack}
                        onMonitorsUpdated={() => { void load(); }}
                    />
                </div>
            </div>
        </div>
    );
}
