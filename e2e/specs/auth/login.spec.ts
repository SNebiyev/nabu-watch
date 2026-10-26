import { test, expect } from "../../fixtures/auth";
import { LoginPage } from "../../pages/LoginPage";
import { ENV } from "../../helpers/env";
import { clearAuthRateLimits } from "../../helpers/redis";

test.beforeEach(async () => {
    await clearAuthRateLimits();
});

test.describe("@smoke @regression auth — login", () => {
    test("demo user can log in via UI and lands on app", async ({ page, asAnonymous }) => {
        const loginPage = new LoginPage(page);
        await loginPage.goto();
        await loginPage.login(ENV.DEMO.email, ENV.DEMO.password);
        await expect(page).toHaveURL(/\/dashboard/, { timeout: 15_000 });
    });

    test("invalid password is rejected", async ({ page, asAnonymous }) => {
        const loginPage = new LoginPage(page);
        await loginPage.goto();
        await loginPage.login(ENV.DEMO.email, "WrongPassword!1");
        await expect(page).toHaveURL(/\/login/);
    });

    test("unknown email is rejected", async ({ page, asAnonymous }) => {
        const loginPage = new LoginPage(page);
        await loginPage.goto();
        await loginPage.login("nobody-" + Date.now() + "@nabu.test", "Whatever!1");
        await expect(page).toHaveURL(/\/login/);
    });
});

test.describe("@regression auth — API login", () => {
    test.describe.configure({ mode: "serial" });

    test("POST /auth/login returns JWT for seeded demo", async ({ api }) => {
        const res = await api.post("/api/v1/auth/login", {
            data: { email: ENV.DEMO.email, password: ENV.DEMO.password },
        });
        expect(res.ok()).toBeTruthy();
        const body = await res.json();
        expect(body.token).toBeTruthy();
        expect(body.role).toBe("USER");
    });

    test("POST /auth/login rejects bad password", async ({ api }) => {
        const res = await api.post("/api/v1/auth/login", {
            data: { email: ENV.DEMO.email, password: "nope" },
        });
        expect(res.status()).toBe(401);
    });
});
