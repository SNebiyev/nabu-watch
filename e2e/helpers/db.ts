import { Pool } from "pg";
import { ENV } from "./env";

let pool: Pool | null = null;

export function pg(): Pool {
    if (!pool) {
        pool = new Pool({ ...ENV.DB, max: 10 });
    }
    return pool;
}

export async function closePg(): Promise<void> {
    if (pool) {
        await pool.end();
        pool = null;
    }
}

export async function sql<T = unknown>(text: string, params: unknown[] = []): Promise<T[]> {
    const res = await pg().query(text, params);
    return res.rows as T[];
}

/**
 * Force-activate a user by email (bypasses email verification flow).
 * Sets status=ACTIVE, email_verified_at=now.
 * Returns the user's UUID or null if the user does not exist.
 */
export async function activateUser(email: string): Promise<string | null> {
    const rows = await sql<{ id: string }>(
        `UPDATE users
         SET status = 'ACTIVE',
             email_verified_at = COALESCE(email_verified_at, NOW())
         WHERE LOWER(email) = LOWER($1)
         RETURNING id`,
        [email],
    );
    return rows[0]?.id ?? null;
}

export async function promoteToAdmin(email: string): Promise<void> {
    await sql(`UPDATE users SET role = 'ADMIN' WHERE LOWER(email) = LOWER($1)`, [email]);
}

export async function setFullName(email: string, fullName: string): Promise<void> {
    await sql(`UPDATE users SET full_name = $2 WHERE LOWER(email) = LOWER($1)`, [email, fullName]);
}

export async function getUserIdByEmail(email: string): Promise<string | null> {
    const rows = await sql<{ id: string }>(
        `SELECT id FROM users WHERE LOWER(email) = LOWER($1)`,
        [email],
    );
    return rows[0]?.id ?? null;
}

export async function clearWebsiteRescanCooldown(websiteId: string): Promise<void> {
    await sql(`UPDATE websites SET last_manual_scan_at = NULL WHERE id = $1`, [websiteId]);
}

export async function latestScanLogForMonitor(monitorId: string): Promise<{ id: string; severity: string } | null> {
    const rows = await sql<{ id: string; severity: string }>(
        `SELECT id, severity FROM scan_logs WHERE monitor_id = $1 ORDER BY created_at DESC LIMIT 1`,
        [monitorId],
    );
    return rows[0] ?? null;
}

export async function countScanLogsForMonitor(monitorId: string): Promise<number> {
    const rows = await sql<{ c: string }>(
        `SELECT COUNT(*)::text AS c FROM scan_logs WHERE monitor_id = $1`,
        [monitorId],
    );
    return Number(rows[0]?.c ?? 0);
}

export async function deleteUserCascade(email: string): Promise<void> {
    const id = await getUserIdByEmail(email);
    if (!id) return;
    // FK-safe best-effort delete. Ignore tables that do not exist.
    const statements = [
        `DELETE FROM scan_logs WHERE monitor_id IN
           (SELECT m.id FROM monitors m JOIN websites w ON w.id = m.website_id
            WHERE w.organization_id IN (SELECT current_organization_id FROM users WHERE id = $1))`,
        `DELETE FROM monitors WHERE website_id IN
           (SELECT id FROM websites WHERE organization_id IN (SELECT current_organization_id FROM users WHERE id = $1))`,
        `DELETE FROM websites WHERE organization_id IN (SELECT current_organization_id FROM users WHERE id = $1)`,
        `DELETE FROM webhook_deliveries WHERE webhook_config_id IN
           (SELECT id FROM webhook_configs WHERE organization_id IN
             (SELECT current_organization_id FROM users WHERE id = $1))`,
        `DELETE FROM webhook_configs WHERE organization_id IN (SELECT current_organization_id FROM users WHERE id = $1)`,
        `DELETE FROM organization_members WHERE user_id = $1`,
        `DELETE FROM organizations WHERE id IN (SELECT current_organization_id FROM users WHERE id = $1)`,
        `DELETE FROM users WHERE id = $1`,
    ];
    for (const stmt of statements) {
        try {
            await sql(stmt, [id]);
        } catch (e) {
            // Table may not exist in this env — safe to ignore for cleanup.
        }
    }
}
