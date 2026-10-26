import { test, expect, request } from "@playwright/test";
import * as crypto from "node:crypto";

const API = process.env.E2E_API_URL ?? "http://localhost:8081";
const JWT_SECRET =
    process.env.E2E_JWT_SECRET ?? ""; // must equal the JWT_SECRET the API under test runs with
const USER_ID = process.env.E2E_ADMIN_USER_ID;

function base64Url(buf: Buffer): string {
    return buf.toString("base64url");
}

function unsubscribeToken(userId: string, type: string): string {
    const secret = Buffer.from(`unsubscribe:${JWT_SECRET}`);
    const sig = crypto
        .createHmac("sha256", secret)
        .update(`${userId}:${type}`)
        .digest();
    return `${base64Url(Buffer.from(userId))}:${type}:${base64Url(sig)}`;
}

test.describe("Phase 5f — lifecycle unsubscribe is independent of scan alerts", () => {
    test.skip(!USER_ID, "E2E_ADMIN_USER_ID not set");

    test("GET /unsubscribe labels the lifecycle type", async () => {
        const ctx = await request.newContext({ baseURL: API });
        const token = unsubscribeToken(USER_ID!, "lifecycle");
        const res = await ctx.get(`/api/v1/unsubscribe?token=${token}`);
        expect(res.ok()).toBeTruthy();
        const body = await res.json();
        expect(body.type).toBe("lifecycle");
        expect(body.label).toContain("lifecycle");
        await ctx.dispose();
    });

    test("POST /unsubscribe with lifecycle token flips only lifecycle_emails_enabled", async () => {
        const ctx = await request.newContext({ baseURL: API });
        const token = unsubscribeToken(USER_ID!, "lifecycle");
        const res = await ctx.post(`/api/v1/unsubscribe?token=${token}`);
        expect(res.ok()).toBeTruthy();
        const body = await res.json();
        expect(body.success).toBe(true);
        expect(body.message).toContain("lifecycle");
        await ctx.dispose();
    });

    test("Rejects unknown type with 400", async () => {
        const ctx = await request.newContext({ baseURL: API });
        const token = unsubscribeToken(USER_ID!, "bogus");
        const res = await ctx.post(`/api/v1/unsubscribe?token=${token}`);
        expect(res.status()).toBe(400);
        await ctx.dispose();
    });
});
