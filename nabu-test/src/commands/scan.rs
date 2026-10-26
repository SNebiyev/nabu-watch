use std::time::Instant;

use anyhow::Result;
use owo_colors::OwoColorize;
use sqlx::PgPool;
use tokio::sync::mpsc;
use uuid::Uuid;

use crate::db::queries;
use crate::display::{log_view, result_view, progress};
use crate::redis::{task, result, logs};

pub async fn run(
    pool: &PgPool,
    redis_client: &redis::Client,
    url: &str,
    only: Option<String>,
    no_ssl: bool,
    no_scout: bool,
    dry_run: bool,
    timeout_secs: u64,
    no_color: bool,
) -> Result<()> {
    let website = queries::find_website_by_url(pool, url).await?;
    let mut monitors = queries::find_active_monitors(pool, website.id).await?;

    // Apply filters
    if no_ssl {
        monitors.retain(|m| m.monitor_type != "SSL");
    }
    if let Some(ref filter) = only {
        let types: Vec<&str> = filter.split(',').map(|s| s.trim()).collect();
        monitors.retain(|m| types.iter().any(|t| m.sub_type.eq_ignore_ascii_case(t)));
    }

    if monitors.is_empty() {
        println!("No monitors match the given filters.");
        return Ok(());
    }

    let batch_id = Uuid::new_v4();
    let tasks = task::build_tasks(pool, &website, &monitors, no_scout, batch_id).await?;

    println!("{} {} monitors for {}  batch={}",
        "Scanning".bold(), tasks.len(), url.cyan(), batch_id.to_string().dimmed());

    for t in &tasks {
        let scout_tag = if t.fields.iter().any(|(k, v)| k == "isScout" && v == "true") { " [SCOUT]" } else { "" };
        println!("  {} {}{}", "→".dimmed(), t.sub_type, scout_tag.yellow());
    }

    if dry_run {
        println!("\n{}", "--- DRY RUN: task fields ---".bold());
        for t in &tasks {
            println!("\n  [{}]", t.sub_type.bold());
            for (k, v) in &t.fields {
                let display_val = if v.len() > 80 { format!("{}...", &v[..77]) } else { v.clone() };
                println!("    {k:25} = {display_val}");
            }
        }
        return Ok(());
    }

    let monitor_ids: Vec<String> = tasks.iter().map(|t| t.monitor_id.to_string()).collect();
    let batch_str = batch_id.to_string();
    let expected = tasks.len();

    // 1. Start log subscriber
    let (log_tx, mut log_rx) = mpsc::unbounded_channel();
    let log_client = redis_client.clone();
    let log_handle = tokio::spawn(async move {
        let _ = logs::subscribe(&log_client, log_tx).await;
    });

    // 2. Start result consumer (in a background task)
    let mut result_con = redis_client.get_multiplexed_async_connection().await?;
    let batch_for_results = batch_str.clone();
    let mut result_handle = tokio::spawn(async move {
        result::subscribe(&mut result_con, &batch_for_results, None, expected).await
    });

    // 3. Publish tasks
    let mut con = redis_client.get_multiplexed_async_connection().await?;
    for t in &tasks {
        let id = task::publish(&mut con, t).await?;
        println!("  {} {} task_id={}", "⚡".dimmed(), t.sub_type, id.dimmed());
    }

    let start = Instant::now();
    let spinner = progress::scan_spinner(&format!("Waiting for {expected} results..."));

    // 4. Stream logs + wait for results
    let timeout = tokio::time::Duration::from_secs(timeout_secs);
    let results = tokio::select! {
        _ = async {
            while let Some(log) = log_rx.recv().await {
                if log_view::matches_filter(&log, &batch_str, &monitor_ids) {
                    spinner.suspend(|| {
                        println!("{}", log_view::format_log(&log, no_color));
                    });
                }
            }
        } => {
            // Log subscriber ended (shouldn't happen normally)
            vec![]
        }
        res = &mut result_handle => {
            res??
        }
        _ = tokio::time::sleep(timeout) => {
            spinner.finish_with_message("Timeout waiting for results");
            vec![]
        }
    };

    spinner.finish_and_clear();
    log_handle.abort();

    // 5. Print results
    for r in &results {
        println!("{}", result_view::format_result(r, no_color));
    }

    let elapsed = start.elapsed().as_secs_f64();
    println!("{}", result_view::format_summary(&results, elapsed, no_color));

    Ok(())
}
