/** "INACTIVE" = soft-disabled via Manage Monitors; excluded from health calculation. */
export type MonitorStatus = "PASSING" | "FAILING" | "PENDING" | "INACTIVE";
export type MonitorType = "SSL" | "COMPLIANCE";
export type MonitorSubType =
  | "SSL"
  | "PRIVACY_POLICY"
  | "TERMS_OF_SERVICE"
  | "IMPRESSUM"
  | "COOKIE_POLICY"
    | "GDPR_REQUEST"
    | "COOKIE_BANNER"
    | "TRACKER_DETECTION";

export interface MonitorHealth {
  id: string;
  type: MonitorType;
  subType: MonitorSubType;
  status: MonitorStatus;
    /**
     * Single source of truth for display status — includes threshold-based
     * warning detection. Possible values: "PASSING", "FAILING", "WARNING",
     * "PENDING", "INACTIVE". Use this for all status rendering.
     */
    effectiveStatus: MonitorStatus | "WARNING";
  lastCheckedAt: string | null;
  severity?: "CRITICAL" | "HIGH" | "MEDIUM" | "INFO" | null;
  /** false = soft-disabled via Manage Monitors; default true */
  active: boolean;
    /** true when native-language keywords are missing but English equivalents were found */
    languageInconsistency?: boolean;
    /**
     * Total number of missing keywords in the latest scan.
     * 0 means either fully compliant or no scan has run yet (check hasKeywordData).
     */
    missingKeywordCount?: number;
    /**
     * Number of keywords successfully found in the latest scan.
     * When > 0 the monitor is GREEN regardless of missingKeywordCount —
     * threshold-based success: finding any native-language legal document is compliant.
     * 0 for SSL monitors or when no scan has run yet.
     */
    foundKeywordCount?: number;
    /**
     * true when the latest compliance scan retrieved keyword data (found/missing/broken
     * arrays are not all empty).  false means the scan ran but got no content
     * (INCOMPLETE — page was likely blocked by a WAF).
     */
    hasKeywordData?: boolean;
    // ── Audit fields ──────────────────────────────────────────────────────────
    /** When this monitor was created (ISO 8601). null for legacy monitors. */
    createdAt?: string | null;
    /** Email of the user who created this monitor. null for legacy/system-created monitors. */
    createdByEmail?: string | null;
    /** How the latest scan was triggered: "MANUAL" or "SCHEDULED". null if no scan yet. */
    lastTriggerType?: "MANUAL" | "SCHEDULED" | null;
    /** Computed next automatic scan time (ISO 8601). null for new monitors. */
    nextScanAt?: string | null;
    // ── Override fields ───────────────────────────────────────────────────────
    /** Whether a manual severity override is active for this monitor. */
    hasOverride?: boolean;
    /** True when the override's baseline severity no longer matches the latest scan result. */
    overrideMismatch?: boolean;
    /** Details of the active override; null when no override exists. */
    overrideInfo?: OverrideInfo | null;
    // ── Status change (vs previous scan) ──────────────────────────────────────
    /** Severity of the monitor's immediately-preceding scan. null on first scan. */
    previousSeverity?: "CRITICAL" | "HIGH" | "MEDIUM" | "INFO" | "LOW" | null;
    /** How the latest scan compares to the previous one. null when no scan yet. */
    statusChange?:
        | "FIRST_SCAN"
        | "UNCHANGED"
        | "IMPROVED"
        | "REGRESSED"
        | "RECOVERED"
        | "REGRESSED_TO_FAILING"
        | null;
    /** Non-standard term that AI semantically verified as equivalent to a required keyword (e.g. "Diretiva de Privacidade"). null when AI verification was not used. */
    aiVerifiedTerm?: string | null;
    /** One-sentence AI explanation for why the non-standard term was accepted. null when AI verification was not used. */
    aiVerificationReasoning?: string | null;
    /** True when this scan was completed via fast-path verification against a stored compliance path. */
    fastPathVerified?: boolean;
    /** True when the latest scan failed due to a WAF (Web Application Firewall) block. */
    wafBlocked?: boolean;
}

/** Compact summary of an active severity override. */
export interface OverrideInfo {
    baselineSeverity: string;
    overrideSeverity: string;
    overriddenByEmail: string;
    overriddenAt: string;
    note?: string | null;
}

/** Returned by PUT /api/v1/websites/{id}/monitors */
export interface SyncMonitorsResult {
  websiteId: string;
  activated: string[];
  deactivated: string[];
  unchanged: string[];
}

export interface WebsiteHealth {
  id: string;
  url: string;
  region: string;
  status: MonitorStatus;
  /** Website entity status — "ACTIVE" or "PAUSED". Used by Admin settings modal. */
  websiteStatus?: "ACTIVE" | "PAUSED";
  lastCheckedAt: string | null;
  monitors: MonitorHealth[];
  /**
   * Email of the user who owns this website.
   * Compare against the authenticated user's email to determine ownership:
   * if different, the secure-delete flow (OTP step-up) is required.
   */
  ownerEmail?: string;
    acceptedLanguages?: "NATIVE_ONLY" | "ENGLISH_ONLY" | "BOTH_ACCEPTED";
    screenshotsEnabled?: boolean;
    /** Weighted compliance grade letter (e.g. "A+", "B", "F"). null until first scan. */
    complianceGrade?: string | null;
    /** Numeric compliance score 0–100. null until first scan. */
    complianceScore?: number | null;
    /** DNS TXT ownership verification mirror. Drives the dashboard-row badge. */
    verificationStatus?: "UNVERIFIED" | "PENDING" | "VERIFIED" | "EXPIRED";
}

/** Returned by GET /api/v1/websites/{id}/latest-scans */
export interface LatestScanItem {
  scanLogId: string | null;
  monitorId: string;
  monitorType: MonitorType;
  subType: MonitorSubType;
  status: MonitorStatus;
    /** Single source of truth for display status — includes threshold-based warning detection. */
    effectiveStatus: MonitorStatus | "WARNING";
  severity: "CRITICAL" | "HIGH" | "MEDIUM" | "INFO" | null;
  scannedAt: string | null;
  resultJson: Record<string, unknown> | null;
    /** Set when the monitor scans a specific page instead of the site homepage. */
    targetUrl?: string | null;
    // ── Audit fields ──────────────────────────────────────────────────────────
    /** How this scan was triggered: "MANUAL" or "SCHEDULED". null for legacy rows. */
    triggerType?: "MANUAL" | "SCHEDULED" | null;
    /** Email of the user who triggered this scan. null for scheduled scans. */
    triggeredByEmail?: string | null;
    /** Computed next automatic scan time (ISO 8601). null for new monitors. */
    nextScanAt?: string | null;
    // ── Override fields ───────────────────────────────────────────────────────
    /** Whether a manual severity override is active for this monitor. */
    hasOverride?: boolean;
    /** True when the override's baseline severity no longer matches the latest scan result. */
    overrideMismatch?: boolean;
    /** Details of the active override; null when no override exists. */
    overrideInfo?: OverrideInfo | null;
    // ── Status change (vs previous scan) ──────────────────────────────────────
    /** Severity of the monitor's immediately-preceding scan. null on first scan. */
    previousSeverity?: "CRITICAL" | "HIGH" | "MEDIUM" | "INFO" | "LOW" | null;
    /** How the latest scan compares to the previous one. null when no scan yet. */
    statusChange?:
        | "FIRST_SCAN"
        | "UNCHANGED"
        | "IMPROVED"
        | "REGRESSED"
        | "RECOVERED"
        | "REGRESSED_TO_FAILING"
        | null;
    /** Non-standard term that AI semantically verified as equivalent to a required keyword (e.g. "Diretiva de Privacidade"). null when AI verification was not used. */
    aiVerifiedTerm?: string | null;
    /** One-sentence AI explanation for why the non-standard term was accepted. null when AI verification was not used. */
    aiVerificationReasoning?: string | null;
    /** True when this scan was completed via fast-path verification against a stored compliance path. */
    fastPathVerified?: boolean;
    /** True when the latest scan failed due to a WAF (Web Application Firewall) block. */
    wafBlocked?: boolean;
}

/** Returned by GET /api/v1/websites/{id}/scan-history */
export interface ScanBatch {
    /** UUID string for modern scans; "legacy-<epoch60>" for legacy rows */
    batchId: string;
    /** ISO 8601 — earliest createdAt in the batch */
    timestamp: string;
    scanType: "FULL" | "PARTIAL";
    scans: LatestScanItem[];
    // ── Audit fields ──────────────────────────────────────────────────────────
    /** How this batch was triggered: "MANUAL" or "SCHEDULED". null for legacy batches. */
    triggerType?: "MANUAL" | "SCHEDULED" | null;
    /** Email of the user who triggered this batch. null for scheduled scans. */
    triggeredByEmail?: string | null;
}

export interface DashboardStats {
  totalSites: number;
  failingSites: number;
  passingSites: number;
  pendingSites: number;
  sslIssues: number;
  complianceIssues: number;
    recoveredCount: number;
    degradedCount: number;
}

/** Paginated response from GET /api/v1/websites */
export interface WebsitePageResponse {
  content: WebsiteHealth[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
  /** Global stats over the URL-matched set (before status/monitor filter). */
  stats: DashboardStats;
}

// ── Admin types ───────────────────────────────────────────────────────────────

export interface AdminStats {
  totalUsers: number;
  totalWebsites: number;
  totalMonitors: number;
  activeScansLastHour: number;
  systemFailureRate: number; // percentage, e.g. 3.14
}

export type UserAccountStatus =
  | "ACTIVE"
  | "PENDING_VERIFICATION"
  | "SUSPENDED"
  | "DELETED";

export interface UserSummaryItem {
  id: string;
  fullName: string;
  email: string;
  role: "USER" | "ADMIN";
  status: UserAccountStatus;
  createdAt: string;
  siteCount: number;
    lastLoginAt?: string | null;
}

export interface UserPageResponse {
    content: UserSummaryItem[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

export interface UserWebsiteItem {
    id: string;
    url: string;
    region: string;
    status: "ACTIVE" | "PAUSED";
}

export interface ScanActivityItem {
    scanLogId: string;
    monitorId: string;
    websiteId: string;
    websiteUrl: string;
    monitorType: string;
    subType: string;
    severity: string;
    scannedAt: string;
}

/** Returned by GET /api/v1/admin/monitor-settings/intervals */
export interface IntervalConfigResponse {
    subTypeCode: MonitorSubType | string;
    autoScanIntervalMinutes: number;
}

/** Returned by GET /api/v1/websites/{id}/compliance-trend */
export interface ComplianceTrendBucket {
    period: string;
    totalScans: number;
    passingCount: number;
    failingCount: number;
    passRate: number;
}

// ── Reports ────────────────────────────────────────────────────────────────────

export interface ReportedScanItem {
    scanLogId: string;
    websiteUrl: string;
    orgName: string;
    monitorType: string;
    subType: string | null;
    severity: string;
    scannedAt: string;
    /** null if the reporter's account was deleted */
    reporterEmail: string | null;
    notes: string;
    reportedAt: string;
    resultJson: Record<string, unknown> | null;
}

export interface ReportedScanPageResponse {
    content: ReportedScanItem[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

