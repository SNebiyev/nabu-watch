#!/usr/bin/env bash
# setup_worker.sh — Ubuntu Server bootstrap for nabu-worker
#
# Installs Google Chrome (non-snap) and the shared libraries required for
# headless operation, and creates a starter worker env file if none exists.
#
# Tested on: Ubuntu 20.04 LTS, 22.04 LTS, 24.04 LTS
#
# Usage (as root or with sudo):
#   chmod +x setup_worker.sh
#   sudo ./setup_worker.sh
#
# Optional environment variables:
#   NABU_ENV_FILE     — Worker env file to create (default: /opt/nabu-worker/.env)
#   REDIS_ADDR        — Redis host:port written to the env file (default: localhost:6379)
#   NABU_CHROME_PATH  — Override Chrome binary path (auto-detected if unset)

set -euo pipefail

# ── Helpers ───────────────────────────────────────────────────────────────────

log()  { echo "[nabu-setup]  $*"; }
warn() { echo "[nabu-setup] ⚠  $*" >&2; }
die()  { echo "[nabu-setup] ✗  $*" >&2; exit 1; }

require_root() {
  [[ $EUID -eq 0 ]] || die "This script must be run as root (use sudo)."
}

# ── Pre-flight ────────────────────────────────────────────────────────────────

require_root

log "Detected OS: $(lsb_release -ds 2>/dev/null || uname -sr)"
log "Architecture: $(uname -m)"

# ── 1. System update ──────────────────────────────────────────────────────────

log "Updating apt package index…"
apt-get update -qq

# ── 2. Chrome browser (Google Chrome .deb — deliberately NOT snap) ───────────
#
# We install google-chrome-stable from Google's apt repo, NOT the Ubuntu
# `chromium`/`chromium-browser` packages. On Ubuntu 22.04+ those are dummy
# packages that redirect to the Snap, and the Snap launches the browser inside
# its own systemd scope under user@0.service — detached from nabu-worker's
# cgroup. That breaks process reaping: KillMode can't terminate it on restart,
# and any ungraceful worker death (OOM/SIGKILL) orphans Chrome to PID 1 where
# it lingers forever leaking ~700MB. A plain .deb runs as a direct child in the
# worker's cgroup, so systemd (KillMode=mixed) and chromedp's allocCancel both
# reap it cleanly. See deploy/nabu-worker.service.

log "Installing Google Chrome (.deb, non-snap)…"

GOOGLE_KEYRING="/etc/apt/keyrings/google-chrome.gpg"
GOOGLE_LIST="/etc/apt/sources.list.d/google-chrome.list"

apt-get install -y --no-install-recommends ca-certificates curl gnupg
install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://dl.google.com/linux/linux_signing_key.pub \
  | gpg --dearmor --yes -o "${GOOGLE_KEYRING}"
echo "deb [arch=amd64 signed-by=${GOOGLE_KEYRING}] https://dl.google.com/linux/chrome/deb/ stable main" \
  > "${GOOGLE_LIST}"
apt-get update -qq
apt-get install -y google-chrome-stable

CHROME_BIN="$(command -v google-chrome-stable 2>/dev/null || echo /usr/bin/google-chrome-stable)"

# Defensive: if a leftover snap chromium is present from a previous setup, drop
# it so auto-discovery can never pick the cgroup-detaching binary again.
if command -v snap &>/dev/null && snap list chromium &>/dev/null; then
  warn "Removing previously-installed snap chromium (cgroup-detaching — see above)…"
  snap remove chromium || warn "snap remove chromium failed — remove manually."
fi

if [[ -x "${CHROME_BIN}" ]]; then
  log "Chrome installed: ${CHROME_BIN}"
  log "Chrome version: $(${CHROME_BIN} --version 2>/dev/null || echo 'unknown')"
else
  warn "google-chrome-stable not found after install."
  warn "Install it manually and set NABU_CHROME_PATH in the worker env file."
fi

# ── 3. Shared libraries required for headless Chrome ─────────────────────────
#
# Chrome's renderer process dynamically loads a range of system libraries.
# On a minimal Ubuntu Server install most of these are absent, causing
# the "error while loading shared libraries" crash at startup.

log "Installing headless Chrome shared library dependencies…"
apt-get install -y --no-install-recommends \
  libnss3 \
  libatk1.0-0 \
  libatk-bridge2.0-0 \
  libcups2 \
  libdrm2 \
  libxkbcommon0 \
  libxcomposite1 \
  libxdamage1 \
  libxfixes3 \
  libxrandr2 \
  libgbm1 \
  libasound2 \
  libpango-1.0-0 \
  libpangocairo-1.0-0 \
  libcairo2 \
  libdbus-1-3 \
  fonts-liberation \
  libappindicator3-1 \
  xdg-utils

log "Shared libraries installed."

# ── 4. Go runtime (optional — only if not already present) ───────────────────

if ! command -v go &>/dev/null; then
  warn "Go not found. nabu-worker needs the Go version in nabu-worker/go.mod (1.26+)."
  warn "The distro golang-go package is usually too old: install from https://go.dev/dl/"
else
  log "Go already installed: $(go version)"
fi

# ── 5. nabu-worker environment file ──────────────────────────────────────────
#
# deploy/nabu-worker.service loads /opt/nabu-worker/.env via EnvironmentFile=.
# An existing file is never overwritten. See nabu-worker/.env.example for the
# full list of variables the worker reads.

NABU_ENV_FILE="${NABU_ENV_FILE:-/opt/nabu-worker/.env}"

if [[ -e "${NABU_ENV_FILE}" ]]; then
  log "Env file already exists, leaving it untouched: ${NABU_ENV_FILE}"
else
  log "Writing environment file: ${NABU_ENV_FILE}"
  mkdir -p "$(dirname "${NABU_ENV_FILE}")"
  cat > "${NABU_ENV_FILE}" <<EOF
# nabu-worker environment - created by setup_worker.sh
# REDIS_PASSWORD must match REDIS_PASSWORD in the stack's .env.
REDIS_ADDR=${REDIS_ADDR:-localhost:6379}
# REDIS_PASSWORD=

# Explicit Chrome binary path (leave blank for auto-discovery)
NABU_CHROME_PATH=${NABU_CHROME_PATH:-${CHROME_BIN:-}}
EOF
  chmod 640 "${NABU_ENV_FILE}"
fi

# ── 6. Summary ────────────────────────────────────────────────────────────────

echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  nabu-worker setup complete"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "  Chrome binary : ${CHROME_BIN:-NOT FOUND — set NABU_CHROME_PATH manually}"
echo "  Env file      : ${NABU_ENV_FILE}"
echo ""
echo "  Next steps:"
echo "    1. Build the worker:  go build -o nabu-worker-bin ."
echo "    2. Set REDIS_PASSWORD (and STORAGE_*) in ${NABU_ENV_FILE}"
echo "    3. Install as a service (optional):"
echo "       cp deploy/nabu-worker.service /etc/systemd/system/"
echo "       systemctl daemon-reload && systemctl enable --now nabu-worker"
echo ""
echo "  If Chrome crashes at startup, run:"
echo "    ${CHROME_BIN:-google-chrome-stable} --headless --no-sandbox --disable-dev-shm-usage --dump-dom https://example.com"
echo "  and check for missing library errors."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
