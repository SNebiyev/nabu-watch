import { test, expect } from "../../fixtures/auth";
import { WebsitesPage } from "../../pages/WebsitesPage";

test.describe("@smoke websites — UI", () => {
    test("demo user opens websites page and sees heading", async ({ page, asDemo }) => {
        const websites = new WebsitesPage(page);
        await websites.goto();
        await websites.expectLoaded();
    });

    test("dashboard main content renders for demo", async ({ page, asDemo }) => {
        await page.goto("/dashboard");
        await expect(page.locator("main, [role='main']").first()).toBeVisible({ timeout: 15_000 });
    });
});
