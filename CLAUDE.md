# CLAUDE.md

Guidance for AI coding assistants and contributors working in this repository. Human-oriented docs live in
`README.md` and `CONTRIBUTING.md`.

## Projects

- **nabu-api**: Spring Boot 3.5 (Java 21) REST API (repo root)
- **nabu-worker**: Go 1.26 scanner worker (`nabu-worker/`)
- **nabu-ui**: Next.js 16 + React 19 + Tailwind 4 frontend (`nabu-ui/`)
- **nabu-test**: Rust CLI for testing the Go worker (`nabu-test/`)
- **e2e**: Playwright regression suite (`e2e/`)
- **site**: static project landing page (`site/`)

## Build & Run

```bash
# Java API (JDK 21)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew compileJava compileTestJava
./gradlew test            # integration tests start PostgreSQL and Redis through Testcontainers (needs Docker)
./gradlew bootRun         # needs JWT_SECRET (>= 32 chars), e.g. in .env.local; see .env.example

# Go worker
cd nabu-worker && go build ./... && go vet ./... && go test ./...

# Frontend
cd nabu-ui && npx tsc --noEmit && npm run build

# Test scans (always use nabu-test, not manual Redis)
cd nabu-test && cargo run --release -- scan https://example.com
```

Local services (PostgreSQL, Redis, MinIO, Mailpit) come from `docker compose up -d`.

## Architecture

Clean Architecture: `infrastructure -> application -> domain`. Schema via Liquibase only (`ddl-auto=validate`);
changesets live in `src/main/resources/db/changelog/tables/`. Add a new changeset file for schema changes and do not
edit changesets that may already have been applied.

```
MonitoringScheduler -> TaskProducer -> Redis Stream (nabu:scan-tasks)
  -> Go worker (SSL/Compliance scanners) -> Redis Stream (nabu:scan-results)
    -> ResultConsumer -> ScanResultProcessor -> ScanLog -> SSE to clients
```

**Key entities:** User, Organization, Website, Monitor, ScanLog.
**Monitor types:** SSL, PRIVACY_POLICY, TERMS_OF_SERVICE, IMPRESSUM, COOKIE_POLICY, GDPR_REQUEST, COOKIE_BANNER,
TRACKER_DETECTION.

## Configuration

- API: `src/main/resources/application.yml`; stack variables are documented in `.env.example`.
- `JWT_SECRET` has no default and the API fails fast without it. Test config supplies its own value in
  `src/test/resources/application.properties`.
- First admin: `NABU_BOOTSTRAP_ADMIN_EMAIL` promotes the matching account once it is ACTIVE (verified email).
- Worker env vars are exactly those read by `nabu-worker/internal/config` and the scanner package; see
  `nabu-worker/.env.example`. The code is the source of truth.

## Scanner Pipeline (Go worker)

Homepage mode escalation: fast-path -> AI hint -> static fetch -> headless -> deep crawl -> text fallback -> desktop
reset -> AI discovery -> AI semantic verify.
Keep robots.txt compliance enabled and follow `docs/RESPONSIBLE_USE.md` when changing fetch behaviour.

## Testing Conventions

- Write unit tests with every fix or feature.
- `@ExtendWith(MockitoExtension.class)` + `@Mock` / `@InjectMocks`.
- Mock `saveAndFlush` (not `save`) for `@CreationTimestamp` fields.
- `ReflectionTestUtils.setField(entity, "id", value)` for `BaseEntity.id`.
- Commits need a DCO sign-off (`git commit -s`); see `CONTRIBUTING.md`.
