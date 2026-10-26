import * as dotenv from "dotenv";
import * as path from "path";

dotenv.config({ path: path.resolve(__dirname, "..", ".env") });

function env(name: string, fallback: string): string {
    return process.env[name] ?? fallback;
}

export const ENV = {
    UI_URL: env("UI_URL", "http://localhost:3000"),
    API_URL: env("API_URL", "http://localhost:8080"),

    DB: {
        host: env("DB_HOST", "localhost"),
        port: Number(env("DB_PORT", "5432")),
        database: env("DB_NAME", "nabu_watch"),
        user: env("DB_USER", "postgres"),
        password: env("DB_PASS", "admin"),
    },

    REDIS: {
        host: env("REDIS_HOST", "localhost"),
        port: Number(env("REDIS_PORT", "6379")),
        password: env("REDIS_PASSWORD", "") || undefined,
    },

    ADMIN: {
        email: env("E2E_ADMIN_EMAIL", "e2e-admin@nabu.test"),
        password: env("E2E_ADMIN_PASSWORD", "E2eAdminPass!23"),
        name: "Test User",
    },
    DEMO: {
        email: env("E2E_DEMO_EMAIL", "e2e-demo@nabu.test"),
        password: env("E2E_DEMO_PASSWORD", "E2eDemoPass!23"),
        name: "E2E Demo",
    },
    SECONDARY: {
        email: env("E2E_SECONDARY_EMAIL", "e2e-secondary@nabu.test"),
        password: env("E2E_SECONDARY_PASSWORD", "E2eSecondary!23"),
        name: "E2E Secondary",
    },

    COOKIE_NAME: "nabu_token",
    FRESH_AUTH: env("E2E_FRESH_AUTH", "0") === "1",
    ALLOW_REMOTE: env("E2E_ALLOW_REMOTE", "0") === "1",
} as const;

const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "::1", "0.0.0.0"]);

/**
 * Refuse to run against non-local infrastructure without explicit opt-in.
 * global-setup runs destructive SQL (user activation, admin promotion) and
 * Redis UNLINK on rate-limit keys — catastrophic if pointed at production.
 */
export function assertLocalTargets(): void {
    if (ENV.ALLOW_REMOTE) return;
    const uiHost = new URL(ENV.UI_URL).hostname;
    const apiHost = new URL(ENV.API_URL).hostname;
    const offenders: string[] = [];
    if (!LOCAL_HOSTS.has(uiHost)) offenders.push(`UI_URL=${ENV.UI_URL}`);
    if (!LOCAL_HOSTS.has(apiHost)) offenders.push(`API_URL=${ENV.API_URL}`);
    if (!LOCAL_HOSTS.has(ENV.DB.host)) offenders.push(`DB_HOST=${ENV.DB.host}`);
    if (!LOCAL_HOSTS.has(ENV.REDIS.host)) offenders.push(`REDIS_HOST=${ENV.REDIS.host}`);
    if (offenders.length > 0) {
        throw new Error(
            `[e2e] refusing to run: targets must be local unless E2E_ALLOW_REMOTE=1. Offending values: ${offenders.join(", ")}`,
        );
    }
}

export type TestUser = {
    email: string;
    password: string;
    name: string;
};
