use owo_colors::OwoColorize;

use crate::redis::logs::WorkerLog;

/// Format a worker log line for terminal display.
pub fn format_log(log: &WorkerLog, no_color: bool) -> String {
    let ts = &log.time[11..23]; // HH:MM:SS.mmm
    let level = &log.level;
    let msg = &log.msg;

    if no_color {
        let mut line = format!("{ts}  {level:5}  {msg}");
        append_attrs(&mut line, log);
        return line;
    }

    let level_colored = match level.as_str() {
        "INFO" => format!("{}", "INFO ".green()),
        "WARN" => format!("{}", "WARN ".yellow()),
        "ERROR" => format!("{}", "ERROR".red().bold()),
        "DEBUG" => format!("{}", "DEBUG".dimmed()),
        _ => level.to_string(),
    };

    let mut line = format!("{}  {}  {}", ts.dimmed(), level_colored, msg.bold());
    append_attrs_colored(&mut line, log);
    line
}

fn append_attrs(line: &mut String, log: &WorkerLog) {
    if let Some(ref v) = log.monitor_id { line.push_str(&format!("  monitor_id={v}")); }
    if let Some(ref v) = log.domain { line.push_str(&format!("  domain={v}")); }
    if let Some(ref v) = log.url { line.push_str(&format!("  url={v}")); }
    if let Some(ref v) = log.error { line.push_str(&format!("  error={v}")); }
    for (k, v) in &log.extra {
        if k == "time" || k == "level" || k == "msg" { continue; }
        line.push_str(&format!("  {k}={v}"));
    }
}

fn append_attrs_colored(line: &mut String, log: &WorkerLog) {
    if let Some(ref v) = log.monitor_id {
        line.push_str(&format!("  {}", format!("monitor_id={v}").cyan()));
    }
    if let Some(ref v) = log.domain {
        line.push_str(&format!("  {}", format!("domain={v}").dimmed()));
    }
    if let Some(ref v) = log.url {
        line.push_str(&format!("  {}", format!("url={v}").dimmed()));
    }
    if let Some(ref v) = log.error {
        line.push_str(&format!("  {}", format!("error={v}").red()));
    }
    for (k, v) in &log.extra {
        if k == "time" || k == "level" || k == "msg" { continue; }
        line.push_str(&format!("  {}", format!("{k}={v}").dimmed()));
    }
}

/// Check if a log entry matches a batch/monitor filter.
pub fn matches_filter(log: &WorkerLog, batch_id: &str, monitor_ids: &[String]) -> bool {
    if log.batch_id.as_deref() == Some(batch_id) {
        return true;
    }
    if let Some(ref mid) = log.monitor_id {
        return monitor_ids.iter().any(|id| id == mid);
    }
    false
}
