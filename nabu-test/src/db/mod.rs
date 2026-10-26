pub mod models;
pub mod queries;

use anyhow::{Context, Result};
use sqlx::postgres::PgPoolOptions;
use sqlx::PgPool;

pub async fn connect(db_url: &str) -> Result<PgPool> {
    PgPoolOptions::new()
        .max_connections(3)
        .connect(db_url)
        .await
        .context("failed to connect to PostgreSQL")
}
