import { request, APIRequestContext } from "@playwright/test";
import { ENV } from "./env";
import type { MonitorSubType, MonitorSummary } from "./types";

export type LoginResponse = {
    token: string;
    tokenType: string;
    expiresAt: string;
    userId: string;
    role: "USER" | "ADMIN";
    requirePasswordChange: boolean;
};

export async function newApi(): Promise<APIRequestContext> {
    return request.newContext({ baseURL: ENV.API_URL });
}

export async function register(
    api: APIRequestContext,
    email: string,
    password: string,
    name: string,
): Promise<void> {
    const res = await api.post("/api/v1/auth/register", {
        data: { email, password, name },
    });
    // 429 rate-limit: user likely already registered by a previous run; caller verifies via DB.
    if (![200, 201, 409, 429].includes(res.status())) {
        throw new Error(`register failed ${res.status()}: ${await res.text()}`);
    }
}

export async function login(
    api: APIRequestContext,
    email: string,
    password: string,
): Promise<LoginResponse> {
    const res = await api.post("/api/v1/auth/login", { data: { email, password } });
    if (!res.ok()) {
        throw new Error(`login failed ${res.status()}: ${await res.text()}`);
    }
    return (await res.json()) as LoginResponse;
}

export function authHeaders(token: string): Record<string, string> {
    return { Authorization: `Bearer ${token}` };
}

export async function me(api: APIRequestContext, token: string): Promise<unknown> {
    const res = await api.get("/api/v1/auth/me", { headers: authHeaders(token) });
    if (!res.ok()) throw new Error(`/me failed ${res.status()}`);
    return res.json();
}

export async function createWebsite(
    api: APIRequestContext,
    token: string,
    url: string,
    region = "DE",
): Promise<{ websiteId: string; url: string; monitors: MonitorSummary[] }> {
    const res = await api.post("/api/v1/websites", {
        headers: authHeaders(token),
        data: { url, region, ownershipAttestation: true },
    });
    if (!res.ok()) throw new Error(`createWebsite ${res.status()}: ${await res.text()}`);
    const body = (await res.json()) as {
        id: string;
        url: string;
        monitorsByStatus: Record<string, MonitorSummary[]>;
    };
    const monitors = Object.values(body.monitorsByStatus ?? {}).flat();
    return { websiteId: body.id, url: body.url, monitors };
}

export function monitorBySubType(monitors: MonitorSummary[], subType: MonitorSubType): MonitorSummary {
    const found = monitors.find((m) => m.subType === subType);
    if (!found) throw new Error(`monitor with subType=${subType} not found on website`);
    return found;
}

let urlCounter = 0;
export function uniqueTestUrl(slug: string): string {
    urlCounter += 1;
    return `https://e2e-${slug}-${Date.now()}-${urlCounter}.example`;
}

export async function listWebsites(api: APIRequestContext, token: string): Promise<{ content: unknown[] }> {
    const res = await api.get("/api/v1/websites?page=0&size=50", { headers: authHeaders(token) });
    if (!res.ok()) throw new Error(`listWebsites ${res.status()}`);
    return res.json();
}

export async function createOrganization(
    api: APIRequestContext,
    token: string,
    name: string,
): Promise<{ id: string; name: string }> {
    const res = await api.post("/api/v1/organizations", {
        headers: authHeaders(token),
        data: { name },
    });
    if (!res.ok()) throw new Error(`createOrg ${res.status()}: ${await res.text()}`);
    return (await res.json()) as { id: string; name: string };
}

export async function adminListUsers(api: APIRequestContext, token: string): Promise<unknown> {
    const res = await api.get("/api/v1/admin/users?page=0&size=50", { headers: authHeaders(token) });
    if (!res.ok()) throw new Error(`adminListUsers ${res.status()}`);
    return res.json();
}

export async function setCookieAuth(page: import("@playwright/test").Page, token: string): Promise<void> {
    const url = new URL(ENV.UI_URL);
    await page.context().addCookies([
        {
            name: ENV.COOKIE_NAME,
            value: token,
            domain: url.hostname,
            path: "/",
            httpOnly: false,
            secure: false,
            sameSite: "Lax",
            expires: Math.floor(Date.now() / 1000) + 7 * 24 * 3600,
        },
    ]);
}
