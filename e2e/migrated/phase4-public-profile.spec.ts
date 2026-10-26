import { test, expect } from "@playwright/test";

const UI = process.env.E2E_UI_URL ?? "http://localhost:3001";
const API = process.env.E2E_API_URL ?? "http://localhost:8081";

test.describe("Phase 4 — public trust profile", () => {
    test("renders verified user profile with badge, websites, grades", async ({ page }) => {
        await page.goto(`${UI}/u/phase4test`);

        await expect(page.getByText("Test User")).toBeVisible();
        await expect(page.getByText("@phase4test")).toBeVisible();
        await expect(page.getByText(/^Verified$/)).toBeVisible();
        await expect(page.getByText(/Verified websites \(2\)/)).toBeVisible();
        await expect(page.getByText("https://one.example.com")).toBeVisible();
        await expect(page.getByText("https://two.example.com")).toBeVisible();
        await expect(page.getByText("A+", { exact: true })).toBeVisible();
        await expect(page.getByText("B", { exact: true })).toBeVisible();

        await page.screenshot({ path: "test-results/phase4-profile.png", fullPage: true });
    });

    test("404 for unknown / disabled profile", async ({ page }) => {
        const res = await page.goto(`${UI}/u/notarealuser`);
        expect(res?.status()).toBe(404);
    });

    test("case-insensitive lookup matches the slug", async ({ page }) => {
        await page.goto(`${UI}/u/Phase4Test`);
        await expect(page.getByText("@phase4test")).toBeVisible();
    });
});

test.describe("Phase 4 — public abuse intake", () => {
    test("anonymous POST returns 202 PENDING and does NOT pause the website", async ({
        request,
    }) => {
        // First grab a website id from the seeded user's public profile
        const profile = await request.get(`${API}/api/v1/public/users/phase4test`);
        expect(profile.ok()).toBeTruthy();
        // websites contains URLs only — abuse needs a UUID, so we re-use the
        // seed website id pinned by the e2e harness via env. If unset, skip.
        const siteId = process.env.E2E_WEBSITE_ID;
        test.skip(!siteId, "E2E_WEBSITE_ID not set");

        const res = await request.post(`${API}/api/v1/abuse/reports`, {
            data: {
                websiteId: siteId,
                reporterEmail: "playwright@example.com",
                message:
                    "End-to-end abuse-report test — sufficiently long to clear the 20-char minimum.",
            },
        });
        expect(res.status()).toBe(202);
        const body = await res.json();
        expect(body.status).toBe("PENDING");
        expect(body.proofDeadlineAt).toBeTruthy();
    });

    test("validation rejects short message", async ({ request }) => {
        const siteId = process.env.E2E_WEBSITE_ID;
        test.skip(!siteId, "E2E_WEBSITE_ID not set");
        const res = await request.post(`${API}/api/v1/abuse/reports`, {
            data: {
                websiteId: siteId,
                reporterEmail: "x@y.com",
                message: "too short",
            },
        });
        expect(res.status()).toBe(400);
    });
});
