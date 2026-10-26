"use client";

import {useEffect, useState} from "react";
import {createPortal} from "react-dom";
import {X} from "lucide-react";
import {cn} from "@/lib/utils";

interface ConfirmModalProps {
    title: string;
    description: string;
    confirmLabel?: string;
    cancelLabel?: string;
    /** "primary" = blue action, "danger" = red destructive action */
    variant?: "primary" | "danger";
    onConfirm: () => void;
    onCancel: () => void;
}

export function ConfirmModal({
                                 title,
                                 description,
                                 confirmLabel = "Confirm",
                                 cancelLabel = "Cancel",
                                 variant = "primary",
                                 onConfirm,
                                 onCancel,
                             }: ConfirmModalProps) {
    // Portal mount guard — avoids SSR mismatch
    const [mounted, setMounted] = useState(false);
    useEffect(() => {
        setMounted(true);
    }, []);
    if (!mounted) return null;

    const modal = (
        <div
            className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/90 p-4 backdrop-blur-xl"
            onClick={(e) => e.target === e.currentTarget && onCancel()}
        >
            <div
                className="w-full max-w-sm overflow-hidden rounded-2xl border border-white/5 bg-[#0B1120] shadow-2xl shadow-black/80">
                {/* Header */}
                <div className="flex items-start justify-between px-5 pb-3 pt-5">
                    <h2 className="text-sm font-semibold text-foreground">{title}</h2>
                    <button
                        onClick={onCancel}
                        className="rounded-lg p-1 text-muted-foreground transition-colors hover:bg-white/5"
                    >
                        <X className="h-4 w-4"/>
                    </button>
                </div>

                {/* Body */}
                <p className="px-5 pb-5 text-sm leading-relaxed text-muted-foreground">
                    {description}
                </p>

                {/* Footer */}
                <div className="flex items-center justify-end gap-2 border-t border-white/5 px-5 py-3">
                    <button
                        onClick={onCancel}
                        className="rounded-xl px-4 py-2 text-xs font-medium text-muted-foreground transition-colors hover:bg-white/5"
                    >
                        {cancelLabel}
                    </button>
                    <button
                        onClick={onConfirm}
                        className={cn(
                            "rounded-xl px-4 py-2 text-xs font-semibold transition-colors",
                            variant === "danger"
                                ? "bg-red-600 text-white hover:bg-red-700"
                                : "bg-primary text-primary-foreground hover:bg-primary/90"
                        )}
                    >
                        {confirmLabel}
                    </button>
                </div>
            </div>
        </div>
    );

    return createPortal(modal, document.body);
}
