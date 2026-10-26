import {useEffect, useState} from "react";
import {formatCountdown} from "@/lib/utils";

/**
 * Returns a live-updating countdown string ("2h 15m", "45m", etc.).
 *
 * Re-computes every 30 seconds. Returns null when the target time is
 * in the past or not provided.
 */
export function useCountdown(dateStr: string | null | undefined): string | null {
    const [label, setLabel] = useState(() => formatCountdown(dateStr));

    useEffect(() => {
        setLabel(formatCountdown(dateStr));
        if (!dateStr) return;

        const id = setInterval(() => {
            setLabel(formatCountdown(dateStr));
        }, 30_000);

        return () => clearInterval(id);
    }, [dateStr]);

    return label;
}
