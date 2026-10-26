# Contributing

Thanks for helping improve nabu-watch. This project is maintained on a best-effort basis.

## Components and how to build/test them

Set `JAVA_HOME` to a JDK 21 installation (for example `/usr/lib/jvm/java-21-openjdk-amd64`).

| Component | Path | Build / test |
|-----------|------|--------------|
| API (Spring Boot 3.5, Java 21) | repo root | `./gradlew compileJava`, `./gradlew test`, `./gradlew bootRun` |
| Worker (Go) | `nabu-worker/` | `cd nabu-worker && go build ./... && go test ./...` |
| UI (Next.js, React 19) | `nabu-ui/` | `cd nabu-ui && npm ci && npm run build` |
| Scan test CLI (Rust) | `nabu-test/` | `cd nabu-test && cargo run --release -- scan https://example.com` |
| E2E (Playwright) | `e2e/` | see `e2e/README.md` (needs the local stack running) |

Use `nabu-test` to exercise the worker rather than pushing tasks into Redis by hand.
Worker setup details (Chrome, FlareSolverr, env vars) are in `nabu-worker/README.md`.

## Workflow

1. Fork the repository and create a branch from `main`.
2. Make your change with tests, and make sure the relevant builds and tests pass.
3. Open a pull request against `main`.

## Conventions

- Clean Architecture layers: `infrastructure -> application -> domain`. Dependencies point inward only.
- Schema changes go through Liquibase only (`ddl-auto=validate`). Add a new migration in
  `src/main/resources/db/changelog/tables/` using the next free number; never edit applied migrations.
- Fixes and features need unit tests.
- Java tests use `@ExtendWith(MockitoExtension.class)` with `@Mock` / `@InjectMocks`.
  Mock `saveAndFlush` (not `save`) for entities with `@CreationTimestamp` fields, and use
  `ReflectionTestUtils.setField(entity, "id", value)` to set `BaseEntity.id`.

## Contribution terms (DCO)

By contributing you certify the [Developer Certificate of Origin](https://developercertificate.org/).
Sign off every commit with `git commit -s`, which adds a `Signed-off-by:` line. Contributions are
licensed under the Apache License 2.0 (see `LICENSE`).

Please also follow the [Code of Conduct](CODE_OF_CONDUCT.md). For security issues see [SECURITY.md](SECURITY.md).
