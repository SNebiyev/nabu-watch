use anyhow::Result;
use owo_colors::OwoColorize;
use tokio::sync::mpsc;

use crate::display::log_view;
use crate::redis::logs;

pub async fn run(
    redis_client: &redis::Client,
    filter: Option<String>,
    no_color: bool,
) -> Result<()> {
    println!("{} Watching nabu:worker-logs (Ctrl+C to stop)...\n",
        "👁".dimmed());

    let (tx, mut rx) = mpsc::unbounded_channel();
    let client = redis_client.clone();
    let _handle = tokio::spawn(async move {
        let _ = logs::subscribe(&client, tx).await;
    });

    while let Some(log) = rx.recv().await {
        // Apply text filter if specified
        if let Some(ref f) = filter {
            let haystack = format!("{} {} {}", log.level, log.msg,
                log.monitor_id.as_deref().unwrap_or(""));
            if !haystack.to_lowercase().contains(&f.to_lowercase()) {
                continue;
            }
        }
        println!("{}", log_view::format_log(&log, no_color));
    }

    Ok(())
}
