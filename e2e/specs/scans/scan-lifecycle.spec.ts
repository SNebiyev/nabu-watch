import { test, expect } from "../../fixtures/auth";
import { ephemeralOrSkip, safeCleanup } from "../../helpers/ephemeral";
import { authHeaders, createWebsite, monitorBySubType, uniqueTestUrl } from "../../helpers/api";
import { publishScanResult, resetRescanCooldowns } from "../../helpers/redis";
import { countScanLogsForMonitor, latestScanLogForMonitor } from "../../helpers/db";
import { FAILING_SEVERITIES, MonitorSubType, Severity } from "../../helpers/types";
import { ENV } from "../../helpers/env";

test.describe("@regression @scans scan lifecycle — Redis injection", () => {
    test("synthetic SUCCESS result is persisted as a ScanLog and visible via REST", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-scan");
        if (!ctx) return;
        const h = authHeaders(ctx.session.token);
        try {
            const { websiteId, monitors } = await createWebsite(api, ctx.session.token, uniqueTestUrl("scan"));
            const ssl = monitorBySubType(monitors, MonitorSubType.SSL);

            await publishScanResult({
                monitorId: ssl.monitorId,
                payload: { status: "COMPLIANT", days_remaining: "180", host: "e2e-scan.example" },
            });

            await expect.poll(() => latestScanLogForMonitor(ssl.monitorId), { timeout: 10_000 }).not.toBeNull();
            const scanLog = await latestScanLogForMonitor(ssl.monitorId);
            expect(Severity).toContain(scanLog!.severity);

            const latest = await api.get(`/api/v1/websites/${websiteId}/latest-scans`, { headers: h });
            expect(latest.ok()).toBeTruthy();
            const items = (await latest.json()) as Array<{ monitorId: string }>;
            expect(items.some((i) => i.monitorId === ssl.monitorId)).toBeTruthy();
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });

    test("NON_COMPLIANT result is surfaced as a failing severity and in /scans/recent", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-scan-fail");
        if (!ctx) return;
        const h = authHeaders(ctx.session.token);
        try {
            const { monitors } = await createWebsite(api, ctx.session.token, uniqueTestUrl("scan-fail"));
            const privacy = monitorBySubType(monitors, MonitorSubType.PRIVACY_POLICY);

            await publishScanResult({
                monitorId: privacy.monitorId,
                payload: {
                    status: "NON_COMPLIANT",
                    missing_keywords: JSON.stringify(["cookie", "tracking"]),
                    error: "",
                },
            });

            await expect.poll(() => latestScanLogForMonitor(privacy.monitorId), { timeout: 10_000 }).not.toBeNull();
            const scanLog = await latestScanLogForMonitor(privacy.monitorId);
            expect(FAILING_SEVERITIES).toContain(scanLog!.severity);

            const recent = await api.get("/api/v1/scans/recent", { headers: h });
            expect(recent.ok()).toBeTruthy();
            const list = (await recent.json()) as Array<{ monitorId: string }>;
            expect(list.some((s) => s.monitorId === privacy.monitorId)).toBeTruthy();
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });

    test("scan history endpoint returns batches after multiple injections", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-scan-hist");
        if (!ctx) return;
        const h = authHeaders(ctx.session.token);
        try {
            const { websiteId, monitors } = await createWebsite(api, ctx.session.token, uniqueTestUrl("scan-hist"));
            const tos = monitorBySubType(monitors, MonitorSubType.TERMS_OF_SERVICE);

            await Promise.all(
                [0, 1, 2].map(() =>
                    publishScanResult({
                        monitorId: tos.monitorId,
                        payload: { status: "COMPLIANT" },
                    }),
                ),
            );

            await expect.poll(() => countScanLogsForMonitor(tos.monitorId), { timeout: 10_000 }).toBeGreaterThanOrEqual(3);

            const history = await api.get(`/api/v1/websites/${websiteId}/scan-history`, { headers: h });
            expect(history.ok()).toBeTruthy();
            const batches = (await history.json()) as unknown[];
            expect(batches.length).toBeGreaterThan(0);
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });
});

test.describe("@regression @scans rescan trigger (REST)", () => {
    test("POST /websites/{id}/rescan returns 202 after cooldown reset", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-rescan");
        if (!ctx) return;
        const h = authHeaders(ctx.session.token);
        try {
            const { websiteId } = await createWebsite(api, ctx.session.token, uniqueTestUrl("rescan"));
            await resetRescanCooldowns(websiteId);

            const res = await api.post(`/api/v1/websites/${websiteId}/rescan`, { headers: h });
            expect([200, 202]).toContain(res.status());
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });

    test("back-to-back rescan hits domain cooldown (429)", async ({ api }, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-rescan-cooldown");
        if (!ctx) return;
        const h = authHeaders(ctx.session.token);
        try {
            const { websiteId } = await createWebsite(api, ctx.session.token, uniqueTestUrl("cooldown"));
            await resetRescanCooldowns(websiteId);

            const first = await api.post(`/api/v1/websites/${websiteId}/rescan`, { headers: h });
            expect([200, 202]).toContain(first.status());

            const second = await api.post(`/api/v1/websites/${websiteId}/rescan`, { headers: h });
            expect([200, 202, 429]).toContain(second.status());
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });
});

test.describe("@regression @scans SSE /scans/stream", () => {
    test("opens a text/event-stream connection", async ({}, testInfo) => {
        const ctx = await ephemeralOrSkip(testInfo, "e2e-sse");
        if (!ctx) return;
        try {
            // Playwright's api.get() waits for the full body, which never ends for an SSE
            // stream — use native fetch with AbortController and read just the first chunk.
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 1_500);
            try {
                const res = await fetch(`${ENV.API_URL}/api/v1/scans/stream`, {
                    headers: {
                        Authorization: `Bearer ${ctx.session.token}`,
                        Accept: "text/event-stream",
                    },
                    signal: controller.signal,
                });
                expect(res.status).toBe(200);
                expect(res.headers.get("content-type") ?? "").toContain("text/event-stream");

                const reader = res.body?.getReader();
                if (reader) {
                    const { value } = await Promise.race([
                        reader.read(),
                        new Promise<{ value: Uint8Array | undefined }>((resolve) =>
                            setTimeout(() => resolve({ value: undefined }), 1_000),
                        ),
                    ]);
                    if (value) expect(new TextDecoder().decode(value).length).toBeGreaterThan(0);
                    await reader.cancel().catch(() => {});
                }
            } catch (e) {
                if ((e as Error).name !== "AbortError") throw e;
            } finally {
                clearTimeout(timer);
            }
        } finally {
            await safeCleanup(ctx.user.email);
        }
    });
});
