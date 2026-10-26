"use client";

import * as React from "react";
import {createPortal} from "react-dom";
import {X} from "lucide-react";
import {cn} from "@/lib/utils";

interface SheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  children: React.ReactNode;
    /** Override the inner panel's width/size classes (e.g. "lg:w-[75vw]"). */
    panelClassName?: string;
}

export function Sheet({open, onOpenChange, children, panelClassName}: SheetProps) {
  React.useEffect(() => {
    if (!open) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onOpenChange(false);
    };
    document.addEventListener("keydown", handleKey);
    return () => document.removeEventListener("keydown", handleKey);
  }, [open, onOpenChange]);

  if (!open) return null;

    const content = (
        <div className="fixed inset-0 z-[9999] flex justify-end">
      {/* Backdrop — slightly more aggressive dim + blur on desktop */}
      <div
        className="absolute inset-0 bg-black/55 backdrop-blur-[2px] md:bg-black/65 md:backdrop-blur-sm"
        onClick={() => onOpenChange(false)}
      />

      {/*
       * Panel sizing:
       *   mobile  → 92 vw  (near full-width sheet)
       *   md      → 56 vw  (tablet: generous but leaves context visible)
       *   lg      → 48 vw  (desktop: true half-screen panel)
       *
       * Left edge gets a subtle glow border on desktop to visually anchor
       * the panel against the dimmed dashboard behind it.
       */}
      <div
        className={cn(
          "relative z-10 flex h-full w-[92vw] flex-col overflow-hidden",
          "bg-card shadow-[-4px_0_40px_rgba(0,0,0,0.12)]",
          "ring-1 ring-border",
            // Default desktop width — can be overridden via panelClassName
          "md:w-[60vw] lg:w-[52vw]",
          // Subtle left-edge accent on large screens
          "md:border-l md:border-border",
          // Slide-in animation
            "animate-sheet-in",
            panelClassName
        )}
      >
        {children}
      </div>
    </div>
  );

    return createPortal(content, document.body);
}

export function SheetHeader({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex shrink-0 items-center justify-between gap-3",
        "border-b border-border",
        // Mobile padding
        "px-5 py-4",
        // Desktop: more breathing room
        "md:px-7 md:py-5",
        className
      )}
    >
      {children}
    </div>
  );
}

export function SheetTitle({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <h2
      className={cn(
        "font-semibold text-[--foreground]",
        "text-sm md:text-base",
        className
      )}
    >
      {children}
    </h2>
  );
}

export function SheetClose({onClickAction}: { onClickAction: () => void }) {
  return (
    <button
        onClick={onClickAction}
      className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
      aria-label="Close"
    >
      <X className="h-4 w-4 md:h-5 md:w-5" />
    </button>
  );
}

export function SheetContent({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex-1 overflow-y-auto",
        "px-5 py-4 md:px-7 md:py-5",
        className
      )}
    >
      {children}
    </div>
  );
}
