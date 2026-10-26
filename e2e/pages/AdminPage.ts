import { Page, expect } from "@playwright/test";

export class AdminPage {
    constructor(private page: Page) {}

    async gotoUsers(): Promise<void> {
        await this.page.goto("/admin/users");
    }

    async gotoStats(): Promise<void> {
        await this.page.goto("/admin/overview");
    }

    async expectAdminNav(): Promise<void> {
        await expect(this.page.getByRole("link", { name: /users|admin/i }).first()).toBeVisible();
    }
}
