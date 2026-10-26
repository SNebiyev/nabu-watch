"use client";

import {useEffect, useState} from "react";
import {AlertTriangle, Loader2} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import type {WebsiteHealth} from "@/lib/types";
import {WebsiteExpandPanel} from "./WebsiteExpandPanel";
import {extractErrorMessage} from "@/lib/utils";

interface Props {
    websiteId: string;
    onClose: () => void;
}

export function WebsiteOverviewLoader({websiteId, onClose}: Props) {
    const [website, setWebsite] = useState<WebsiteHealth | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        setError(null);
        apiClient(`/api/v1/websites/${websiteId}`)
            .then(async res => {
                if (!res.ok) throw new Error(await extractErrorMessage(res, "Website not found"));
                return res.json() as Promise<WebsiteHealth>;
            })
            .then(w => { if (!cancelled) setWebsite(w); })
            .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : "Failed to load"); })
            .finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
    }, [websiteId]);

    if (loading) {
        return (
            <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
                <Loader2 className="h-5 w-5 animate-spin"/>
                <span className="text-sm">Loading website overview…</span>
            </div>
        );
    }
    if (error || !website) {
        return (
            <div className="flex flex-col items-center gap-3 py-16 text-center">
                <AlertTriangle className="h-6 w-6 text-muted-foreground"/>
                <p className="text-sm text-muted-foreground">{error ?? "Website not found"}</p>
            </div>
        );
    }
    return <WebsiteExpandPanel website={website} onClose={onClose}/>;
}
