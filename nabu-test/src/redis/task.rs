use anyhow::Result;
use sqlx::PgPool;
use uuid::Uuid;

use crate::db::models::*;
use crate::db::queries;

const STREAM_KEY: &str = "nabu:scan-tasks";

/// A fully-built scan task ready to be published to Redis.
#[derive(Debug)]
pub struct ScanTask {
    pub monitor_id: Uuid,
    pub sub_type: String,
    pub fields: Vec<(String, String)>,
}

impl ScanTask {
    pub fn field_map(&self) -> Vec<(&str, &str)> {
        self.fields.iter().map(|(k, v)| (k.as_str(), v.as_str())).collect()
    }
}

/// Build scan tasks for all monitors of a website, replicating TaskProducer.enqueue() logic.
/// Returns tasks in scout-first order (homepage-parsing compliance → banner/tracker → SSL).
pub async fn build_tasks(
    pool: &PgPool,
    website: &Website,
    monitors: &[Monitor],
    no_scout: bool,
    batch_id: Uuid,
) -> Result<Vec<ScanTask>> {
    // Sort: homepage-parsing compliance first (scout candidates), then banner/tracker, then SSL
    let mut sorted: Vec<&Monitor> = monitors.iter().collect();
    sorted.sort_by_key(|m| match m.sub_type.as_str() {
        "SSL" => 2,
        "COOKIE_BANNER" | "TRACKER_DETECTION" => 1,
        _ => 0, // homepage-parsing compliance types
    });

    let mut tasks = Vec::new();
    let mut scout_assigned = false;

    for monitor in sorted {
        let is_scout = !no_scout
            && !scout_assigned
            && monitor.monitor_type == "COMPLIANCE"
            && !matches!(monitor.sub_type.as_str(), "COOKIE_BANNER" | "TRACKER_DETECTION" | "SSL");
        if is_scout {
            scout_assigned = true;
        }

        let task = build_single_task(pool, website, monitor, batch_id, is_scout).await?;
        tasks.push(task);
    }

    Ok(tasks)
}

/// Build a single scan task for one monitor.
pub async fn build_single_task(
    pool: &PgPool,
    website: &Website,
    monitor: &Monitor,
    batch_id: Uuid,
    is_scout: bool,
) -> Result<ScanTask> {
    let mut fields: Vec<(String, String)> = vec![
        ("monitorId".into(), monitor.id.to_string()),
        ("websiteId".into(), website.id.to_string()),
        ("websiteUrl".into(), website.url.clone()),
        ("regionCode".into(), website.region_code.clone().unwrap_or_default()),
        ("monitorType".into(), monitor.monitor_type.clone()),
        ("subType".into(), monitor.sub_type.clone()),
        ("batchId".into(), batch_id.to_string()),
        ("screenshotsEnabled".into(), website.screenshots_enabled.to_string()),
    ];

    if is_scout {
        fields.push(("isScout".into(), "true".into()));
    }

    if let Some(ref target) = monitor.target_url {
        if !target.is_empty() {
            fields.push(("targetUrl".into(), target.clone()));
        }
    }

    // Keyword resolution for COMPLIANCE monitors
    if monitor.monitor_type == "COMPLIANCE" {
        let payload = queries::build_keyword_payload(
            pool,
            website.region_code.as_deref(),
            &monitor.sub_type,
            &website.accepted_languages,
        ).await?;

        if !payload.required.is_empty() {
            fields.push(("requiredKeywords".into(), serde_json::to_string(&payload.required)?));
        }
        if !payload.optional.is_empty() {
            fields.push(("optionalKeywords".into(), serde_json::to_string(&payload.optional)?));
        }
        if !payload.fallback.is_empty() {
            fields.push(("fallbackKeywords".into(), serde_json::to_string(&payload.fallback)?));
        }
    }

    // Fetch mode hint
    if queries::should_inject_fetch_mode_hint(website) {
        fields.push(("fetchModeHint".into(), "HEADLESS".into()));
    }

    // Compliance path hints (non-banner, non-targeted)
    if monitor.monitor_type == "COMPLIANCE"
        && monitor.sub_type != "COOKIE_BANNER"
        && monitor.target_url.is_none()
    {
        if let Some(path) = queries::find_compliance_path(pool, website.id, &monitor.sub_type).await? {
            if let (Some(href), Some(url)) = (path.anchor_href, path.discovered_url) {
                fields.push(("pathAnchorHref".into(), href));
                fields.push(("pathDiscoveredURL".into(), url));
            }
        }
    }

    // AI hints from previous scan
    if monitor.monitor_type == "COMPLIANCE" && monitor.sub_type != "COOKIE_BANNER" {
        if let Some(scan) = queries::find_latest_scan(pool, monitor.id).await? {
            inject_ai_hints(&mut fields, &monitor.sub_type, scan.result_json);
        }
    }

    // Banner keywords (from banner_keywords DB table) + AI hints
    if monitor.sub_type == "COOKIE_BANNER" {
        let banner = queries::build_banner_keyword_payload(
            pool,
            website.region_code.as_deref(),
        ).await?;
        if !banner.is_empty() {
            if !banner.banner_phrases.is_empty() {
                fields.push(("bannerPhrases".into(), serde_json::to_string(&banner.banner_phrases)?));
            }
            if !banner.accept_terms.is_empty() {
                fields.push(("bannerAcceptTerms".into(), serde_json::to_string(&banner.accept_terms)?));
            }
            if !banner.reject_terms.is_empty() {
                fields.push(("bannerRejectTerms".into(), serde_json::to_string(&banner.reject_terms)?));
            }
            if !banner.pref_terms.is_empty() {
                fields.push(("bannerPrefTerms".into(), serde_json::to_string(&banner.pref_terms)?));
            }
        }

        if let Some(scan) = queries::find_latest_scan(pool, monitor.id).await? {
            inject_banner_ai_hints(&mut fields, scan.result_json);
        }
    }

    Ok(ScanTask {
        monitor_id: monitor.id,
        sub_type: monitor.sub_type.clone(),
        fields,
    })
}

/// Publish a task to the Redis scan-tasks stream.
pub async fn publish(con: &mut redis::aio::MultiplexedConnection, task: &ScanTask) -> Result<String> {
    let items: Vec<(&str, &str)> = task.field_map();
    let id: String = redis::cmd("XADD")
        .arg(STREAM_KEY)
        .arg("*")
        .arg(&items)
        .query_async(con)
        .await?;
    Ok(id)
}

fn inject_ai_hints(fields: &mut Vec<(String, String)>, sub_type: &str, json: Option<serde_json::Value>) {
    let json = match json {
        Some(serde_json::Value::Object(m)) => m,
        _ => return,
    };

    let via = json.get("scanned_via").and_then(|v| v.as_str()).unwrap_or("");

    // Compliance AI hints (non-banner, non-targeted)
    if sub_type != "COOKIE_BANNER" {
        let has_discovery = via.contains("AI_DISCOVERY") || via == "AI_HINT_VERIFIED";
        let has_semantic = via.contains("AI_SEMANTIC_VERIFIED") || via.contains("AI_SEMANTIC_CACHED");

        if has_discovery {
            if let Some(url) = json.get("ai_discovered_url").and_then(|v| v.as_str()).filter(|s| !s.is_empty()) {
                fields.push(("aiHintDiscoveredURL".into(), url.to_string()));
            }
            if let Some(hash) = json.get("ai_discovered_content_hash").and_then(|v| v.as_str()).filter(|s| !s.is_empty()) {
                fields.push(("aiHintContentHash".into(), hash.to_string()));
            }
        }
        if has_semantic {
            if let (Some(href), Some(hash)) = (
                json.get("ai_verified_link_href").and_then(|v| v.as_str()).filter(|s| !s.is_empty()),
                json.get("ai_verified_homepage_hash").and_then(|v| v.as_str()).filter(|s| !s.is_empty()),
            ) {
                fields.push(("aiHintVerifiedLinkHref".into(), href.to_string()));
                fields.push(("aiHintVerifiedHomepageHash".into(), hash.to_string()));
            }
        }
    }
}

fn inject_banner_ai_hints(fields: &mut Vec<(String, String)>, json: Option<serde_json::Value>) {
    let json = match json {
        Some(serde_json::Value::Object(m)) => m,
        _ => return,
    };

    let via = json.get("scanned_via").and_then(|v| v.as_str()).unwrap_or("");
    if via != "AI_ASSISTED" && via != "AI_HINT_VERIFIED" {
        return;
    }

    if let Some(sel) = json.get("banner_selector").and_then(|v| v.as_str()).filter(|s| !s.is_empty()) {
        fields.push(("aiHintSelector".into(), sel.to_string()));
    }
    if let Some(accept) = json.get("accept_button_label").and_then(|v| v.as_str()).filter(|s| !s.is_empty()) {
        fields.push(("aiHintAcceptLabel".into(), accept.to_string()));
    }
    if let Some(reject) = json.get("reject_button_label").and_then(|v| v.as_str()).filter(|s| !s.is_empty()) {
        fields.push(("aiHintRejectLabel".into(), reject.to_string()));
    }
}
