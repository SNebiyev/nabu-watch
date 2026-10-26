import { test, expect } from "../../fixtures/auth";
import { ephemeralOrSkip, safeCleanup } from "../../helpers/ephemeral";
import { authHeaders } from "../../helpers/api";

test.describe("@regression websites — CRUD via API", () => {
    test("create, list, get-health, delete-otp flow", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-web");
        if (!ctx) return;
        const h = authHeaders(ctx.session.token);
        try {
            const created = await api.post("/api/v1/websites", {
                headers: h,
                data: { url: "https://example.org", region: "DE", ownershipAttestation: true },
            });
            expect(created.ok()).toBeTruthy();
            const website = await created.json();
            expect(website.id).toBeTruthy();

            const listed = await api.get("/api/v1/websites?page=0&size=50", { headers: h });
            expect(listed.ok()).toBeTruthy();
            const list = await listed.json();
            expect(list.content.length).toBeGreaterThanOrEqual(1);

            const health = await api.get(`/api/v1/websites/${website.id}/health`, { headers: h });
            expect(health.ok()).toBeTruthy();

            const monitors = await api.get(`/api/v1/websites/${website.id}/monitors`, { headers: h });
            expect(monitors.ok()).toBeTruthy();
            expect(Array.isArray(await monitors.json())).toBeTruthy();
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });

    test("invalid URL is rejected", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-web-bad");
        if (!ctx) return;
        try {
            const res = await api.post("/api/v1/websites", {
                headers: authHeaders(ctx.session.token),
                data: { url: "not-a-url", region: "DE", ownershipAttestation: true },
            });
            expect(res.status()).toBeGreaterThanOrEqual(400);
            expect(res.status()).toBeLessThan(500);
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });

    test("unauthenticated website list returns 401", async ({ api }) => {
        const res = await api.get("/api/v1/websites");
        expect(res.status()).toBe(401);
    });
});
