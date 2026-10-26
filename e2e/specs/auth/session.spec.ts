import { test, expect } from "../../fixtures/auth";
import { ENV } from "../../helpers/env";

test.describe("@smoke @regression auth — session", () => {
    test("anonymous visit to dashboard redirects to /login", async ({ page, asAnonymous }) => {
        await page.goto("/dashboard");
        await expect(page).toHaveURL(/\/(login|auth)/);
    });

    test("logged-in demo user reaches /auth/me via cookie", async ({ api, tokens }) => {
        const res = await api.get("/api/v1/auth/me", {
            headers: { Authorization: `Bearer ${tokens.demo}` },
        });
        expect(res.ok()).toBeTruthy();
        const body = await res.json();
        expect(body.email.toLowerCase()).toBe(ENV.DEMO.email.toLowerCase());
    });

    test("invalid bearer token returns 401 on /auth/me", async ({ api }) => {
        const res = await api.get("/api/v1/auth/me", {
            headers: { Authorization: "Bearer not-a-real-token" },
        });
        expect(res.status()).toBe(401);
    });
});
