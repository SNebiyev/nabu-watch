import * as React from "react";
import {cn} from "@/lib/utils";

interface ToggleProps {
    checked: boolean;
    onChange: (checked: boolean) => void;
    disabled?: boolean;
    size?: "sm" | "default";
    className?: string;
    "aria-label"?: string;
}

const Toggle = React.forwardRef<HTMLButtonElement, ToggleProps>(
    ({checked, onChange, disabled, size = "default", className, ...rest}, ref) => {
        const isSmall = size === "sm";
        return (
            <button
                ref={ref}
                type="button"
                role="switch"
                aria-checked={checked}
                onClick={() => !disabled && onChange(!checked)}
                disabled={disabled}
                className={cn(
                    "relative inline-flex shrink-0 cursor-pointer items-center rounded-full transition-colors",
                    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[--ring] focus-visible:ring-offset-2",
                    "disabled:cursor-not-allowed disabled:opacity-50",
                    isSmall ? "h-4 w-7" : "h-5 w-9",
                    checked
                        ? "bg-green-500"
                        : "bg-slate-600 ring-1 ring-slate-500/60",
                    className
                )}
                {...rest}
            >
        <span
            className={cn(
                "pointer-events-none inline-block rounded-full bg-white shadow-sm transition-all duration-200",
                isSmall ? "h-3 w-3" : "h-3.5 w-3.5",
                checked
                    ? isSmall ? "translate-x-3.5" : "translate-x-[1.2rem]"
                    : "translate-x-[0.15rem]"
            )}
        />
            </button>
        );
    }
);
Toggle.displayName = "Toggle";

export {Toggle};
