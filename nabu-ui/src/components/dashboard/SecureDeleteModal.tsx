"use client";

import {useEffect, useRef, useState} from "react";
import {AlertTriangle, Loader2, Mail, X} from "lucide-react";

interface SecureDeleteModalProps {
    open: boolean;
    /** Human-readable label shown in the confirmation text, e.g. "example.com". */
    siteName: string;
    /**
     * true  → caller does not own this site; a 6-digit OTP must be entered
     *          before the delete button becomes enabled.
     * false → self-owned site; simple confirmation only.
     */
    requiresOtp: boolean;
    /**
     * Called when the user confirms deletion.
     * Receives the 6-digit OTP string when requiresOtp=true, undefined otherwise.
     */
    onConfirm: (otpCode?: string) => void;
    onCancel: () => void;
    /** Disables the confirm button and shows a spinner (during the DELETE request). */
    loading?: boolean;
    /** Inline error message shown below the OTP input (e.g. "Code invalid"). */
    error?: string;
    /**
     * Called once when the modal opens in OTP mode so the parent can fire
     * POST /{id}/delete-otp to dispatch the code to the admin's email.
     */
    onRequestOtp?: () => void;
}

/**
 * Reusable secure-delete confirmation modal.
 *
 * Scenario A (requiresOtp=false — self-owned site):
 *   Plain "Are you sure?" with Confirm / Cancel.
 *
 * Scenario B (requiresOtp=true — admin deleting another user's site):
 *   Same modal, but shows a 6-digit OTP input.  The Confirm button is
 *   disabled until exactly 6 digits are entered.  onRequestOtp() is called
 *   on mount so the backend dispatches the code to the admin's email.
 */
export function SecureDeleteModal({
                                      open,
                                      siteName,
                                      requiresOtp,
                                      onConfirm,
                                      onCancel,
                                      loading = false,
                                      error,
                                      onRequestOtp,
                                  }: SecureDeleteModalProps) {
    const [otp, setOtp] = useState("");
    const [otpSent, setOtpSent] = useState(false);
    const inputRef = useRef<HTMLInputElement>(null);

    // When the modal opens in OTP mode, request the code and focus the input.
    useEffect(() => {
        if (!open) {
            setOtp("");
            setOtpSent(false);
            return;
        }
        if (requiresOtp) {
            onRequestOtp?.();
            setOtpSent(true);
            setTimeout(() => inputRef.current?.focus(), 80);
        }
    }, [open, requiresOtp]); // eslint-disable-line react-hooks/exhaustive-deps

    if (!open) return null;

    const canConfirm = !loading && (!requiresOtp || otp.length === 6);

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();
        if (!canConfirm) return;
        onConfirm(requiresOtp ? otp : undefined);
    };

    return (
        <div
            className="fixed inset-0 z-[200] flex items-center justify-center p-4"
            role="dialog"
            aria-modal="true"
            aria-labelledby="secure-delete-title"
        >
            {/* Backdrop */}
            <div
                className="absolute inset-0 bg-black/60 backdrop-blur-sm"
                onClick={onCancel}
            />

            {/* Panel */}
            <div
                className="relative z-10 w-full max-w-sm rounded-2xl border border-red-500/30 bg-slate-900/90 p-6 shadow-2xl backdrop-blur-md">
                {/* Close */}
                <button
                    type="button"
                    onClick={onCancel}
                    className="absolute right-4 top-4 text-slate-500 hover:text-slate-300 transition-colors"
                    aria-label="Close"
                >
                    <X className="h-4 w-4"/>
                </button>

                {/* Icon + title */}
                <div className="mb-4 flex items-center gap-3">
                    <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-red-500/15">
                        <AlertTriangle className="h-5 w-5 text-red-400"/>
                    </div>
                    <div>
                        <p
                            id="secure-delete-title"
                            className="text-sm font-semibold text-foreground"
                        >
                            Delete Site
                        </p>
                        <p className="text-xs text-muted-foreground">
                            This action cannot be undone
                        </p>
                    </div>
                </div>

                <form onSubmit={handleSubmit} className="space-y-4">
                    {/* Confirmation text */}
                    <p className="text-sm text-slate-300">
                        You are about to permanently delete{" "}
                        <span className="font-semibold text-foreground">
              {siteName}
            </span>{" "}
                        and all its monitors.
                        {requiresOtp && (
                            <>
                                {" "}
                                Because this site belongs to another user, an extra
                                verification step is required.
                            </>
                        )}
                    </p>

                    {/* OTP section — Scenario B only */}
                    {requiresOtp && (
                        <div className="space-y-2">
                            {/* Sent indicator */}
                            {otpSent && (
                                <div
                                    className="flex items-center gap-2 rounded-lg border border-slate-700/50 bg-slate-800/60 px-3 py-2">
                                    <Mail className="h-3.5 w-3.5 shrink-0 text-slate-400"/>
                                    <p className="text-xs text-slate-400">
                                        A 6-digit code has been sent to your registered email
                                        address. It expires in&nbsp;10&nbsp;minutes.
                                    </p>
                                </div>
                            )}

                            <input
                                ref={inputRef}
                                type="text"
                                inputMode="numeric"
                                pattern="\d{6}"
                                maxLength={6}
                                value={otp}
                                onChange={(e) =>
                                    setOtp(e.target.value.replace(/\D/g, "").slice(0, 6))
                                }
                                placeholder="000000"
                                className="w-full rounded-xl border border-border bg-slate-800 px-4 py-3 text-center text-xl font-mono tracking-[0.4em] text-foreground placeholder:text-slate-600 focus:border-red-500/50 focus:outline-none focus:ring-1 focus:ring-red-500/30"
                            />
                        </div>
                    )}

                    {/* Error */}
                    {error && (
                        <p className="text-center text-xs text-red-400">{error}</p>
                    )}

                    {/* Actions */}
                    <div className="flex gap-2">
                        <button
                            type="button"
                            onClick={onCancel}
                            disabled={loading}
                            className="flex-1 rounded-xl border border-border bg-slate-800 px-4 py-2.5 text-sm text-muted-foreground hover:bg-slate-700 hover:text-foreground disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                        >
                            Cancel
                        </button>
                        <button
                            type="submit"
                            disabled={!canConfirm}
                            className="flex-1 rounded-xl bg-red-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-red-500 disabled:opacity-40 disabled:cursor-not-allowed transition-colors inline-flex items-center justify-center gap-1.5"
                        >
                            {loading ? (
                                <>
                                    <Loader2 className="h-3.5 w-3.5 animate-spin"/>
                                    Deleting…
                                </>
                            ) : (
                                "Delete"
                            )}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
