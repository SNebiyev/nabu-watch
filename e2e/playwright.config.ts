import { defineConfig, devices } from "@playwright/test";
import * as path from "path";
import { ENV } from "./helpers/env";

const AUTH_DIR = path.resolve(__dirname, ".auth");

export default defineConfig({
    testDir: "./specs",
    timeout: 60_000,
    expect: { timeout: 10_000 },
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 2 : 0,
    workers: process.env.CI ? 2 : undefined,
    reporter: [
        ["list"],
        ["html", { outputFolder: "playwright-report", open: "never" }],
        ["junit", { outputFile: "test-results/junit.xml" }],
    ],
    globalSetup: require.resolve("./global-setup.ts"),
    globalTeardown: require.resolve("./global-teardown.ts"),
    outputDir: "test-results",

    use: {
        baseURL: ENV.UI_URL,
        actionTimeout: 15_000,
        navigationTimeout: 30_000,
        screenshot: "only-on-failure",
        trace: "retain-on-failure",
        video: "retain-on-failure",
        viewport: { width: 1440, height: 900 },
        ignoreHTTPSErrors: true,
    },

    projects: (() => {
        // API-only specs — no UI interaction. Running them across 5 browser projects
        // hammers rate-limited auth endpoints; restrict them to a single "api" project.
        const apiOnlySpecs = [
            /specs\/auth\/login\.spec\.ts/,
            /specs\/auth\/signup\.spec\.ts/,
            /specs\/auth\/password-reset\.spec\.ts/,
            /specs\/health\.spec\.ts/,
            /specs\/onboarding\//,
            /specs\/websites\/websites-crud\.spec\.ts/,
            /specs\/monitors\//,
            /specs\/organizations\//,
            /specs\/admin\/admin-api\.spec\.ts/,
            /specs\/scans\//,
        ];
        return [
            {
                name: "api",
                testMatch: apiOnlySpecs,
                use: { ...devices["Desktop Chrome"] },
            },
            {
                name: "chromium",
                testIgnore: apiOnlySpecs,
                use: { ...devices["Desktop Chrome"] },
            },
            {
                name: "firefox",
                testIgnore: apiOnlySpecs,
                use: { ...devices["Desktop Firefox"] },
            },
            {
                name: "webkit",
                testIgnore: apiOnlySpecs,
                use: { ...devices["Desktop Safari"] },
            },
            {
                name: "mobile-chrome",
                testIgnore: apiOnlySpecs,
                use: { ...devices["Pixel 7"] },
            },
            {
                name: "mobile-safari",
                testIgnore: apiOnlySpecs,
                use: { ...devices["iPhone 14"] },
            },
            {
                name: "admin-chromium",
                testMatch: /specs\/admin\/admin-ui\.spec\.ts/,
                use: { ...devices["Desktop Chrome"], storageState: path.join(AUTH_DIR, "admin.json") },
            },
        ];
    })(),
});
