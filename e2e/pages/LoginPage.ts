import { Page, expect } from "@playwright/test";

export class LoginPage {
    constructor(private page: Page) {}

    async goto(): Promise<void> {
        await this.page.goto("/login");
    }

    async login(email: string, password: string): Promise<void> {
        await this.page.getByLabel(/email/i).fill(email);
        await this.page.getByLabel(/password/i).fill(password);
        await this.page.getByRole("button", { name: /log in|sign in/i }).click();
    }

    async expectError(): Promise<void> {
        await expect(this.page.getByRole("alert")).toBeVisible();
    }
}
