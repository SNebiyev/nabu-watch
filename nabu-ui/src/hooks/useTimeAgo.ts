import {useEffect, useState} from "react";
import {getSafeFormattedDate} from "@/lib/dateUtils";

/**
 * Returns a live-updating relative timestamp string ("2 minutes ago", etc.).
 *
 * Re-computes every 30 seconds so that displayed timestamps stay accurate
 * without requiring a full page reload.  Delegates all date-parsing logic
 * to {@link getSafeFormattedDate} so every edge-case (epoch-zero, arrays,
 * null) is handled identically to static renders.
 */
export function useTimeAgo(dateValue: unknown): string {
    const [label, setLabel] = useState(() => getSafeFormattedDate(dateValue));

    useEffect(() => {
        setLabel(getSafeFormattedDate(dateValue));

        const id = setInterval(() => {
            setLabel(getSafeFormattedDate(dateValue));
        }, 30_000);

        return () => clearInterval(id);
    }, [dateValue]);

    return label;
}
