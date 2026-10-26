import {formatDistanceToNow} from "date-fns";

/**
 * Minimum plausible timestamp for any date produced by this application.
 * Anything older than Jan 1 2020 cannot be a real `createdAt` value —
 * it means the field arrived as epoch-zero, uninitialised, or corrupt.
 *
 * Using a calendar-year floor (rather than just `<= 0`) also catches cases
 * where a numeric 0 slips through as a truthy-looking non-null value after
 * serialisation edge-cases in the Spring Boot → Jackson → STOMP pipeline.
 */
const MIN_VALID_MS = new Date("2020-01-01T00:00:00Z").getTime();

/**
 * Safely converts any date-like value coming from the backend into a
 * human-readable relative string ("2 minutes ago", "Just now", …).
 *
 * Handles every format the Spring Boot backend may emit:
 *  • ISO-8601 string  — "2025-03-28T14:30:00Z"
 *  • Epoch millis     — 1711633800000
 *  • Java array       — [2025, 3, 28, 14, 30, 0, 0]  (JSR-310 without module)
 *  • null / undefined — missing field on first WS frame
 *  • 0 / epoch zero   — uninitialised createdAt ("56 years ago" bug)
 *
 * Returns "Just now" for any value that cannot be resolved to a date after
 * year 2020.  This ensures the "56 years ago" flash never reaches the DOM,
 * regardless of which layer (REST initial load vs real-time WS push) delivers
 * the bad value.
 */
export function getSafeFormattedDate(dateValue: unknown): string {
    if (dateValue === null || dateValue === undefined || dateValue === "") {
        return "Just now";
    }

    try {
        let ms: number;

        if (Array.isArray(dateValue)) {
            // Spring Boot LocalDateTime / Instant array: [year, month(1-indexed), day, h, m, s, nano?]
            const [year, month, day, hours = 0, minutes = 0, seconds = 0] =
                dateValue as number[];
            // Use UTC construction so the result is timezone-independent
            ms = Date.UTC(year, month - 1, day, hours, minutes, seconds);
        } else if (typeof dateValue === "number") {
            ms = dateValue;
        } else {
            ms = new Date(dateValue as string).getTime();
        }

        // Guard: NaN, epoch-zero, or any timestamp too old to be real app data
        if (!Number.isFinite(ms) || ms <= 0 || ms < MIN_VALID_MS) {
            return "Just now";
        }

        return formatDistanceToNow(new Date(ms), {addSuffix: true});
    } catch {
        return "Just now";
    }
}
