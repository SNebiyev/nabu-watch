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

function authCtx() {
    return request.newContext({
        baseURL: API,
        extraHTTPHeaders: {
            Authorization: `Bearer ${sharedToken}`,
            "Content-Type": "application/json",
        },
    });
}

type Notif = {
    id: string;
    type: string;
    title: string;
    message: string;
    relatedEntityId: string;
    createdAt: string;
};

async function latestNotification(
    ctx: Awaited<ReturnType<typeof authCtx>>,
    type: string,
): Promise<Notif | undefined> {
    const res = await ctx.get("/api/v1/notifications?page=0&size=20");
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    const items: Notif[] = body.content ?? body.items ?? body;
    return items.find((n) => n.type === type);
}

test.describe("Phase 5c — owner notifications for abuse lifecycle", () => {
    test.skip(!WEBSITE_ID, "E2E_WEBSITE_ID not set");

    test("uphold fires ABUSE_REPORT notification to the website owner", async () => {
        // Anonymous submission
        const anon = await request.newContext({ baseURL: API });
        const submit = await anon.post("/api/v1/abuse/reports", {
            data: {
                websiteId: WEBSITE_ID,
                reporterEmail: "e2e-5c@example.com",
                message: "Playwright 5c notification test — sufficiently long to clear validation.",
            },
            headers: { "Content-Type": "application/json" },
        });
        expect(submit.status()).toBe(202);
        const reportId = (await submit.json()).id;
        await anon.dispose();

        const admin = await authCtx();
        const baseline = await latestNotification(admin, "ABUSE_REPORT");
        const baselineId = baseline?.id;

        const upheld = await admin.post(`/api/v1/admin/abuse/reports/${reportId}/uphold`);
        expect(upheld.status()).toBe(200);

        // Notification is persisted synchronously inside the @Transactional
        // service method, so GET /notifications should see it immediately.
        const after = await latestNotification(admin, "ABUSE_REPORT");
        expect(after).toBeDefined();
        expect(after!.id).not.toBe(baselineId);
        expect(after!.title).toMatch(/paused/i);
        expect(after!.message).toContain("48 hours");
        expect(after!.relatedEntityId).toBe(WEBSITE_ID);

        // Reactivate so later tests find the site ACTIVE
        const proven = await admin.post(
            `/api/v1/admin/abuse/reports/${reportId}/ownership-proven`,
        );
        expect(proven.status()).toBe(200);

        const reactivated = await latestNotification(admin, "ABUSE_REPORT");
        expect(reactivated!.title).toMatch(/reactivated/i);

        await admin.dispose();
    });

    test("dismiss does NOT fire an owner notification", async () => {
        const anon = await request.newContext({ baseURL: API });
        const submit = await anon.post("/api/v1/abuse/reports", {
            data: {
                websiteId: WEBSITE_ID,
                reporterEmail: "e2e-5c-dismiss@example.com",
                message: "Playwright 5c dismiss-does-not-notify test — long enough to validate.",
            },
            headers: { "Content-Type": "application/json" },
        });
        const reportId = (await submit.json()).id;
        await anon.dispose();

        const admin = await authCtx();
        const baseline = await latestNotification(admin, "ABUSE_REPORT");
        const baselineId = baseline?.id;
        const baselineCreatedAt = baseline?.createdAt;

        const dismissed = await admin.post(
            `/api/v1/admin/abuse/reports/${reportId}/dismiss`,
        );
        expect(dismissed.status()).toBe(200);

        const after = await latestNotification(admin, "ABUSE_REPORT");
        // Either no prior notification at all, or still pointing at the same one
        if (baseline) {
            expect(after?.id).toBe(baselineId);
            expect(after?.createdAt).toBe(baselineCreatedAt);
        } else {
            expect(after).toBeUndefined();
        }
        await admin.dispose();
    });
});
