import * as fs from "fs";
import * as path from "path";
import { FullConfig } from "@playwright/test";
import { ENV, assertLocalTargets } from "./helpers/env";
import { ensureAdmin, ensureDemo, ensureSecondary } from "./helpers/users";
import { closePg } from "./helpers/db";
import { clearAllE2eRateLimits, closeRedis } from "./helpers/redis";
import { setCookieAuth, newApi, authHeaders } from "./helpers/api";
import { chromium } from "@playwright/test";

const AUTH_DIR = path.resolve(__dirname, ".auth");

async function writeStorageState(token: string, file: string): Promise<void> {
    const browser = await chromium.launch();
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.goto(ENV.UI_URL);
    await setCookieAuth(page, token);
    await ctx.storageState({ path: file });
    await browser.close();
}

async function assertServicesUp(): Promise<void> {
    const api = await newApi();
    try {
        const res = await api.get("/actuator/health");
        if (!res.ok()) throw new Error(`API health check failed: ${res.status()}`);
    } finally {
        await api.dispose();
    }
}

async function tokensStillValid(): Promise<{ admin: string; demo: string; secondary: string } | null> {
    const file = path.join(AUTH_DIR, "tokens.json");
    if (!fs.existsSync(file) || ENV.FRESH_AUTH) return null;
    const cached = JSON.parse(fs.readFileSync(file, "utf8")) as { admin: string; demo: string; secondary: string };
    const api = await newApi();
    try {
        const checks = await Promise.all(
            Object.values(cached).map((t) => api.get("/api/v1/auth/me", { headers: authHeaders(t) })),
        );
        if (checks.every((r) => r.ok())) return cached;
        return null;
    } catch {
        return null;
    } finally {
        await api.dispose();
    }
}

export default async function globalSetup(_config: FullConfig): Promise<void> {
    fs.mkdirSync(AUTH_DIR, { recursive: true });

    assertLocalTargets();

    // eslint-disable-next-line no-console
    console.log(`[e2e] global-setup — UI=${ENV.UI_URL} API=${ENV.API_URL}`);

    await assertServicesUp();

    const cleared = await clearAllE2eRateLimits();
    // eslint-disable-next-line no-console
    console.log(`[e2e] global-setup — cleared ${cleared} Redis rate-limit keys`);

    const cached = await tokensStillValid();
    let adminTok: string;
    let demoTok: string;
    let secondaryTok: string;
    if (cached) {
        // eslint-disable-next-line no-console
        console.log(`[e2e] global-setup — reusing cached tokens (all three validated via /auth/me)`);
        adminTok = cached.admin;
        demoTok = cached.demo;
        secondaryTok = cached.secondary;
    } else {
        const [admin, demo, secondary] = await Promise.all([
            ensureAdmin(),
            ensureDemo(),
            ensureSecondary(),
        ]);
        adminTok = admin.token;
        demoTok = demo.token;
        secondaryTok = secondary.token;
    }
    const admin = { token: adminTok };
    const demo = { token: demoTok };
    const secondary = { token: secondaryTok };

    await Promise.all([
        writeStorageState(admin.token, path.join(AUTH_DIR, "admin.json")),
        writeStorageState(demo.token, path.join(AUTH_DIR, "demo.json")),
        writeStorageState(secondary.token, path.join(AUTH_DIR, "secondary.json")),
    ]);

    // Tokens are also exposed via env for tests that hit the API directly.
    process.env.E2E_ADMIN_TOKEN = admin.token;
    process.env.E2E_DEMO_TOKEN = demo.token;
    process.env.E2E_SECONDARY_TOKEN = secondary.token;

    fs.writeFileSync(
        path.join(AUTH_DIR, "tokens.json"),
        JSON.stringify({ admin: admin.token, demo: demo.token, secondary: secondary.token }, null, 2),
    );

    // eslint-disable-next-line no-console
    console.log(`[e2e] global-setup done — seeded admin, demo, secondary users`);

    await closePg();
    await closeRedis();
}
