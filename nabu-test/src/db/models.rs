use chrono::{DateTime, Utc};
use sqlx::FromRow;
use uuid::Uuid;

#[derive(Debug, FromRow)]
pub struct Website {
    pub id: Uuid,
    pub url: String,
    pub region_code: Option<String>,
    pub preferred_fetch_mode: Option<String>,
    pub fetch_mode_detected_at: Option<DateTime<Utc>>,
    pub accepted_languages: String,
    pub screenshots_enabled: bool,
}

#[derive(Debug, FromRow)]
pub struct Monitor {
    pub id: Uuid,
    pub website_id: Uuid,
    #[sqlx(rename = "type")]
    pub monitor_type: String,
    pub sub_type: String,
    pub target_url: Option<String>,
    pub active: bool,
}

#[derive(Debug, FromRow)]
pub struct ComplianceKeyword {
    pub keyword: String,
    pub tier: String, // MANDATORY_NATIVE, OPTIONAL_NATIVE, FALLBACK_ENGLISH
}

#[derive(Debug, FromRow)]
#[allow(dead_code)]
pub struct CompliancePath {
    pub anchor_href: Option<String>,
    pub discovered_url: Option<String>,
    pub confidence: Option<String>,
}

#[derive(Debug, FromRow)]
pub struct LatestScanResult {
    pub result_json: Option<serde_json::Value>,
}

/// Monitor with its parent website loaded (for scan-monitor command).
#[derive(Debug)]
pub struct MonitorWithWebsite {
    pub monitor: Monitor,
    pub website: Website,
}
