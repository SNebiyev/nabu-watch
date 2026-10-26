pub mod task;
pub mod result;
pub mod logs;

use anyhow::{Context, Result};

pub fn connect(redis_url: &str) -> Result<redis::Client> {
    redis::Client::open(redis_url).context("failed to connect to Redis")
}
