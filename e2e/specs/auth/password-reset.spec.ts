import { test, expect } from "../../fixtures/auth";
import { ENV } from "../../helpers/env";
import { clearAuthRateLimits } from "../../helpers/redis";

test.beforeEach(async () => {
    await clearAuthRateLimits();
});

test.describe("@regression auth — password reset", () => {
    test("POST /auth/forgot-password always returns 200 (no email leak)", async ({ api }) => {
        const res = await api.post("/api/v1/auth/forgot-password", {
            data: { email: ENV.DEMO.email },
        });
        expect(res.status()).toBe(200);
    });

    test("POST /auth/forgot-password returns 200 for unknown email too", async ({ api }) => {
        const res = await api.post("/api/v1/auth/forgot-password", {
            data: { email: `unknown-${Date.now()}@nabu.test` },
        });
        expect(res.status()).toBe(200);
    });

    test("POST /auth/reset-password rejects invalid token", async ({ api }) => {
        const res = await api.post("/api/v1/auth/reset-password", {
            data: { token: "invalid-token-xyz", newPassword: "BrandNewPass!23" },
        });
        expect(res.status()).toBeGreaterThanOrEqual(400);
    });
});
