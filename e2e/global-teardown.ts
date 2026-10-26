import { closePg, deleteUserCascade, sql } from "./helpers/db";
import { closeRedis } from "./helpers/redis";

export default async function globalTeardown(): Promise<void> {
    try {
        const rows = await sql<{ email: string }>(
            `SELECT email FROM users WHERE email LIKE '%@nabu.test'`,
        );
        for (const { email } of rows) {
            try {
                await deleteUserCascade(email);
            } catch {
                // Best-effort cleanup; continue on per-user failures.
            }
        }
        if (rows.length > 0) {
            console.log(`[teardown] Cleaned up ${rows.length} test user(s)`);
        }
    } catch (e) {
        console.warn("[teardown] Test-user cleanup skipped:", e);
    }

    await closePg();
    await closeRedis();
}
