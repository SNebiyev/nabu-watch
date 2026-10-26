"use client";

import {useEffect, useRef, useState} from "react";
import {ShieldAlert, X} from "lucide-react";

interface TotpChallengeModalProps {
    open: boolean;
    onConfirm: (code: string) => void;
    onCancel: () => void;
    error?: string;
}

/**
 * Glassmorphism modal that prompts an Admin for their 6-digit TOTP code
 * before performing a force-scan bypass.
 */
export function TotpChallengeModal({
                                       open,
                                       onConfirm,
                                       onCancel,
                                       error,
                                   }: TotpChallengeModalProps) {
    const [code, setCode] = useState("");
    const inputRef = useRef<HTMLInputElement>(null);

    // Reset code and focus when opening
    useEffect(() => {
        if (open) {
            setCode("");
            setTimeout(() => inputRef.current?.focus(), 50);
        }
    }, [open]);

    if (!open) return null;

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();
        if (code.length === 6) onConfirm(code);
    };

    return (
        <div
            className="fixed inset-0 z-[200] flex items-center justify-center p-4"
            role="dialog"
            aria-modal="true"
            aria-labelledby="totp-modal-title"
        >
            {/* Backdrop */}
            <div
                className="absolute inset-0 bg-black/60 backdrop-blur-sm"
                onClick={onCancel}
            />

            {/* Panel */}
            <div
                className="relative z-10 w-full max-w-sm rounded-2xl border border-amber-500/30 bg-slate-900/90 p-6 shadow-2xl backdrop-blur-md">
                {/* Close button */}
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
                    <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-amber-500/15">
                        <ShieldAlert className="h-5 w-5 text-amber-400"/>
                    </div>
                    <div>
                        <p
                            id="totp-modal-title"
                            className="text-sm font-semibold text-foreground"
                        >
                            Admin Override Required
                        </p>
                        <p className="text-xs text-muted-foreground">
                            Enter your 6-digit Authenticator code
                        </p>
                    </div>
                </div>

                <form onSubmit={handleSubmit} className="space-y-4">
                    <input
                        ref={inputRef}
                        type="text"
                        inputMode="numeric"
                        pattern="\d{6}"
                        maxLength={6}
                        value={code}
                        onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                        placeholder="000000"
                        className="w-full rounded-xl border border-border bg-slate-800 px-4 py-3 text-center text-xl font-mono tracking-[0.4em] text-foreground placeholder:text-slate-600 focus:border-amber-500/50 focus:outline-none focus:ring-1 focus:ring-amber-500/30"
                    />

                    {error && (
                        <p className="text-center text-xs text-red-400">{error}</p>
                    )}

                    <div className="flex gap-2">
                        <button
                            type="button"
                            onClick={onCancel}
                            className="flex-1 rounded-xl border border-border bg-slate-800 px-4 py-2.5 text-sm text-muted-foreground hover:bg-slate-700 hover:text-foreground transition-colors"
                        >
                            Cancel
                        </button>
                        <button
                            type="submit"
                            disabled={code.length !== 6}
                            className="flex-1 rounded-xl bg-amber-500 px-4 py-2.5 text-sm font-semibold text-slate-900 hover:bg-amber-400 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                        >
                            Confirm
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
