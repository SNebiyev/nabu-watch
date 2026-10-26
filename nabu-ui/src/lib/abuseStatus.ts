/**
 * Shared presentation model for abuse-report status chips.
 * Used by both the admin triage console and the owner-facing card in the
 * site expand panel so the two views never drift apart.
 */
export type AbuseStatus =
    | "PENDING"
    | "UPHELD"
    | "OWNERSHIP_PROVEN"
    | "SITE_REMOVED"
    | "DISMISSED";

export const ABUSE_STATUS_CHIP: Record<AbuseStatus, string> = {
    PENDING: "bg-amber-500/10 text-amber-500 ring-amber-500/20",
    UPHELD: "bg-orange-500/10 text-orange-500 ring-orange-500/20",
    OWNERSHIP_PROVEN: "bg-emerald-500/10 text-emerald-500 ring-emerald-500/20",
    SITE_REMOVED: "bg-red-500/10 text-red-500 ring-red-500/20",
    DISMISSED: "bg-muted text-muted-foreground ring-border",
};

/** Short, user-readable label per status. */
export const ABUSE_STATUS_OWNER_LABEL: Record<AbuseStatus, string> = {
    PENDING: "Under admin review",
    UPHELD: "Upheld — action required",
    OWNERSHIP_PROVEN: "Resolved — ownership proved",
    SITE_REMOVED: "Site removed",
    DISMISSED: "Dismissed",
};
