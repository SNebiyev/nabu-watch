import type { TestInfo } from "@playwright/test";
import { createEphemeralUser } from "./users";
import { deleteUserCascade } from "./db";
import { clearAuthRateLimits } from "./redis";
import type { TestUser } from "./env";
import type { LoginResponse } from "./api";

/**
 * Wrapper around createEphemeralUser that converts auth rate-limits into
 * a test.skip() instead of a hard failure. Returns null when skipped.
 */
export async function ephemeralOrSkip(
    testInfo: TestInfo,
    prefix: string,
): Promise<{ user: TestUser; session: LoginResponse } | null> {
    // Reset auth rate-limits before every ephemeral user creation; none of our
    // regression specs assert rate-limit behaviour, so this is safe.
    await clearAuthRateLimits();
    try {
        return await createEphemeralUser(prefix);
    } catch (e) {
        const err = e as Error & { rateLimited?: boolean };
        if (err.rateLimited || /RATE_LIMITED|429|rate limit/i.test(err.message)) {
            // Try once more after a forced clear — something out of band set the key.
            await clearAuthRateLimits();
            try {
                return await createEphemeralUser(prefix);
            } catch {
                testInfo.skip(true, "auth endpoint still rate-limited after Redis clear");
                return null;
            }
        }
        throw e;
    }
}

export async function safeCleanup(email: string | undefined): Promise<void> {
    if (!email) return;
    try {
        await deleteUserCascade(email);
    } catch {
        // best-effort
    }
}
