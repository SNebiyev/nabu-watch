use anyhow::Result;
use futures_util::StreamExt;
use serde::Deserialize;
use tokio::sync::mpsc;

const CHANNEL: &str = "nabu:worker-logs";

#[derive(Debug, Deserialize)]
#[allow(dead_code)]
pub struct WorkerLog {
    pub time: String,
    pub level: String,
    pub msg: String,
    // Structured attrs (optional — present when the log line has them)
    #[serde(default)]
    pub monitor_id: Option<String>,
    #[serde(default)]
    pub batch_id: Option<String>,
    #[serde(default)]
    pub domain: Option<String>,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub error: Option<String>,
    #[serde(default, rename = "type")]
    pub task_type: Option<String>,
    // Catch-all for any other fields
    #[serde(flatten)]
    pub extra: serde_json::Map<String, serde_json::Value>,
}

/// Subscribe to the worker log pub/sub channel and send parsed entries to the
/// provided channel sender. Runs until the sender is dropped or Redis disconnects.
pub async fn subscribe(
    client: &redis::Client,
    tx: mpsc::UnboundedSender<WorkerLog>,
) -> Result<()> {
    let mut pubsub = client.get_async_pubsub().await?;
    pubsub.subscribe(CHANNEL).await?;

    loop {
        let msg = pubsub.on_message().next().await;
        match msg {
            Some(msg) => {
                let payload: String = msg.get_payload()?;
                if let Ok(log) = serde_json::from_str::<WorkerLog>(&payload) {
                    if tx.send(log).is_err() {
                        break; // receiver dropped
                    }
                }
            }
            None => break, // connection closed
        }
    }
    Ok(())
}
