mod config;
mod db;
mod redis;
mod commands;
mod display;

use anyhow::Result;
use clap::{Parser, Subcommand};
use uuid::Uuid;

#[derive(Parser)]
#[command(name = "nabu-test", about = "CLI tool for testing nabu-watch Go scanner worker")]
struct Cli {
    #[command(subcommand)]
    command: Commands,

    /// PostgreSQL connection URL (overrides env vars)
    #[arg(long, global = true)]
    db_url: Option<String>,

    /// Redis connection URL (overrides env vars)
    #[arg(long, global = true)]
    redis_url: Option<String>,

    /// Disable terminal colors
    #[arg(long, global = true)]
    no_color: bool,
}

#[derive(Subcommand)]
enum Commands {
    /// Full website rescan (all active monitors)
    Scan {
        /// Website URL to scan
        url: String,

        /// Only scan specific sub-type(s), comma-separated
        #[arg(long)]
        only: Option<String>,

        /// Skip SSL monitor
        #[arg(long)]
        no_ssl: bool,

        /// Don't mark any task as scout
        #[arg(long)]
        no_scout: bool,

        /// Print task fields without publishing to Redis
        #[arg(long)]
        dry_run: bool,

        /// Timeout in seconds waiting for results (default: 120)
        #[arg(long, default_value = "120")]
        timeout: u64,
    },

    /// Scan a single monitor by ID
    ScanMonitor {
        /// Monitor UUID
        id: Uuid,

        /// Print task fields without publishing
        #[arg(long)]
        dry_run: bool,

        /// Timeout in seconds (default: 120)
        #[arg(long, default_value = "120")]
        timeout: u64,
    },

    /// Show monitors for a website
    List {
        /// Website URL
        url: String,

        /// Include inactive monitors
        #[arg(long)]
        all: bool,
    },

    /// Passive log watcher (all worker logs)
    Watch {
        /// Filter log lines by substring
        #[arg(long)]
        filter: Option<String>,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    let cfg = config::Config::load()?.with_overrides(cli.db_url, cli.redis_url)?;

    let pool = db::connect(&cfg.db_url).await?;
    let redis_client = redis::connect(&cfg.redis_url)?;

    match cli.command {
        Commands::Scan { url, only, no_ssl, no_scout, dry_run, timeout } => {
            commands::scan::run(&pool, &redis_client, &url, only, no_ssl, no_scout, dry_run, timeout, cli.no_color).await
        }
        Commands::ScanMonitor { id, dry_run, timeout } => {
            commands::scan_monitor::run(&pool, &redis_client, id, dry_run, timeout, cli.no_color).await
        }
        Commands::List { url, all } => {
            commands::list::run(&pool, &url, all).await
        }
        Commands::Watch { filter } => {
            commands::watch::run(&redis_client, filter, cli.no_color).await
        }
    }
}
