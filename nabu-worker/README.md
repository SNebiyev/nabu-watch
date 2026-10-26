# nabu-worker

Go 1.26 scanner worker for nabu-watch. It consumes scan tasks from a Redis Stream and publishes results back.
See `.env.example` for the full list of variables it reads.

## FlareSolverr (optional)

FlareSolverr is an optional last-resort fetcher for sites that serve a JavaScript challenge to the built-in headless
Chrome. It runs a real Chrome instance via `undetected-chromedriver`. The worker still respects robots.txt and the
rules in `docs/RESPONSIBLE_USE.md`; only enable this for sites you are allowed to scan.

### Option A: Standalone

Standalone mode uses the host's native Chrome — no Docker ARM emulation overhead, better stability.

**Requirements:**

- Python 3.12 (not 3.13+ — `cgi` module removed)
- Google Chrome or Chromium installed
- ~500 MB RAM per FlareSolverr instance

```bash
# 1. Clone FlareSolverr
git clone --depth 1 https://github.com/FlareSolverr/FlareSolverr.git /opt/flaresolverr

# 2. Create venv with Python 3.12
python3.12 -m venv /opt/flaresolverr/venv
/opt/flaresolverr/venv/bin/pip install -r /opt/flaresolverr/requirements.txt undetected-chromedriver

# 3. Run (on Linux use HEADLESS=true with Xvfb installed: apt install xvfb)
HEADLESS=true \
CHROME_PATH=/usr/bin/google-chrome \
LOG_LEVEL=info \
PYTHONPATH=src \
/opt/flaresolverr/venv/bin/python -m flaresolverr
```

**systemd unit:**

```ini
[Unit]
Description=FlareSolverr WAF bypass proxy
After=network.target

[Service]
Type=simple
User=nabu
Environment=HEADLESS=true
Environment=CHROME_PATH=/usr/bin/google-chrome-stable
Environment=LOG_LEVEL=info
Environment=PYTHONPATH=/opt/flaresolverr/src
ExecStart=/opt/flaresolverr/venv/bin/python -m flaresolverr
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

### Option B: Docker

Simpler setup, but Chrome inside the container may be less stable on ARM hosts.

```bash
docker run -d \
  --name nabu-flaresolverr \
  -p 8191:8191 \
  -e LOG_LEVEL=info \
  --restart unless-stopped \
  ghcr.io/flaresolverr/flaresolverr:latest
```

### Worker Configuration

Set the environment variable to enable FlareSolverr in the Go worker:

```bash
NABU_FLARESOLVERR_URL=http://localhost:8191
```

Add to `.env` or pass directly. When not set, this step is silently skipped.

### Chrome Version Pinning (Required for FlareSolverr)

`undetected-chromedriver` v3.5.5 does not support Chrome 146+. FlareSolverr requires Chrome to be pinned to a compatible
version.

**Linux:**

```bash
# 1. Remove auto-updating Chrome
sudo apt remove google-chrome-stable

# 2. Install a specific version (Chrome 130)
wget https://dl.google.com/linux/chrome/deb/pool/main/g/google-chrome-stable/google-chrome-stable_130.0.6723.91-1_amd64.deb
sudo dpkg -i google-chrome-stable_130.0.6723.91-1_amd64.deb

# 3. Hold the package to prevent auto-update
sudo apt-mark hold google-chrome-stable

# 4. Verify
google-chrome-stable --version
# Google Chrome 130.0.6723.91
```

**Docker (simplest — no pin needed):**

The Docker image ships its own Chrome, so pinning is handled by the image tag:

```bash
# Use a specific FlareSolverr version instead of :latest
ghcr.io/flaresolverr/flaresolverr:v3.3.21
```

### Known Limitations

- **Cloudflare h2-force**: Some Cloudflare sites ignore ALPN `http/1.1` and send HTTP/2 frames anyway. The scanner
  detects this and falls back to `tls-client` (Chrome_144 h2 profile) automatically.
- **Heavily protected sites**: Sites behind managed JS challenges (Cloudflare/Akamai) may still block the scanner,
  especially from datacenter IPs. Proxies are optional and user-configured per region via `NABU_PROXY_<REGION>`.

## Environment Variables

| Variable                     | Default          | Description                                                   |
|------------------------------|------------------|---------------------------------------------------------------|
| `REDIS_ADDR`                 | `localhost:6379` | Redis `host:port`                                             |
| `REDIS_PASSWORD`             | *(empty)*        | Redis password                                                |
| `STORAGE_ENDPOINT`           | `localhost:9000` | S3/MinIO endpoint for screenshots                             |
| `STORAGE_ACCESS_KEY`         | `minioadmin`     | Storage access key                                            |
| `STORAGE_SECRET_KEY`         | `minioadmin`     | Storage secret key                                            |
| `STORAGE_BUCKET`             | `nabu-files`     | Storage bucket                                                |
| `STORAGE_USE_SSL`            | `false`          | Use TLS for storage (implied by an `https://` endpoint)       |
| `WORKER_MAX_CONCURRENT`      | `10`             | Max concurrent scans (clamped to the Chrome limit below)      |
| `NABU_MAX_CONCURRENT_CHROME` | `3`              | Max concurrent Chrome tabs                                    |
| `NABU_CHROME_PATH`           | *(auto-detect)*  | Chrome/Chromium binary                                        |
| `NABU_FLARESOLVERR_URL`      | *(empty)*        | FlareSolverr endpoint, e.g. `http://localhost:8191`           |
| `NABU_PROXY_<REGION>`        | *(empty)*        | Per-region HTTP proxy, e.g. `NABU_PROXY_DE=http://proxy:8080` |
| `AI_PROVIDER`                | `auto`           | AI backend: `claude`, `gemini`, or `auto`                     |
| `ANTHROPIC_API_KEY`          | *(empty)*        | Claude API key                                                |
| `GEMINI_API_KEY`             | *(empty)*        | Gemini API key                                                |
