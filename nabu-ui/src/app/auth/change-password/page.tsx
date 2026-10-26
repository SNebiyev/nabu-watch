"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Shield, Loader2, Eye, EyeOff, Check, X } from "lucide-react";
import { changePassword } from "@/lib/authService";
import { getTokenPayload } from "@/lib/authService";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

// ── Password strength rules ────────────────────────────────────────────────

interface Rule {
  label: string;
  test: (v: string) => boolean;
}

const RULES: Rule[] = [
  { label: "At least 8 characters", test: (v) => v.length >= 8 },
  { label: "Uppercase letter", test: (v) => /[A-Z]/.test(v) },
  { label: "Lowercase letter", test: (v) => /[a-z]/.test(v) },
  { label: "Number", test: (v) => /[0-9]/.test(v) },
  {
    label: "Special character (!@#$…)",
    test: (v) => /[^A-Za-z0-9]/.test(v),
  },
];

function strengthScore(password: string): number {
  return RULES.filter((r) => r.test(password)).length;
}

const STRENGTH_LABELS = ["", "Very weak", "Weak", "Fair", "Strong", "Very strong"];
const STRENGTH_COLORS = [
  "",
  "bg-red-500",
  "bg-orange-500",
  "bg-yellow-500",
  "bg-blue-500",
  "bg-emerald-500",
];

// ── Component ──────────────────────────────────────────────────────────────

export default function ChangePasswordPage() {
  const router = useRouter();

  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  const [showCurrent, setShowCurrent] = useState(false);
  const [showNew, setShowNew] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const score = strengthScore(newPassword);
  const allRulesMet = score === RULES.length;
  const passwordsMatch =
    confirmPassword.length > 0 && newPassword === confirmPassword;
  const canSubmit =
    currentPassword.length > 0 && allRulesMet && passwordsMatch && !loading;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setError(null);
    setLoading(true);
    try {
      const response = await changePassword(currentPassword, newPassword);
      // New token is stored by changePassword(); read role to pick redirect
      const payload = getTokenPayload();
      const role = payload?.role ?? response.role;
      router.push(role === "ADMIN" ? "/admin/overview" : "/dashboard");
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to change password"
      );
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-[--background] px-4">
      {/* Background glow */}
      <div className="pointer-events-none absolute left-1/2 top-1/2 h-[500px] w-[500px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-blue-900/10 blur-3xl" />

      <div className="relative w-full max-w-sm">
        {/* Logo */}
        <div className="mb-8 flex flex-col items-center gap-3 text-center">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-[--primary] shadow-lg shadow-blue-900/40">
            <Shield className="h-7 w-7 text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-[--foreground]">
              nabu<span className="text-[--primary]">.watch</span>
            </h1>
            <p className="mt-1 text-sm text-[--muted-foreground]">
              Set a new password to continue
            </p>
          </div>
        </div>

        {/* Card */}
        <div className="rounded-2xl border border-[--border] bg-[--card] p-6 shadow-xl">
          <h2 className="mb-1 text-base font-semibold text-[--foreground]">
            Change your password
          </h2>
          <p className="mb-5 text-xs text-[--muted-foreground]">
            Your account requires a password change before you can continue.
          </p>

          <form onSubmit={handleSubmit} className="space-y-4">
            {/* Current password */}
            <div className="space-y-1.5">
              <label
                htmlFor="current"
                className="text-xs font-medium text-[--muted-foreground]"
              >
                Current password
              </label>
              <div className="relative">
                <Input
                  id="current"
                  type={showCurrent ? "text" : "password"}
                  placeholder="••••••••"
                  autoComplete="current-password"
                  value={currentPassword}
                  onChange={(e) => setCurrentPassword(e.target.value)}
                  required
                  className="pr-10"
                />
                <button
                  type="button"
                  onClick={() => setShowCurrent((v) => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-[--muted-foreground] hover:text-[--foreground]"
                  tabIndex={-1}
                >
                  {showCurrent ? (
                    <EyeOff className="h-4 w-4" />
                  ) : (
                    <Eye className="h-4 w-4" />
                  )}
                </button>
              </div>
            </div>

            {/* New password */}
            <div className="space-y-1.5">
              <label
                htmlFor="new"
                className="text-xs font-medium text-[--muted-foreground]"
              >
                New password
              </label>
              <div className="relative">
                <Input
                  id="new"
                  type={showNew ? "text" : "password"}
                  placeholder="••••••••"
                  autoComplete="new-password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  required
                  className="pr-10"
                />
                <button
                  type="button"
                  onClick={() => setShowNew((v) => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-[--muted-foreground] hover:text-[--foreground]"
                  tabIndex={-1}
                >
                  {showNew ? (
                    <EyeOff className="h-4 w-4" />
                  ) : (
                    <Eye className="h-4 w-4" />
                  )}
                </button>
              </div>

              {/* Strength bar */}
              {newPassword.length > 0 && (
                <div className="space-y-1.5 pt-0.5">
                  <div className="flex gap-1">
                    {RULES.map((_, i) => (
                      <div
                        key={i}
                        className={`h-1 flex-1 rounded-full transition-colors ${
                          i < score
                            ? STRENGTH_COLORS[score]
                            : "bg-[--border]"
                        }`}
                      />
                    ))}
                  </div>
                  <p className="text-xs text-[--muted-foreground]">
                    {STRENGTH_LABELS[score]}
                  </p>
                </div>
              )}

              {/* Rules checklist */}
              {newPassword.length > 0 && (
                <ul className="space-y-1 pt-1">
                  {RULES.map((rule) => {
                    const met = rule.test(newPassword);
                    return (
                      <li
                        key={rule.label}
                        className="flex items-center gap-1.5 text-xs"
                      >
                        {met ? (
                          <Check className="h-3 w-3 shrink-0 text-emerald-400" />
                        ) : (
                          <X className="h-3 w-3 shrink-0 text-[--muted-foreground]" />
                        )}
                        <span
                          className={
                            met
                              ? "text-emerald-400"
                              : "text-[--muted-foreground]"
                          }
                        >
                          {rule.label}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>

            {/* Confirm password */}
            <div className="space-y-1.5">
              <label
                htmlFor="confirm"
                className="text-xs font-medium text-[--muted-foreground]"
              >
                Confirm new password
              </label>
              <div className="relative">
                <Input
                  id="confirm"
                  type={showConfirm ? "text" : "password"}
                  placeholder="••••••••"
                  autoComplete="new-password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  required
                  className={`pr-10 ${
                    confirmPassword.length > 0 && !passwordsMatch
                      ? "border-red-900/50 focus-visible:ring-red-900/50"
                      : ""
                  }`}
                />
                <button
                  type="button"
                  onClick={() => setShowConfirm((v) => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-[--muted-foreground] hover:text-[--foreground]"
                  tabIndex={-1}
                >
                  {showConfirm ? (
                    <EyeOff className="h-4 w-4" />
                  ) : (
                    <Eye className="h-4 w-4" />
                  )}
                </button>
              </div>
              {confirmPassword.length > 0 && !passwordsMatch && (
                <p className="text-xs text-red-400">Passwords do not match</p>
              )}
            </div>

            {error && (
              <div className="rounded-lg border border-red-900/50 bg-red-950/50 px-3 py-2.5 text-sm text-red-400">
                {error}
              </div>
            )}

            <Button
              type="submit"
              disabled={!canSubmit}
              className="mt-2 w-full"
              size="lg"
            >
              {loading ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  Updating…
                </>
              ) : (
                "Update password"
              )}
            </Button>
          </form>
        </div>

      </div>
    </div>
  );
}
