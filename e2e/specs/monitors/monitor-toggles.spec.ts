import { test, expect } from "../../fixtures/auth";
import { ephemeralOrSkip, safeCleanup } from "../../helpers/ephemeral";
import { authHeaders } from "../../helpers/api";

test.describe("@regression monitors — enable/disable sub-types", () => {
    test("PUT /websites/{id}/monitors toggles sub-types idempotently", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-mon");
        if (!ctx) return;
        const h = authHeaders(ctx.session.token);
        try {
            const created = await api.post("/api/v1/websites", {
                headers: h,
                data: { url: "https://example.net", region: "DE", ownershipAttestation: true },
            });
            const website = await created.json();

            const put = await api.put(`/api/v1/websites/${website.id}/monitors`, {
                headers: h,
                data: { enabledSubTypes: ["PRIVACY_POLICY", "SSL"] },
            });
            expect(put.ok()).toBeTruthy();

            const monitors = await api.get(`/api/v1/websites/${website.id}/monitors`, { headers: h });
            const list = (await monitors.json()) as Array<{ subType: string; status: string }>;
            const subs = list.map((m) => m.subType);
            expect(subs).toContain("PRIVACY_POLICY");
            expect(subs).toContain("SSL");

            const repeat = await api.put(`/api/v1/websites/${website.id}/monitors`, {
                headers: h,
                data: { enabledSubTypes: ["PRIVACY_POLICY", "SSL"] },
            });
            expect(repeat.ok()).toBeTruthy();
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });
});
