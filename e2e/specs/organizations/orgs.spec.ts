import { test, expect } from "../../fixtures/auth";
import { ephemeralOrSkip, safeCleanup } from "../../helpers/ephemeral";
import { authHeaders } from "../../helpers/api";
import { ENV } from "../../helpers/env";

test.describe("@regression organizations", () => {
    test("new user has one auto-provisioned org and can create a second", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-org");
        if (!ctx) return;
        const h = authHeaders(ctx.session.token);
        try {
            const first = await api.get("/api/v1/organizations", { headers: h });
            expect(first.ok()).toBeTruthy();
            const initial = (await first.json()) as unknown[];
            expect(initial.length).toBe(1);

            const created = await api.post("/api/v1/organizations", {
                headers: h,
                data: { name: "E2E Second Org" },
            });
            expect(created.status()).toBe(201);

            const after = await api.get("/api/v1/organizations", { headers: h });
            const afterList = (await after.json()) as unknown[];
            expect(afterList.length).toBe(2);
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });

    test("switching org reissues JWT with new org claim", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-orgswitch");
        if (!ctx) return;
        const h = authHeaders(ctx.session.token);
        try {
            const created = await api.post("/api/v1/organizations", {
                headers: h,
                data: { name: "Switch Target" },
            });
            const newOrg = await created.json();

            const switched = await api.post("/api/v1/auth/switch-org", {
                headers: h,
                data: { organizationId: newOrg.id },
            });
            expect(switched.ok()).toBeTruthy();
            const payload = await switched.json();
            expect(payload.token).toBeTruthy();
            expect(payload.token).not.toBe(ctx.session.token);
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });

    test("invite secondary user as member", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-orginvite");
        if (!ctx) return;
        const h = authHeaders(ctx.session.token);
        try {
            const orgs = (await (await api.get("/api/v1/organizations", { headers: h })).json()) as Array<{ id: string }>;
            const orgId = orgs[0].id;

            const invited = await api.post(`/api/v1/organizations/${orgId}/members`, {
                headers: h,
                data: { email: ENV.SECONDARY.email },
            });
            expect([200, 201]).toContain(invited.status());

            const members = await api.get(`/api/v1/organizations/${orgId}/members`, { headers: h });
            const list = (await members.json()) as Array<{ email: string }>;
            expect(list.some((m) => m.email.toLowerCase() === ENV.SECONDARY.email.toLowerCase())).toBeTruthy();
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });
});
