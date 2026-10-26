#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# nabu-watch — Server Bootstrap Script
# Run once on a fresh Ubuntu 24.04 LTS server (as root).
# Usage: ssh root@SERVER_IP 'bash -s' < deploy/server-setup.sh
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

NABU_USER="nabu"
REPO_URL="https://github.com/SNebiyev/nabu-watch.git"
INSTALL_DIR="/opt/nabu-watch"
WORKER_DIR="/opt/nabu-worker"
BACKUP_DIR="/opt/backups"

echo "==> [1/10] System update..."
apt-get update -qq && apt-get upgrade -y -qq

echo "==> [2/10] Create nabu user..."
if ! id "$NABU_USER" &>/dev/null; then
    adduser --system --group --shell /bin/bash --home /home/$NABU_USER $NABU_USER
fi

echo "==> [3/10] Install Docker..."
if ! command -v docker &>/dev/null; then
    apt-get install -y -qq ca-certificates curl gnupg
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
    apt-get update -qq
    apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    usermod -aG docker $NABU_USER
    systemctl enable docker
fi

echo "==> [4/10] Install Go (version required by nabu-worker/go.mod: 1.26+)..."
if ! command -v go &>/dev/null; then
    GO_VERSION="$(curl -fsSL 'https://go.dev/VERSION?m=text' | head -n1 | sed 's/^go//')"
    GO_VERSION="${GO_VERSION:-1.26.0}"
    curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" | tar -C /usr/local -xz
    echo 'export PATH=$PATH:/usr/local/go/bin' > /etc/profile.d/golang.sh
    export PATH=$PATH:/usr/local/go/bin
fi

echo "==> [5/10] Install Google Chrome (.deb) and its dependencies..."
# Deliberately NOT the snap chromium: the snap runs outside the worker's systemd
# cgroup, so Chrome processes cannot be reaped on restart (see nabu-worker/setup_worker.sh).
# setup_worker.sh holds the full, maintained install logic; reuse it.
apt-get install -y -qq git
echo "==> [6/10] Clone repository..."
mkdir -p "$INSTALL_DIR" "$WORKER_DIR" "$BACKUP_DIR"
if [ ! -d "$INSTALL_DIR/.git" ]; then
    git clone "$REPO_URL" "$INSTALL_DIR"
fi
chown -R $NABU_USER:$NABU_USER "$INSTALL_DIR" "$WORKER_DIR"
# Backups hold secrets and are written by root (nabu-backup.service).
chmod 700 "$BACKUP_DIR"
bash "$INSTALL_DIR/nabu-worker/setup_worker.sh"

echo "==> [7/10] Set up Go worker..."
# Symlink worker source
if [ ! -L "$WORKER_DIR/nabu-worker" ]; then
    ln -sf "$INSTALL_DIR/nabu-worker" "$WORKER_DIR/nabu-worker"
fi

# Build worker binary
export PATH=$PATH:/usr/local/go/bin
cd "$WORKER_DIR/nabu-worker"
sudo -u $NABU_USER /usr/local/go/bin/go build -o "$WORKER_DIR/nabu-worker-bin" ./...

# Install systemd service
cp "$INSTALL_DIR/deploy/nabu-worker.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable nabu-worker

echo "==> [8/10] Configure UFW firewall..."
if command -v ufw &>/dev/null; then
    ufw --force reset
    ufw default deny incoming
    ufw default allow outgoing
    ufw allow 22/tcp    # SSH
    ufw allow 80/tcp    # HTTP
    ufw allow 443/tcp   # HTTPS
    ufw --force enable
fi

echo "==> [9/10] Enable daily PostgreSQL backup (systemd timer)..."
cp "$INSTALL_DIR/deploy/backup/nabu-backup.service" "$INSTALL_DIR/deploy/backup/nabu-backup.timer" /etc/systemd/system/
chmod +x "$INSTALL_DIR/deploy/backup/nabu-backup.sh"
systemctl daemon-reload
systemctl enable nabu-backup.timer

echo "==> [10/10] Configure SSH hardening..."
# Disable password auth (SSH key only)
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
systemctl reload ssh 2>/dev/null || systemctl reload sshd

echo ""
echo "══════════════════════════════════════════════════════"
echo " Server setup complete!"
echo ""
echo " Next steps:"
echo "  1. Create $INSTALL_DIR/.env from .env.example and fill in the secrets"
echo "  2. Edit $WORKER_DIR/.env (REDIS_PASSWORD, STORAGE_*; see nabu-worker/.env.example)"
echo "  3. Point DNS: your-domain.example → $(curl -s ifconfig.me)"
echo "  4. Start services:"
echo "     cd $INSTALL_DIR"
echo "     docker compose -f docker-compose.prod.yml up -d"
echo "     systemctl start nabu-worker nabu-backup.timer"
echo "══════════════════════════════════════════════════════"
