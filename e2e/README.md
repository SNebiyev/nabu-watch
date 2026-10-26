# nabu-watch · e2e regression suite

Full end-to-end regression tests for nabu-watch (API + UI + worker).

Stack: **Playwright 1.58** · **TypeScript** · **PostgreSQL seed via `pg`** · **Page Object Model**.

## Layout

```
e2e/
├── playwright.config.ts       # multi-project: chromium / firefox / webkit / mobile-chrome / mobile-safari / admin
├── global-setup.ts            # seeds admin/demo/secondary users, writes storageState per role
├── global-teardown.ts         # closes DB pool
├── fixtures/
│   └── auth.ts                # `test` fixture with asAdmin / asDemo / asSecondary / asAnonymous
├── helpers/
│   ├── env.ts                 # all config in one place (.env driven)
│   ├── db.ts                  # pg queries: activateUser, promoteToAdmin, setFullName, cleanup
│   ├── api.ts                 # REST helpers (register, login, /me, websites, orgs)
│   └── users.ts               # ensureUser / ensureAdmin / createEphemeralUser
├── pages/                     # Page Object Models
├── specs/                     # regression specs grouped by feature
│   ├── auth/       login, signup, password-reset, session
│   ├── onboarding/ first-login
│   ├── websites/   CRUD + UI
│   ├── monitors/   sub-type toggles
│   ├── organizations/  create, switch, invite
│   ├── admin/      admin API + UI (uses admin storageState)
│   ├── settings/   profile, theme
│   └── health.spec.ts
└── migrated/                  # legacy specs awaiting migration to specs/
```

## Prerequisites

Local stack running on default ports:

- **UI** → http://localhost:3000 (`cd nabu-ui && npm run dev`)
- **API** → http://localhost:8080 (`./gradlew bootRun`)
- **Postgres** → localhost:5432/nabu_watch (postgres / admin)
- **Redis** → localhost:6379
- **Go worker** → `cd nabu-worker && go run .` (optional — only needed for scans-triggering specs)

## Setup

```bash
cd e2e
cp .env.example .env                 # tweak if needed
npm install
npx playwright install --with-deps chromium
```

## Run

```bash
# full regression (all projects)
npm test

# smoke only (fastest, run before PRs)
npm run test:smoke

# by tag
npm run test:regression
npm run test:admin

# by browser / viewport
npm run test:chromium
npm run test:mobile

# debugging
npm run test:ui        # Playwright UI mode
npm run test:headed    # watch a browser
npm run test:debug     # PWDEBUG inspector

# HTML report after a run
npm run report
```

## How authentication works

`global-setup.ts` runs once per invocation:

1. Hits `/actuator/health` to verify the API is up.
2. For each of `admin`, `demo`, `secondary`:
   - `POST /api/v1/auth/register` (ignores 409 conflict — user already exists)
   - SQL `UPDATE users SET status='ACTIVE', email_verified_at=NOW()` — bypasses the email verification link
   - Admin only: SQL `UPDATE users SET role='ADMIN'`
   - `POST /api/v1/auth/login` → JWT
3. Writes three `storageState` files to `.auth/admin.json`, `.auth/demo.json`, `.auth/secondary.json` with the `nabu_token` cookie set.
4. Writes raw tokens to `.auth/tokens.json` for the `tokens` fixture (used by API-only specs).

Specs opt into a role via fixtures:

```ts
import { test, expect } from "../../fixtures/auth";

test("demo user sees dashboard", async ({ page, asDemo }) => {
    await page.goto("/dashboard");
    // ...
});

test("admin API returns user list", async ({ api, tokens }) => {
    const res = await api.get("/api/v1/admin/users", {
        headers: { Authorization: `Bearer ${tokens.admin}` },
    });
    expect(res.ok()).toBeTruthy();
});
```

The `admin-chromium` project in `playwright.config.ts` automatically attaches `admin.json` as `storageState` for everything under `specs/admin/` — no fixture needed.

## Ephemeral users

For tests that need a fresh user (e.g. onboarding, first-login, org-creation flows):

```ts
import { createEphemeralUser } from "../../helpers/users";
import { deleteUserCascade } from "../../helpers/db";

const { user, session } = await createEphemeralUser("e2e-myflow");
try {
    // ...use session.token...
} finally {
    await deleteUserCascade(user.email);
}
```

Each call creates a unique `e2e-myflow-<ts>-<rand>@nabu.test`, registers + activates + logs in. The test cleans up by cascading through scan_logs → monitors → websites → org members → orgs → user.

## Tags

- `@smoke` — must-pass before every commit (health, login, dashboard load)
- `@regression` — full functional coverage (the default target for CI)
- `@admin` — admin-only flows

Run a single tag with `npx playwright test --grep @smoke`.

## CI integration

The config emits a JUnit report at `test-results/junit.xml` and an HTML report at `playwright-report/`. Retries are enabled only in CI (`process.env.CI`). Suggested GitHub Actions workflow:

```yaml
- run: cd e2e && npm ci && npx playwright install --with-deps chromium
- run: cd e2e && npm test
  env:
    UI_URL: http://localhost:3000
    API_URL: http://localhost:8080
    CI: "true"
- uses: actions/upload-artifact@v4
  if: always()
  with:
    name: playwright-report
    path: e2e/playwright-report
```

## Adding a new spec

1. Put it under the feature folder in `specs/` — group by product area, not by phase/ticket.
2. Import from `"../../fixtures/auth"` (never from `@playwright/test` directly — you lose the role fixtures).
3. Tag with `@smoke` if it must pass before every merge; otherwise default to `@regression`.
4. For API-driven setup use the `api` fixture + helpers in `helpers/api.ts`. Avoid navigating through the UI just to get into state — it's slow and flaky.
5. Always clean up created users with `deleteUserCascade(email)` in a `finally`.
