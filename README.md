# nabu-watch

Open-source, self-hosted website compliance monitoring: SSL certificate health, legal pages, cookie banners,
trackers and security posture. A [TechLab](https://techlab.az/) project. Project homepage: https://nabu.watch

There is no hosted service. You run the API, UI, database and scanner worker on your own server.

## Features

- **SSL monitoring**: certificate expiry, chain and protocol checks with alerts before a certificate lapses.
- **Legal page monitoring**: privacy policy, terms of service, impressum, cookie policy and GDPR request pages, checked
  per region (13 regions, localized keyword sets).
- **Cookie banner and tracker checks**: detects consent platforms, whether reject works, and which third-party
  trackers load before consent.
- **Security audit**: TLS grade, security headers, DNS hygiene, exposed files, open ports, outdated JavaScript
  libraries, typosquat watch.
- **Optional AI**: a Claude or Gemini key lets the scanner verify legal pages and banners it cannot classify
  statically, and a Gemini key adds fix suggestions on security findings. Everything else works without AI.
- **Alerts and integrations**: email, outbound webhooks (Slack and generic), live updates over SSE and WebSocket,
  PDF compliance reports, public scan tools, domain-ownership verification and public trust profiles.
- **Teams**: organizations, roles, TOTP two-factor auth, Google and GitHub sign-in, admin panel.

## Architecture

```
Browser ──► Caddy ──► nabu-ui (Next.js)
                 └──► nabu-api (Spring Boot, Java 21) ──► PostgreSQL
                          │  ▲                       └──► MinIO (screenshots, files)
              XADD tasks  │  │ XREADGROUP results
                          ▼  │
                       Redis Streams  ◄──► nabu-worker (Go + headless Chrome, runs on the host)
```

| Component    | Tech                              | Location       |
|--------------|-----------------------------------|----------------|
| `nabu-api`   | Spring Boot 3.5, Java 21          | repo root      |
| `nabu-ui`    | Next.js 16, React 19, Tailwind 4  | `nabu-ui/`     |
| `nabu-worker`| Go 1.26                           | `nabu-worker/` |
| `nabu-test`  | Rust CLI to drive the worker      | `nabu-test/`   |
| `e2e`        | Playwright regression suite       | `e2e/`         |
| `site`       | Static project landing page       | `site/`        |

The API schema is managed only by Liquibase (changesets under `src/main/resources/db/changelog`).

Documentation: [Overview](https://sprintree.com/p/NABU/pages/1),
[Architecture](https://sprintree.com/p/NABU/pages/2),
[Scanner pipeline](https://sprintree.com/p/NABU/pages/3),
[Monitor types](https://sprintree.com/p/NABU/pages/4),
[Development and testing](https://sprintree.com/p/NABU/pages/5).

## Quick start (self-host)

Requirements: a Linux server with Docker (Compose v2), a domain pointing at it, and ports 80/443 open. The worker
runs on the host because it needs a real Chrome binary.

```bash
git clone https://github.com/SNebiyev/nabu-watch.git /opt/nabu-watch
cd /opt/nabu-watch

# 1. Configure
cp .env.example .env
#    Fill in DOMAIN, DB_PASS, REDIS_PASSWORD, JWT_SECRET, STORAGE_ACCESS_KEY, STORAGE_SECRET_KEY,
#    GRAFANA_PASSWORD, the MAIL_* SMTP settings and NABU_BOOTSTRAP_ADMIN_EMAIL.
#    Generate secrets with: openssl rand -base64 32

# 2. Start the stack (Caddy, UI, API, PostgreSQL, Redis, MinIO, FlareSolverr, Loki, Grafana)
docker compose -f docker-compose.prod.yml up -d --build

# 3. Install the scanner worker on the host (Ubuntu/Debian, as root)
sudo ./nabu-worker/setup_worker.sh        # installs Google Chrome (non-snap), writes /opt/nabu-worker/.env
cd nabu-worker && go build -o /opt/nabu-worker/nabu-worker-bin . && cd ..
#    Edit /opt/nabu-worker/.env: REDIS_PASSWORD and STORAGE_* must match your .env.
sudo cp deploy/nabu-worker.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now nabu-worker
```

`deploy/server-setup.sh` automates the host preparation on a fresh Ubuntu 24.04 server (Docker, Go, Chrome, the
`nabu` user, firewall, systemd units, nightly backup timer). Read it before running it.

The prod compose file publishes Redis and MinIO on `127.0.0.1` only, so the host worker can reach them
(`REDIS_ADDR=localhost:6379`, `STORAGE_ENDPOINT=localhost:9000`) without exposing them to the network.

### First admin

Set `NABU_BOOTSTRAP_ADMIN_EMAIL` in `.env`, then register with that email (or sign in with Google/GitHub). The account
becomes `ADMIN` as soon as its email is verified, including at API startup if it already exists. Unverified accounts
are never promoted. Leave the variable empty to disable the bootstrap.

### Backups

`deploy/backup/` contains a nightly `pg_dump` plus config backup (systemd timer). It does not back up the MinIO
bucket; mirror it separately if you need screenshots.

## Configuration

- API and stack: [`.env.example`](.env.example) (every variable is documented there). `JWT_SECRET` has no default:
  the API refuses to start without a value of at least 32 characters.
- Worker: [`nabu-worker/.env.example`](nabu-worker/.env.example) and [`nabu-worker/README.md`](nabu-worker/README.md).
- UI build-time variables: [`nabu-ui/README.md`](nabu-ui/README.md).

## Local development

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

docker compose up -d                  # PostgreSQL, Redis, MinIO, Mailpit (dev defaults)
echo "JWT_SECRET=$(openssl rand -base64 48)" > .env.local   # loaded by `bootRun`
./gradlew bootRun                     # API on :8080
(cd nabu-ui && npm ci && npm run dev) # UI on :3000
(cd nabu-worker && go run .)          # worker; needs Chrome for headless scans
```

Exercise the worker with `cd nabu-test && cargo run --release -- scan https://example.com`.
Build and test commands for every component are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Responsible use

nabu-watch is for auditing sites you own, operate, or are authorized to audit. Read
[docs/RESPONSIBLE_USE.md](docs/RESPONSIBLE_USE.md) before pointing it at anything else.

## Contributing, security, license

- Contributing and DCO sign-off: [CONTRIBUTING.md](CONTRIBUTING.md), [Code of Conduct](CODE_OF_CONDUCT.md)
- Reporting vulnerabilities: [SECURITY.md](SECURITY.md)
- License: Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Copyright 2026 Samir Nabiyev.

Compose images such as Redis, MinIO and Grafana are separate network services under their own licenses. Product
names and logos mentioned belong to their respective owners.
