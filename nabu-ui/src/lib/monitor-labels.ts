/** Short labels for monitor sub-types, used in admin feed pills and badges. */
export const SUBTYPE_SHORT: Record<string, string> = {
    SSL: "SSL",
    PRIVACY_POLICY: "PP",
    TERMS_OF_SERVICE: "ToS",
    IMPRESSUM: "IMP",
    COOKIE_POLICY: "CKP",
    GDPR_REQUEST: "GDPR",
    COOKIE_BANNER: "CKBNR",
    TRACKER_DETECTION: "TRKR",
};

export function severityColor(s: string) {
    if (s === "CRITICAL") return "text-red-600 bg-red-100 ring-red-200 dark:text-red-400 dark:bg-red-950/60 dark:ring-red-900/40";
    if (s === "HIGH") return "text-orange-600 bg-orange-100 ring-orange-200 dark:text-orange-400 dark:bg-orange-950/60 dark:ring-orange-900/40";
    if (s === "MEDIUM") return "text-yellow-600 bg-yellow-100 ring-yellow-200 dark:text-yellow-400 dark:bg-yellow-950/60 dark:ring-yellow-900/40";
    return "text-green-600 bg-green-100 ring-green-200 dark:text-green-400 dark:bg-green-950/60 dark:ring-green-900/40";
}

export function isFailing(severity: string) {
    return severity === "CRITICAL" || severity === "HIGH";
}
