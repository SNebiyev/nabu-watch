import { test, expect } from "../../fixtures/auth";
import { ephemeralOrSkip, safeCleanup } from "../../helpers/ephemeral";

test.describe("@regression onboarding — first login", () => {
    test("fresh user sees firstLogin=true in /auth/me", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-first");
        if (!ctx) return;
        try {
            const res = await api.get("/api/v1/auth/me", {
                headers: { Authorization: `Bearer ${ctx.session.token}` },
            });
            expect(res.ok()).toBeTruthy();
            const body = await res.json();
            expect(body.firstLogin).toBe(true);
            expect(body.organizations).toHaveLength(1);
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });

    test("adding first website transitions firstLogin to false", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-onb");
        if (!ctx) return;
        try {
            const created = await api.post("/api/v1/websites", {
                headers: { Authorization: `Bearer ${ctx.session.token}` },
                data: { url: "https://example.com", region: "DE", ownershipAttestation: true },
            });
            expect(created.ok()).toBeTruthy();

            const me = await api.get("/api/v1/auth/me", {
                headers: { Authorization: `Bearer ${ctx.session.token}` },
            });
            const body = await me.json();
            expect(body.firstLogin).toBe(false);
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });
});
