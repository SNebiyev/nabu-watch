use owo_colors::OwoColorize;

use crate::redis::result::ScanResultMessage;

/// Format a scan result for terminal display.
pub fn format_result(msg: &ScanResultMessage, no_color: bool) -> String {
    let mut out = String::new();

    let status_display = if no_color {
        msg.status.clone()
    } else {
        match msg.status.as_str() {
            "SUCCESS" => format!("{}", "SUCCESS".green().bold()),
            "FAILED" => format!("{}", "FAILED".red().bold()),
            s if s.starts_with("SKIPPED") => format!("{}", s.yellow()),
            _ => msg.status.clone(),
        }
    };

    out.push_str(&format!("\n  [{status_display}] monitor={}", msg.monitor_id));

    // Show key payload fields
    if let Some(via) = msg.payload.get("scanned_via").and_then(|v| v.as_str()) {
        out.push_str(&format!("  via={via}"));
    }
    if let Some(err) = msg.payload.get("error").and_then(|v| v.as_str()) {
        let err_display = if no_color {
            err.to_string()
        } else {
            format!("{}", err.red())
        };
        out.push_str(&format!("\n    error: {err_display}"));
    }
    if let Some(code) = msg.payload.get("error_code").and_then(|v| v.as_str()) {
        out.push_str(&format!("  error_code={code}"));
    }

    out
}

/// Print a batch summary.
pub fn format_summary(results: &[ScanResultMessage], elapsed_secs: f64, no_color: bool) -> String {
    let total = results.len();
    let success = results.iter().filter(|r| r.status == "SUCCESS").count();
    let failed = results.iter().filter(|r| r.status == "FAILED").count();
    let skipped = total - success - failed;

    let header = if no_color {
        "--- SCAN COMPLETE ---".to_string()
    } else {
        format!("{}", "--- SCAN COMPLETE ---".bold())
    };

    format!(
        "\n{header}\n  total={total}  success={success}  failed={failed}  skipped={skipped}  elapsed={elapsed_secs:.1}s"
    )
}
