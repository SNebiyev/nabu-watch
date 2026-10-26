use anyhow::{Context, Result};
use chrono::{Duration, Utc};
use sqlx::PgPool;
use uuid::Uuid;

use super::models::*;

/// Find a website by its URL (exact match).
pub async fn find_website_by_url(pool: &PgPool, url: &str) -> Result<Website> {
    sqlx::query_as::<_, Website>(
        "SELECT id, url, region_code, preferred_fetch_mode, fetch_mode_detected_at,
                accepted_languages, screenshots_enabled
         FROM websites WHERE url = $1"
    )
    .bind(url)
    .fetch_one(pool)
    .await
    .context(format!("website not found: {url}"))
}

/// Find all active monitors for a website.
pub async fn find_active_monitors(pool: &PgPool, website_id: Uuid) -> Result<Vec<Monitor>> {
    Ok(sqlx::query_as::<_, Monitor>(
        "SELECT id, website_id, type, sub_type, target_url, active
         FROM monitors
         WHERE website_id = $1 AND active = true"
    )
    .bind(website_id)
    .fetch_all(pool)
    .await?)
}

/// Find a monitor by ID with its parent website.
pub async fn find_monitor_with_website(pool: &PgPool, monitor_id: Uuid) -> Result<MonitorWithWebsite> {
    let monitor = sqlx::query_as::<_, Monitor>(
        "SELECT id, website_id, type, sub_type, target_url, active
         FROM monitors WHERE id = $1"
    )
    .bind(monitor_id)
    .fetch_one(pool)
    .await
    .context(format!("monitor not found: {monitor_id}"))?;

    let website = sqlx::query_as::<_, Website>(
        "SELECT id, url, region_code, preferred_fetch_mode, fetch_mode_detected_at,
                accepted_languages, screenshots_enabled
         FROM websites WHERE id = $1"
    )
    .bind(monitor.website_id)
    .fetch_one(pool)
    .await
    .context("website not found for monitor")?;

    Ok(MonitorWithWebsite { monitor, website })
}

/// Find native keywords (MANDATORY_NATIVE + OPTIONAL_NATIVE) for a region and sub-type.
/// Translates the JPA query: ComplianceKeywordRepository.findNativeByRegionAndSubType()
pub async fn find_native_keywords(pool: &PgPool, region_code: &str, sub_type_code: &str) -> Result<Vec<ComplianceKeyword>> {
    Ok(sqlx::query_as::<_, ComplianceKeyword>(
        "SELECT ck.keyword, ck.tier
         FROM compliance_keywords ck
         JOIN compliance_region_rules rr ON rr.id = ck.region_rule_id
         JOIN compliance_regions r ON r.id = rr.region_id
         JOIN compliance_monitor_types mt ON mt.id = rr.monitor_type_id
         WHERE r.code = $1
           AND mt.sub_type_code = $2
           AND rr.active = true"
    )
    .bind(region_code.to_uppercase())
    .bind(sub_type_code)
    .fetch_all(pool)
    .await?)
}

/// Find English fallback keywords for a sub-type (region_rule IS NULL).
/// Translates: ComplianceKeywordRepository.findFallbackKeywordsBySubTypeCode()
pub async fn find_fallback_keywords(pool: &PgPool, sub_type_code: &str) -> Result<Vec<String>> {
    let rows: Vec<(String,)> = sqlx::query_as(
        "SELECT ck.keyword
         FROM compliance_keywords ck
         JOIN compliance_monitor_types mt ON mt.id = ck.monitor_type_id
         WHERE mt.sub_type_code = $1
           AND ck.region_rule_id IS NULL"
    )
    .bind(sub_type_code)
    .fetch_all(pool)
    .await?;

    Ok(rows.into_iter().map(|(kw,)| kw).collect())
}

/// Find the global default region code (used as fallback when a region has no keywords).
pub async fn find_global_default_region(pool: &PgPool) -> Result<String> {
    let row: (String,) = sqlx::query_as(
        "SELECT code FROM compliance_regions WHERE global_default = true LIMIT 1"
    )
    .fetch_one(pool)
    .await
    .context("no global default region found")?;

    Ok(row.0)
}

/// Find a high/medium confidence compliance path for fast-path hints.
pub async fn find_compliance_path(pool: &PgPool, website_id: Uuid, sub_type: &str) -> Result<Option<CompliancePath>> {
    Ok(sqlx::query_as::<_, CompliancePath>(
        "SELECT anchor_href, discovered_url, confidence
         FROM website_compliance_paths
         WHERE website_id = $1 AND sub_type = $2
           AND confidence IN ('high', 'medium')"
    )
    .bind(website_id)
    .bind(sub_type)
    .fetch_optional(pool)
    .await?)
}

/// Find the latest scan result for a monitor (for AI hint extraction).
pub async fn find_latest_scan(pool: &PgPool, monitor_id: Uuid) -> Result<Option<LatestScanResult>> {
    Ok(sqlx::query_as::<_, LatestScanResult>(
        "SELECT result_json
         FROM scan_logs
         WHERE monitor_id = $1
         ORDER BY created_at DESC
         LIMIT 1"
    )
    .bind(monitor_id)
    .fetch_optional(pool)
    .await?)
}

/// Build the effective keyword lists for a compliance task, replicating
/// ComplianceTaskBuilderService.buildPayload() logic.
pub async fn build_keyword_payload(
    pool: &PgPool,
    region_code: Option<&str>,
    sub_type: &str,
    accepted_languages: &str,
) -> Result<KeywordPayload> {
    // COOKIE_BANNER and TRACKER_DETECTION don't use keywords
    if sub_type == "COOKIE_BANNER" || sub_type == "TRACKER_DETECTION" {
        return Ok(KeywordPayload::empty());
    }

    // Try region-specific keywords, fall back to global default
    let native = if let Some(rc) = region_code.filter(|s| !s.is_empty()) {
        let kws = find_native_keywords(pool, rc, sub_type).await?;
        if kws.is_empty() {
            let global = find_global_default_region(pool).await?;
            find_native_keywords(pool, &global, sub_type).await?
        } else {
            kws
        }
    } else {
        let global = find_global_default_region(pool).await?;
        find_native_keywords(pool, &global, sub_type).await?
    };

    let required: Vec<String> = native.iter()
        .filter(|k| k.tier == "MANDATORY_NATIVE")
        .map(|k| k.keyword.clone())
        .collect();

    let optional: Vec<String> = native.iter()
        .filter(|k| k.tier == "OPTIONAL_NATIVE")
        .map(|k| k.keyword.clone())
        .collect();

    // English-region heuristic: suppress fallback for GB/US
    let is_english_region = region_code
        .map(|rc| matches!(rc.to_uppercase().as_str(), "GB" | "US"))
        .unwrap_or(false);

    let fallback = if is_english_region {
        vec![]
    } else {
        find_fallback_keywords(pool, sub_type).await?
    };

    // Apply AcceptedLanguages transformation
    Ok(match accepted_languages {
        "ENGLISH_ONLY" if !fallback.is_empty() => KeywordPayload {
            required: fallback,
            optional: vec![],
            fallback: vec![],
        },
        "BOTH_ACCEPTED" if !fallback.is_empty() => {
            let mut merged = required;
            merged.extend(fallback);
            KeywordPayload {
                required: merged,
                optional,
                fallback: vec![],
            }
        }
        _ => KeywordPayload { required, optional, fallback },
    })
}

/// Check if the fetch mode hint should be injected (HEADLESS + within 7-day expiry).
pub fn should_inject_fetch_mode_hint(website: &Website) -> bool {
    website.preferred_fetch_mode.as_deref() == Some("HEADLESS")
        && website.fetch_mode_detected_at
            .map(|at| at > Utc::now() - Duration::days(7))
            .unwrap_or(false)
}

/// Banner keywords for a COOKIE_BANNER task payload: a region's own rows plus
/// the GLOBAL English fallback rows, grouped by keyword_type. Mirrors
/// BannerKeywordService.buildPayload() on the Java side in a single query.
pub async fn build_banner_keyword_payload(
    pool: &PgPool,
    region_code: Option<&str>,
) -> Result<BannerKeywordPayload> {
    let normalized = region_code
        .filter(|s| !s.is_empty())
        .map(|s| s.to_uppercase())
        .unwrap_or_else(|| "GLOBAL".to_string());

    let rows: Vec<(String, String)> = sqlx::query_as(
        "SELECT bk.keyword_type, bk.keyword
         FROM banner_keywords bk
         JOIN compliance_regions r ON r.id = bk.region_id
         WHERE bk.active = true
           AND r.code IN ($1, 'GLOBAL')"
    )
    .bind(&normalized)
    .fetch_all(pool)
    .await?;

    let mut payload = BannerKeywordPayload::empty();
    for (kw_type, kw) in rows {
        match kw_type.as_str() {
            "BANNER_PHRASE" => payload.banner_phrases.push(kw),
            "ACCEPT" => payload.accept_terms.push(kw),
            "REJECT" => payload.reject_terms.push(kw),
            "PREF" => payload.pref_terms.push(kw),
            _ => {}
        }
    }
    Ok(payload)
}

#[derive(Debug)]
pub struct KeywordPayload {
    pub required: Vec<String>,
    pub optional: Vec<String>,
    pub fallback: Vec<String>,
}

impl KeywordPayload {
    pub fn empty() -> Self {
        Self { required: vec![], optional: vec![], fallback: vec![] }
    }
}

#[derive(Debug)]
pub struct BannerKeywordPayload {
    pub banner_phrases: Vec<String>,
    pub accept_terms: Vec<String>,
    pub reject_terms: Vec<String>,
    pub pref_terms: Vec<String>,
}

impl BannerKeywordPayload {
    pub fn empty() -> Self {
        Self {
            banner_phrases: vec![],
            accept_terms: vec![],
            reject_terms: vec![],
            pref_terms: vec![],
        }
    }

    pub fn is_empty(&self) -> bool {
        self.banner_phrases.is_empty()
            && self.accept_terms.is_empty()
            && self.reject_terms.is_empty()
            && self.pref_terms.is_empty()
    }
}
