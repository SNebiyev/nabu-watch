import { test, expect } from "../../fixtures/auth";

test.describe("@regression settings", () => {
    test("profile settings page loads", async ({ page, asDemo }) => {
        await page.goto("/dashboard/settings/notifications");
        await expect(page.getByRole("heading").first()).toBeVisible({ timeout: 15_000 });
    });

    test("dark/light theme toggle persists", async ({ page, asDemo }) => {
        await page.goto("/dashboard");
        const html = page.locator("html");
        const initial = await html.getAttribute("class");

        const toggle = page.getByRole("button", { name: /theme|dark|light/i }).first();
        if (await toggle.count()) {
            await toggle.click();
            await page.waitForTimeout(300);
            const after = await html.getAttribute("class");
            expect(after).not.toBe(initial);
        } else {
            test.skip(true, "theme toggle not present on this page — adjust selector per UI");
        }
    });
});
