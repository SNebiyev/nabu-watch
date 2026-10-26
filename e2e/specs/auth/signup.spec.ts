import { test, expect } from "../../fixtures/auth";
import { activateUser, deleteUserCascade } from "../../helpers/db";
import { clearAuthRateLimits } from "../../helpers/redis";

test.beforeEach(async () => {
    await clearAuthRateLimits();
});

const randomEmail = () => `e2e-signup-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@nabu.test`;

test.describe("@regression auth — signup", () => {
    test("POST /auth/register creates a PENDING user which can activate+login", async ({ api }) => {
        const email = randomEmail();
        const register = await api.post("/api/v1/auth/register", {
            data: { email, password: "StrongPass!23", name: "Signup Test" },
        });
        expect([200, 201]).toContain(register.status());

        // Without activation, login should fail.
        const blocked = await api.post("/api/v1/auth/login", {
            data: { email, password: "StrongPass!23" },
        });
        expect(blocked.ok()).toBeFalsy();

        // SQL activate bypasses the email verification.
        const id = await activateUser(email);
        expect(id).toBeTruthy();

        // The failed pre-activation login above increments the per-email failed counter
        // AND parallel workers may have consumed the per-IP login budget — reset both.
        await clearAuthRateLimits();

        const ok = await api.post("/api/v1/auth/login", {
            data: { email, password: "StrongPass!23" },
        });
        expect(ok.status(), `post-activation login: ${await ok.text()}`).toBe(200);

        await deleteUserCascade(email);
    });

    test("duplicate email registration does not leak existence (anti-enumeration)", async ({ api }) => {
        const email = randomEmail();
        const first = await api.post("/api/v1/auth/register", {
            data: { email, password: "StrongPass!23", name: "Dup" },
        });
        const second = await api.post("/api/v1/auth/register", {
            data: { email, password: "StrongPass!23", name: "Dup" },
        });
        // Security: the API must return an identical response for new vs existing emails.
        expect(second.status()).toBe(first.status());
        expect(await second.text()).toBe(await first.text());
        await deleteUserCascade(email);
    });

    test("weak password is rejected", async ({ api }) => {
        const res = await api.post("/api/v1/auth/register", {
            data: { email: randomEmail(), password: "weak", name: "Weak" },
        });
        expect(res.status()).toBeGreaterThanOrEqual(400);
        expect(res.status()).toBeLessThan(500);
    });
});
