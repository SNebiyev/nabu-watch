# nabu-test

Rust CLI tool for testing the nabu-watch Go scanner worker. Publishes scan tasks directly to Redis, streams live worker
logs via pub/sub, and displays results as they arrive.

## Architecture

```
nabu-test (Rust CLI)
  ├─ PostgreSQL (read-only) ── website/monitor/keyword lookup
  ├─ Redis XADD ── publish scan tasks to nabu:scan-tasks
  ├─ Redis XREAD ── consume results from nabu:scan-results
  └─ Redis SUBSCRIBE ── live worker logs from nabu:worker-logs
```

The tool bypasses the Java API entirely — it reads monitor/keyword data from PostgreSQL and publishes tasks directly to
the Redis stream that the Go worker consumes. This means:

- No authentication needed
- No rate limiting or scan count enforcement
- No `lastRunAt` stamping (monitors won't be marked as "just scanned")
- The Java `ResultConsumer` still processes results normally (nabu-test uses `XREAD`, not `XREADGROUP`, so it doesn't
  steal messages)

## Prerequisites

- **Go worker running** with the `PubSubLogHandler` wired in (`nabu-worker/internal/worker/logpub.go` publishes logs to
  Redis pub/sub channel `nabu:worker-logs`)
- **PostgreSQL** accessible (default: `localhost:5432/nabu_watch`)
- **Redis** accessible (default: `localhost:6379`)
- **Rust 1.85+** for building

## Build

```bash
cd nabu-test
cargo build --release
# Binary: target/release/nabu-test
```

## Commands

### `list <url>` — Show monitors for a website

```bash
nabu-test list https://example.com
nabu-test list https://example.com --all    # include inactive monitors
```

### `scan <url>` — Full website rescan

Triggers all active monitors for a website with scout-first ordering (homepage-parsing compliance → banner/tracker →
SSL).

```bash
# Full rescan with live logs
nabu-test scan https://example.com

# Only specific sub-type(s)
nabu-test scan https://example.com --only PRIVACY_POLICY
nabu-test scan https://example.com --only PRIVACY_POLICY,COOKIE_POLICY

# Skip SSL monitor
nabu-test scan https://example.com --no-ssl

# Preview task fields without publishing
nabu-test scan https://example.com --dry-run

# Custom timeout (default: 120s)
nabu-test scan https://example.com --timeout 180
```

### `scan-monitor <id>` — Scan a single monitor

```bash
# Get monitor ID from `list` command first
nabu-test scan-monitor 00000000-0000-4000-8000-000000000001

# Preview task fields
nabu-test scan-monitor 00000000-0000-4000-8000-000000000001 --dry-run
```

### `watch` — Passive log watcher

Subscribes to `nabu:worker-logs` and displays all Go worker logs in real-time. Useful for monitoring scheduled scans.

```bash
nabu-test watch
nabu-test watch --filter "example"    # filter by substring
```

## Global options

```
--db-url <url>       Override PostgreSQL connection (e.g. postgres://user:pass@host/db)
--redis-url <url>    Override Redis connection (e.g. redis://host:6379)
--no-color           Disable terminal colors
```

## Environment variables

Uses the same env vars as the Java API (reads `.env` if present):

| Variable         | Default          | Purpose           |
|------------------|------------------|-------------------|
| `DB_HOST`        | `localhost`      | PostgreSQL host   |
| `DB_PORT`        | `5432`           | PostgreSQL port   |
| `DB_NAME`        | `nabu_watch`     | Database name     |
| `DB_USER`        | `postgres`       | Database user     |
| `DB_PASS`        | `admin`          | Database password |
| `REDIS_ADDR`     | `localhost:6379` | Redis address     |
| `REDIS_PASSWORD` | _(empty)_        | Redis password    |

## Task building

nabu-test replicates the Java `TaskProducer.enqueue()` logic:

- **Keyword resolution**: Queries `compliance_keywords` + `compliance_region_rules` tables with region fallback to
  global default
- **English-region heuristic**: Suppresses fallback keywords for GB/US regions
- **AcceptedLanguages**: Handles NATIVE_ONLY / ENGLISH_ONLY / BOTH_ACCEPTED transformations
- **Scout ordering**: First homepage-parsing compliance monitor is marked `isScout=true`
- **Fetch mode hint**: Injects `fetchModeHint=HEADLESS` when website has recent headless detection
- **Compliance path hints**: Injects `pathAnchorHref` + `pathDiscoveredURL` from `website_compliance_paths`
- **AI hints**: Injects previous scan's AI-discovered URLs, selectors, and button labels

## Output format

Live logs are color-coded:

- **INFO** (green): Normal scan progress
- **WARN** (yellow): Escalation, WAF blocks, fallbacks
- **ERROR** (red): Failures

Results show status, `scanned_via` tag, and the full execution trace with per-step timing.

## Example session

```
$ nabu-test scan-monitor 00000000-0000-4000-8000-000000000001

Scanning COOKIE_BANNER (00000000-...) for https://www.example.com
  ⚡ Published task_id=1700000000000-0
01:52:52  INFO   Processing task  monitor_id=00000000-...
01:52:52  INFO   BannerScanner: starting banner detection
01:53:10  INFO   BannerScanner: CMP detection result  cmp_detected=true
01:53:10  INFO   BannerScanner: AI hints confirmed — skipping AI call
01:53:10  INFO   Scan result published  status="SUCCESS"

  [SUCCESS] monitor=00000000-...  via=AI_HINT_VERIFIED
    trace:
      headless_fetch                  17465ms  url=https://www.example.com
      cmp_detection                       1ms  cmp=Generic selector=[id*=cookie-consent]
      ai_hint_verify                      0ms  hints confirmed — AI call skipped

  Completed in 17.6s
```
