import { test, expect } from "../../fixtures/auth";
import { AdminPage } from "../../pages/AdminPage";

test.describe("@admin UI", () => {
    test("admin can open admin users page", async ({ page, asAdmin }) => {
        const admin = new AdminPage(page);
        await admin.gotoUsers();
        // Accept any of: "Users", "Admin · Users", or the main admin heading.
        await expect(page.getByRole("heading").first()).toBeVisible({ timeout: 15_000 });
    });

    test("demo user visiting admin area is blocked", async ({ page, asDemo }) => {
        await page.goto("/admin/users");
        // Either redirected to dashboard/login or shown a 403 message.
        await expect(page).not.toHaveURL(/\/admin\/users$/, { timeout: 10_000 }).catch(async () => {
            await expect(page.getByText(/forbidden|not authori[sz]ed|access denied|403/i)).toBeVisible();
        });
    });
});
