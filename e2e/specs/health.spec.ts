import { test, expect } from "../fixtures/auth";
import { ENV } from "../helpers/env";

test.describe("@smoke infra", () => {
    test("API /actuator/health is UP", async ({ api }) => {
        const res = await api.get("/actuator/health");
        expect(res.ok()).toBeTruthy();
        const body = await res.json();
        expect(body.status).toBe("UP");
    });

    test("UI home page responds", async ({ page, asAnonymous }) => {
        const res = await page.goto(ENV.UI_URL);
        expect(res?.ok()).toBeTruthy();
    });
});
