import Redis from "ioredis";
import { ENV } from "./env";

let client: Redis | null = null;

function redis(): Redis {
    if (!client) {
        client = new Redis({
            host: ENV.REDIS.host,
            port: ENV.REDIS.port,
            password: ENV.REDIS.password,
            maxRetriesPerRequest: 2,
            lazyConnect: false,
        });
        client.on("error", () => {
            // Swallow — in CI we may not have Redis wired up; callers are best-effort.
        });
    }
    return client;
}

export async function closeRedis(): Promise<void> {
    if (client) {
        await client.quit().catch(() => {});
        client = null;
    }
}

async function deleteByPattern(pattern: string): Promise<number> {
    const r = redis();
    const keys: string[] = [];
    try {
        const stream = r.scanStream({ match: pattern, count: 200 });
        await new Promise<void>((resolve, reject) => {
            stream.on("data", (batch: string[]) => keys.push(...batch));
            stream.on("end", () => resolve());
            stream.on("error", reject);
        });
        if (keys.length === 0) return 0;
        // Batch deletes to avoid huge UNLINK arg lists.
        let deleted = 0;
        for (let i = 0; i < keys.length; i += 500) {
            deleted += await r.unlink(...keys.slice(i, i + 500));
        }
        return deleted;
    } catch {
        return 0;
    }
}

/**
 * Wipes all auth-related rate-limit keys so tests don't self-throttle.
 * Patterns match the backend's key scheme in AuthRateLimitService:
 *   rl:auth:login:<ip>          — per-IP login attempt counter
 *   rl:auth:register:<ip>       — per-IP registration counter
 *   rl:auth:reset:<email>       — password-reset counter
 *   rl:auth:verify:<email>      — resend-verification counter
 *   rl:auth:failed:<email>      — consecutive failed-login counter
 */
export async function clearAuthRateLimits(): Promise<number> {
    return deleteByPattern("rl:auth:*");
}

/**
 * Wipes scan rescan cooldown keys (per-domain + per-user). Useful for tests
 * that trigger multiple rescans on example.com within the 1h window.
 */
export async function clearScanRateLimits(): Promise<number> {
    const [a, b] = await Promise.all([
        deleteByPattern("rl:domain:*"),
        deleteByPattern("rl:user:*:rescan"),
    ]);
    return a + b;
}

export async function clearAllE2eRateLimits(): Promise<number> {
    const [a, b] = await Promise.all([clearAuthRateLimits(), clearScanRateLimits()]);
    return a + b;
}

export async function resetRescanCooldowns(websiteId: string): Promise<void> {
    const { clearWebsiteRescanCooldown } = await import("./db");
    await Promise.all([clearScanRateLimits(), clearWebsiteRescanCooldown(websiteId)]);
}

/** Stream consumed by ResultConsumer.java — do not rename without updating the backend. */
export const SCAN_RESULTS_STREAM = "nabu:scan-results";

export type SyntheticScanResult = {
    monitorId: string;
    status?: "SUCCESS" | "FAILED" | "SKIPPED_WAF" | "SKIPPED_TRANSIENT" | "SKIPPED_BATCH_ABORT";
    payload?: Record<string, string>;
    batchId?: string | null;
    taskId?: string;
};

/**
 * Publish a synthetic scan result into the Redis stream so ResultConsumer
 * persists a ScanLog without the Go worker running. Returns the stream entry id.
 */
export async function publishScanResult(r: SyntheticScanResult): Promise<string> {
    const message = {
        task_id: r.taskId ?? `e2e-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        monitor_id: r.monitorId,
        status: r.status ?? "SUCCESS",
        payload: r.payload ?? { status: "COMPLIANT" },
        published_at: new Date().toISOString(),
        batch_id: r.batchId ?? null,
        raw_dom: null,
    };
    const id = await redis().xadd(SCAN_RESULTS_STREAM, "*", "result", JSON.stringify(message));
    if (!id) throw new Error("XADD returned null");
    return id;
}
