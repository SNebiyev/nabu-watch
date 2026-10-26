use anyhow::Result;
use owo_colors::OwoColorize;
use sqlx::PgPool;

use crate::db::queries;

pub async fn run(pool: &PgPool, url: &str, show_all: bool) -> Result<()> {
    let website = queries::find_website_by_url(pool, url).await?;

    println!("Website: {}  region={}  fetch_mode={}  lang={}",
        website.url.bold(),
        website.region_code.as_deref().unwrap_or("—"),
        website.preferred_fetch_mode.as_deref().unwrap_or("AUTO"),
        &website.accepted_languages,
    );

    let monitors = if show_all {
        // Fetch all monitors (including inactive)
        sqlx::query_as::<_, crate::db::models::Monitor>(
            "SELECT id, website_id, type, sub_type, target_url, active
             FROM monitors WHERE website_id = $1 ORDER BY type, sub_type"
        )
        .bind(website.id)
        .fetch_all(pool)
        .await?
    } else {
        queries::find_active_monitors(pool, website.id).await?
    };

    if monitors.is_empty() {
        println!("  No monitors found.");
        return Ok(());
    }

    println!("\n  {:<38}  {:<12}  {:<22}  {}", "ID", "TYPE", "SUB_TYPE", "ACTIVE");
    println!("  {}", "─".repeat(85));

    for m in &monitors {
        let active_str = if m.active {
            format!("{}", "yes".green())
        } else {
            format!("{}", "no".red())
        };
        println!("  {:<38}  {:<12}  {:<22}  {}",
            m.id, m.monitor_type, m.sub_type, active_str);
    }

    println!("\n  Total: {} monitors", monitors.len());
    Ok(())
}
