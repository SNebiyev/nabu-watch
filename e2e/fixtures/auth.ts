import { test as base, expect, APIRequestContext } from "@playwright/test";
import * as fs from "fs";
import * as path from "path";
import { newApi, setCookieAuth } from "../helpers/api";

type Tokens = { admin: string; demo: string; secondary: string };

function loadTokens(): Tokens {
    const file = path.resolve(__dirname, "..", ".auth", "tokens.json");
    return JSON.parse(fs.readFileSync(file, "utf8")) as Tokens;
}

type Fixtures = {
    api: APIRequestContext;
    tokens: Tokens;
    asDemo: void;
    asAdmin: void;
    asSecondary: void;
    asAnonymous: void;
};

export const test = base.extend<Fixtures>({
    api: async ({}, use) => {
        const api = await newApi();
        await use(api);
        await api.dispose();
    },

    tokens: async ({}, use) => {
        await use(loadTokens());
    },

    asDemo: async ({ page, tokens }, use) => {
        await setCookieAuth(page, tokens.demo);
        await use();
    },

    asAdmin: async ({ page, tokens }, use) => {
        await setCookieAuth(page, tokens.admin);
        await use();
    },

    asSecondary: async ({ page, tokens }, use) => {
        await setCookieAuth(page, tokens.secondary);
        await use();
    },

    asAnonymous: async ({ page }, use) => {
        await page.context().clearCookies();
        await use();
    },
});

export { expect };
