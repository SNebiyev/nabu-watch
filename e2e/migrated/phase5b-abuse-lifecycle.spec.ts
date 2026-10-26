import { test, expect, request } from "@playwright/test";

const API = process.env.E2E_API_URL ?? "http://localhost:8081";
const EMAIL = process.env.E2E_ADMIN_EMAIL ?? "admin@example.com";
const PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? "e2etest!pw";
const WEBSITE_ID = process.env.E2E_WEBSITE_ID;

let sharedToken = "";

test.beforeAll(async () => {
    const ctx = await request.newContext();
    const res = await ctx.post(`${API}/api/v1/auth/login`, {
        data: { email: EMAIL, password: PASSWORD },
    });
    if (!res.ok()) throw new Error(`login failed: ${res.status()}`);
    sharedToken = (await res.json()).token;
    await ctx.dispose();
});

function adminCtx() {
    return request.newContext({
        baseURL: API,
        extraHTTPHeaders: {
            Authorization: `Bearer ${sharedToken}`,
            "Content-Type": "application/json",
        },
    });
}

test.describe("Phase 5b — admin abuse lifecycle", () => {
    test.skip(!WEBSITE_ID, "E2E_WEBSITE_ID not set");

    test("submit → PENDING → admin uphold → site PAUSED → ownership-proven → site ACTIVE", async () => {
        // 1. Anonymous submit a report
        const anon = await request.newContext({ baseURL: API });
        const submit = await anon.post("/api/v1/abuse/reports", {
            data: {
                websiteId: WEBSITE_ID,
                reporterEmail: "e2e-5b@example.com",
                message: "Playwright 5b admin-lifecycle test — long enough to pass validation.",
            },
            headers: { "Content-Type": "application/json" },
        });
        expect(submit.status()).toBe(202);
        const submitted = await submit.json();
        const reportId = submitted.id;
        await anon.dispose();

        const admin = await adminCtx();

        // 2. List PENDING — the new row should be there
        const list = await admin.get("/api/v1/admin/abuse/reports?status=PENDING");
        expect(list.ok()).toBeTruthy();
        const page = await list.json();
        expect(page.items.map((i: { id: string }) => i.id)).toContain(reportId);

        // 3. Uphold — transitions to UPHELD + pauses site
        const upheld = await admin.post(`/api/v1/admin/abuse/reports/${reportId}/uphold`);
        expect(upheld.status()).toBe(200);
        const upheldBody = await upheld.json();
        expect(upheldBody.status).toBe("UPHELD");
        expect(upheldBody.websiteStatus).toBe("PAUSED");

        // 4. Second uphold on the now-UPHELD report → 409
        const conflict = await admin.post(`/api/v1/admin/abuse/reports/${reportId}/uphold`);
        expect(conflict.status()).toBe(409);

        // 5. Ownership proven → reactivates site
        const proven = await admin.post(
            `/api/v1/admin/abuse/reports/${reportId}/ownership-proven`,
        );
        expect(proven.status()).toBe(200);
        const provenBody = await proven.json();
        expect(provenBody.status).toBe("OWNERSHIP_PROVEN");
        expect(provenBody.websiteStatus).toBe("ACTIVE");

        await admin.dispose();
    });

    test("dismiss takes PENDING → DISMISSED without touching the site", async () => {
        const anon = await request.newContext({ baseURL: API });
        const submit = await anon.post("/api/v1/abuse/reports", {
            data: {
                websiteId: WEBSITE_ID,
                reporterEmail: "e2e-5b-dismiss@example.com",
                message: "Playwright 5b dismiss test — long enough to pass validation rules.",
            },
            headers: { "Content-Type": "application/json" },
        });
        const reportId = (await submit.json()).id;
        await anon.dispose();

        const admin = await adminCtx();
        const dismissed = await admin.post(`/api/v1/admin/abuse/reports/${reportId}/dismiss`);
        expect(dismissed.status()).toBe(200);
        const body = await dismissed.json();
        expect(body.status).toBe("DISMISSED");
        expect(body.websiteStatus).toBe("ACTIVE");
        expect(body.resolvedAt).toBeTruthy();
        await admin.dispose();
    });

    test("non-admin user gets 403 on admin abuse endpoint", async () => {
        const anon = await request.newContext({ baseURL: API });
        const res = await anon.get("/api/v1/admin/abuse/reports");
        expect([401, 403]).toContain(res.status());
        await anon.dispose();
    });
});
