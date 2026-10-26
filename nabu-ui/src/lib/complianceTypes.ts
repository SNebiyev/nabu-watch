// ── Domain types for the Compliance Matrix Builder ────────────────────────────

export interface ComplianceRegion {
    id: string;
    code: string;
    name: string;
    active: boolean;
    globalDefault: boolean;
}

export interface ComplianceMonitorType {
    id: string;
    subTypeCode: string;
    defaultName: string;
    description: string | null;
}

/** Full rule details for one monitor type within a region, returned by the rules endpoint. */
export interface RegionRuleDetail {
    ruleId: string;
    monitorTypeId: string;
    subTypeCode: string;
    defaultName: string;
    description: string | null;
    active: boolean;
    mandatory: boolean;
    mandatoryKeywords: string[];
    optionalKeywords: string[];
}

/** Request body for creating / updating a region rule. */
export interface UpsertRuleRequest {
    active: boolean;
    mandatory: boolean;
    mandatoryKeywords: string[];
    optionalKeywords: string[];
}

/** One monitor type entry returned by the global-fallbacks endpoint. */
export interface GlobalFallbackItem {
    monitorTypeId: string;
    subTypeCode: string;
    defaultName: string;
    description: string | null;
    fallbackKeywords: string[];
}

// ── Banner keywords ────────────────────────────────────────────────────────────

export type BannerKeywordType = "BANNER_PHRASE" | "ACCEPT" | "REJECT" | "PREF";

/**
 * One (region, type) group of banner-scanner keywords returned by the
 * banner-keywords endpoint. {@code regionCode === "GLOBAL"} represents the
 * English fallback rows where {@code region_id IS NULL}.
 */
export interface BannerKeywordGroup {
    regionCode: string;
    regionName: string;
    keywordType: BannerKeywordType;
    keywords: string[];
}

// ── Local UI state ─────────────────────────────────────────────────────────────

/**
 * Per-card mutable state used by the Region Rule Builder.
 * Merges a ComplianceMonitorType with the current RegionRule (if any).
 */
export interface MonitorRuleCardState {
    monitorTypeId: string;
    subTypeCode: string;
    defaultName: string;
    description: string | null;
    ruleId: string | null;
    enabled: boolean;
    mandatory: boolean;
    mandatoryKeywords: string[];
    optionalKeywords: string[];
}
