use anyhow::Result;
use serde::Deserialize;

const STREAM_KEY: &str = "nabu:scan-results";

#[derive(Debug, Deserialize)]
#[allow(dead_code)]
pub struct ScanResultMessage {
    pub task_id: String,
    pub monitor_id: String,
    pub status: String,
    #[serde(default)]
    pub payload: serde_json::Map<String, serde_json::Value>,
    pub published_at: String,
    #[serde(default)]
    pub batch_id: Option<String>,
}

/// Get the current last entry ID of the results stream, so we can start
/// reading from this point forward without missing entries added between
/// XREAD calls. Returns "0-0" if the stream doesn't exist yet.
pub async fn get_last_id(con: &mut redis::aio::MultiplexedConnection) -> Result<String> {
    let reply: redis::Value = redis::cmd("XREVRANGE")
        .arg(STREAM_KEY)
        .arg("+")
        .arg("-")
        .arg("COUNT")
        .arg(1_u64)
        .query_async(con)
        .await?;

    if let redis::Value::Array(entries) = reply {
        if let Some(redis::Value::Array(parts)) = entries.into_iter().next() {
            if let Some(redis::Value::BulkString(id_bytes)) = parts.into_iter().next() {
                return Ok(String::from_utf8_lossy(&id_bytes).to_string());
            }
        }
    }
    Ok("0-0".to_string())
}

/// Subscribe to the scan-results stream and yield results matching the given
/// batch_id or monitor_id. Uses XREAD (not XREADGROUP) to avoid stealing
/// messages from the Java ResultConsumer.
pub async fn subscribe(
    con: &mut redis::aio::MultiplexedConnection,
    batch_id: &str,
    monitor_filter: Option<&str>,
    expected_count: usize,
) -> Result<Vec<ScanResultMessage>> {
    // Anchor to the current stream end BEFORE tasks are published.
    let mut last_id = get_last_id(con).await?;
    let mut results = Vec::new();

    loop {
        let reply: redis::Value = redis::cmd("XREAD")
            .arg("BLOCK").arg(2000_u64)
            .arg("COUNT").arg(10_u64)
            .arg("STREAMS").arg(STREAM_KEY).arg(&last_id)
            .query_async(con)
            .await?;

        let entries = parse_xread_reply(reply);

        for (id, json_str) in entries {
            last_id.clone_from(&id);
            let msg: ScanResultMessage = match serde_json::from_str(&json_str) {
                Ok(m) => m,
                Err(e) => {
                    eprintln!("  [warn] failed to parse result JSON: {e}");
                    continue;
                }
            };

            let matches = msg.batch_id.as_deref() == Some(batch_id)
                || monitor_filter.map_or(false, |mid| msg.monitor_id == mid);

            if matches {
                results.push(msg);
                if results.len() >= expected_count {
                    return Ok(results);
                }
            }
        }
    }
}

/// Parse the nested XREAD reply into (id, json_string) pairs.
fn parse_xread_reply(reply: redis::Value) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let streams = match reply {
        redis::Value::Array(s) => s,
        _ => return out,
    };
    for stream in streams {
        let entries = match stream {
            redis::Value::Array(mut parts) if parts.len() == 2 => {
                match parts.pop() {
                    Some(redis::Value::Array(entries)) => entries,
                    _ => continue,
                }
            }
            _ => continue,
        };
        for entry in entries {
            let mut parts = match entry {
                redis::Value::Array(p) if p.len() == 2 => p,
                _ => continue,
            };
            let fields = parts.pop();
            let id = parts.pop();
            let id_str = match id {
                Some(redis::Value::BulkString(b)) => String::from_utf8_lossy(&b).to_string(),
                _ => continue,
            };
            if let Some(redis::Value::Array(kvs)) = fields {
                for chunk in kvs.chunks(2) {
                    if chunk.len() == 2 {
                        if let redis::Value::BulkString(val) = &chunk[1] {
                            out.push((id_str.clone(), String::from_utf8_lossy(val).to_string()));
                        }
                    }
                }
            }
        }
    }
    out
}
