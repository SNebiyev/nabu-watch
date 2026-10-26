export const MonitorSubType = {
    SSL: "SSL",
    PRIVACY_POLICY: "PRIVACY_POLICY",
    TERMS_OF_SERVICE: "TERMS_OF_SERVICE",
    IMPRESSUM: "IMPRESSUM",
    COOKIE_POLICY: "COOKIE_POLICY",
    GDPR_REQUEST: "GDPR_REQUEST",
    COOKIE_BANNER: "COOKIE_BANNER",
    TRACKER_DETECTION: "TRACKER_DETECTION",
} as const;
export type MonitorSubType = (typeof MonitorSubType)[keyof typeof MonitorSubType];

export const Severity = ["INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
export type Severity = (typeof Severity)[number];

export const FAILING_SEVERITIES: readonly Severity[] = ["MEDIUM", "HIGH", "CRITICAL"];

export type MonitorSummary = {
    monitorId: string;
    subType: MonitorSubType;
    monitorType: "SSL" | "COMPLIANCE";
};
