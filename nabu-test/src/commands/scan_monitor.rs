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
    monitor_id: Uuid,
    dry_run: bool,
    timeout_secs: u64,
    no_color: bool,
) -> Result<()> {
    let mw = queries::find_monitor_with_website(pool, monitor_id).await?;

    let batch_id = Uuid::new_v4();
    let scan_task = task::build_single_task(
        pool, &mw.website, &mw.monitor, batch_id, false,
    ).await?;

    println!("{} {} ({}) for {}  batch={}",
        "Scanning".bold(),
        mw.monitor.sub_type.cyan(),
        monitor_id.to_string().dimmed(),
        mw.website.url.bold(),
        batch_id.to_string().dimmed(),
    );

    if dry_run {
        println!("\n{}", "--- DRY RUN: task fields ---".bold());
        for (k, v) in &scan_task.fields {
            let display_val = if v.len() > 80 { format!("{}...", &v[..77]) } else { v.clone() };
            println!("  {k:25} = {display_val}");
        }
        return Ok(());
    }

    let monitor_id_str = monitor_id.to_string();
    let monitor_ids = vec![monitor_id_str.clone()];
    let batch_str = batch_id.to_string();

    // 1. Start log subscriber
    let (log_tx, mut log_rx) = mpsc::unbounded_channel();
    let log_client = redis_client.clone();
    let log_handle = tokio::spawn(async move {
        let _ = logs::subscribe(&log_client, log_tx).await;
    });

    // 2. Start result consumer
    let mut result_con = redis_client.get_multiplexed_async_connection().await?;
    let batch_for_results = batch_str.clone();
    let mid_for_results = monitor_id_str.clone();
    let mut result_handle = tokio::spawn(async move {
        result::subscribe(&mut result_con, &batch_for_results, Some(&mid_for_results), 1).await
    });

    // 3. Publish task
    let mut con = redis_client.get_multiplexed_async_connection().await?;
    let id = task::publish(&mut con, &scan_task).await?;
    println!("  {} task_id={}", "⚡ Published".dimmed(), id.dimmed());

    let start = Instant::now();
    let spinner = progress::scan_spinner("Waiting for result...");

    // 4. Stream logs + wait for result
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
        } => { vec![] }
        res = &mut result_handle => { res?? }
        _ = tokio::time::sleep(timeout) => {
            spinner.finish_with_message("Timeout");
            vec![]
        }
    };

    spinner.finish_and_clear();
    log_handle.abort();

    for r in &results {
        println!("{}", result_view::format_result(r, no_color));
    }

    let elapsed = start.elapsed().as_secs_f64();
    println!("\n  Completed in {elapsed:.1}s");
    Ok(())
}
