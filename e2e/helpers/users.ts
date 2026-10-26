import { ENV, TestUser } from "./env";
import { activateUser, getUserIdByEmail, promoteToAdmin, setFullName } from "./db";
import { login, register, LoginResponse, newApi } from "./api";

/**
 * Idempotently ensure a user exists, is email-verified, and returns a valid JWT.
 * Strategy: attempt REST registration (ignore 409 conflict), then use SQL to
 * force-activate + bypass email verification, then log in via REST.
 */
export async function ensureUser(
    user: TestUser,
    opts: { admin?: boolean } = {},
): Promise<LoginResponse> {
    const api = await newApi();
    try {
        const existing = await getUserIdByEmail(user.email);
        if (!existing) {
            await register(api, user.email, user.password, user.name);
        }
        const activated = await activateUser(user.email);
        if (!activated) {
            // Most common cause: the API rate-limiter ate the register request and no user row was created.
            // Surface a recognisable sentinel so specs can skip instead of failing the run.
            const err = new Error(`RATE_LIMITED_OR_BLOCKED: user ${user.email} not in DB after register`);
            (err as Error & { rateLimited?: boolean }).rateLimited = true;
            throw err;
        }
        if (opts.admin) {
            await promoteToAdmin(user.email);
            // Keep the admin display name stable (the migrated public-profile spec expects "Test User").
            await setFullName(user.email, user.name);
        }
        const session = await login(api, user.email, user.password);
        return session;
    } finally {
        await api.dispose();
    }
}

export async function ensureAdmin(): Promise<LoginResponse> {
    return ensureUser(ENV.ADMIN, { admin: true });
}

export async function ensureDemo(): Promise<LoginResponse> {
    return ensureUser(ENV.DEMO);
}

export async function ensureSecondary(): Promise<LoginResponse> {
    return ensureUser(ENV.SECONDARY);
}

/** Create an ephemeral user with a unique email. Returns credentials + session. */
export async function createEphemeralUser(
    prefix = "e2e-tmp",
): Promise<{ user: TestUser; session: LoginResponse }> {
    const rand = Math.random().toString(36).slice(2, 10);
    const user: TestUser = {
        email: `${prefix}-${Date.now()}-${rand}@nabu.test`,
        password: "EphemeralPass!23",
        name: `Ephemeral ${rand}`,
    };
    const session = await ensureUser(user);
    return { user, session };
}

export async function loginViaApi(user: TestUser): Promise<LoginResponse> {
    const api = await newApi();
    try {
        return await login(api, user.email, user.password);
    } finally {
        await api.dispose();
    }
}
