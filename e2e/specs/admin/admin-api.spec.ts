import { test, expect } from "../../fixtures/auth";
import { authHeaders } from "../../helpers/api";

test.describe("@regression @admin admin API", () => {
    test("admin can list users", async ({ api, tokens }) => {
        const res = await api.get("/api/v1/admin/users?page=0&size=10", {
            headers: authHeaders(tokens.admin),
        });
        expect(res.ok()).toBeTruthy();
        const body = await res.json();
        expect(Array.isArray(body.content)).toBeTruthy();
        expect(body.totalElements).toBeGreaterThan(0);
    });

    test("admin sees stats", async ({ api, tokens }) => {
        const res = await api.get("/api/v1/admin/stats", { headers: authHeaders(tokens.admin) });
        expect(res.ok()).toBeTruthy();
        const body = await res.json();
        expect(body).toHaveProperty("totalUsers");
    });

    test("non-admin is rejected (403)", async ({ api, tokens }) => {
        const res = await api.get("/api/v1/admin/users", { headers: authHeaders(tokens.demo) });
        expect(res.status()).toBe(403);
    });

    test("admin can list organizations", async ({ api, tokens }) => {
        const res = await api.get("/api/v1/admin/organizations?page=0&size=10", {
            headers: authHeaders(tokens.admin),
        });
        expect(res.ok()).toBeTruthy();
    });
});
