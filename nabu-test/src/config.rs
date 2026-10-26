use anyhow::Result;

#[derive(Debug, Clone)]
pub struct Config {
    pub db_url: String,
    pub redis_url: String,
}

impl Config {
    pub fn load() -> Result<Self> {
        _ = dotenvy::dotenv();

        let db_host = env_or("DB_HOST", "localhost");
        let db_port = env_or("DB_PORT", "5432");
        let db_name = env_or("DB_NAME", "nabu_watch");
        let db_user = env_or("DB_USER", "postgres");
        let db_pass = env_or("DB_PASS", "admin");

        let db_url = format!("postgres://{}:{}@{}:{}/{}", db_user, db_pass, db_host, db_port, db_name);

        let redis_addr = env_or("REDIS_ADDR", "localhost:6379");
        let redis_pass = env_or("REDIS_PASSWORD", "");
        let redis_url = if redis_pass.is_empty() {
            format!("redis://{}", redis_addr)
        } else {
            format!("redis://:{}@{}", redis_pass, redis_addr)
        };

        Ok(Self { db_url, redis_url })
    }

    /// Override from CLI flags (take precedence over env vars).
    pub fn with_overrides(mut self, db_url: Option<String>, redis_url: Option<String>) -> Result<Self> {
        if let Some(url) = db_url {
            self.db_url = url;
        }
        if let Some(url) = redis_url {
            self.redis_url = url;
        }
        Ok(self)
    }
}

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}
