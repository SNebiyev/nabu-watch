import { Page, expect } from "@playwright/test";

export class DashboardPage {
    constructor(private page: Page) {}

    async goto(): Promise<void> {
        await this.page.goto("/dashboard");
    }

    async expectLoaded(): Promise<void> {
        await expect(this.page).toHaveURL(/\/dashboard/);
    }

    sidebarLink(name: RegExp | string) {
        return this.page.getByRole("link", { name });
    }
}
