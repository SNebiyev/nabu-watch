import { Page, expect } from "@playwright/test";

export class WebsitesPage {
    constructor(private page: Page) {}

    async goto(): Promise<void> {
        await this.page.goto("/dashboard");
    }

    async expectLoaded(): Promise<void> {
        await expect(this.page).toHaveURL(/\/dashboard/);
        await expect(this.page.locator("main, [role='main']").first()).toBeVisible();
    }

    async addWebsite(url: string): Promise<void> {
        await this.page.getByRole("button", { name: /add (a )?website|new website/i }).first().click();
        await this.page.getByLabel(/url|address/i).first().fill(url);
        await this.page.getByRole("button", { name: /add|create|save/i }).last().click();
    }

    row(urlFragment: string) {
        return this.page.getByRole("row", { name: new RegExp(urlFragment, "i") });
    }
}
