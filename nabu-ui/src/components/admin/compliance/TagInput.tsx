"use client";

import {KeyboardEvent, useRef, useState} from "react";
import {X} from "lucide-react";
import {cn} from "@/lib/utils";

interface TagInputProps {
    tags: string[];
    onChange: (tags: string[]) => void;
    placeholder?: string;
    disabled?: boolean;
    className?: string;
    /** Accent colour for the chips. Defaults to blue (primary). */
    variant?: "primary" | "amber" | "violet";
}

const CHIP_COLORS = {
    primary: "bg-blue-500/15 text-blue-400 border-blue-500/25 hover:border-blue-500/50",
    amber: "bg-amber-500/15 text-amber-400 border-amber-500/25 hover:border-amber-500/50",
    violet: "bg-violet-500/15 text-violet-400 border-violet-500/25 hover:border-violet-500/50",
};

const X_COLORS = {
    primary: "hover:bg-blue-500/25",
    amber: "hover:bg-amber-500/25",
    violet: "hover:bg-violet-500/25",
};

export function TagInput({
                             tags,
                             onChange,
                             placeholder = "Type and press Enter…",
                             disabled,
                             className,
                             variant = "primary",
                         }: TagInputProps) {
    const [input, setInput] = useState("");
    const inputRef = useRef<HTMLInputElement>(null);

    function addTag(raw: string) {
        const value = raw.trim();
        if (!value || tags.includes(value)) {
            setInput("");
            return;
        }
        onChange([...tags, value]);
        setInput("");
    }

    function removeTag(index: number) {
        onChange(tags.filter((_, i) => i !== index));
    }

    function handleKeyDown(e: KeyboardEvent<HTMLInputElement>) {
        if (e.key === "Enter" || e.key === "Tab" || e.key === ",") {
            e.preventDefault();
            addTag(input);
        } else if (e.key === "Backspace" && input === "" && tags.length > 0) {
            removeTag(tags.length - 1);
        }
    }

    return (
        <div
            className={cn(
                "flex min-h-[2.75rem] w-full flex-wrap items-center gap-1.5 rounded-lg",
                "border border-white/5 bg-secondary/20 px-3 py-2",
                "transition-colors focus-within:border-primary/30 focus-within:ring-1 focus-within:ring-primary/10",
                disabled && "cursor-not-allowed opacity-60",
                className
            )}
            onClick={() => inputRef.current?.focus()}
        >
            {tags.map((tag, i) => (
                <span
                    key={`${tag}-${i}`}
                    className={cn(
                        "inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-xs font-medium transition-colors",
                        CHIP_COLORS[variant]
                    )}
                >
          {tag}
                    {!disabled && (
                        <button
                            type="button"
                            onClick={(e) => {
                                e.stopPropagation();
                                removeTag(i);
                            }}
                            className={cn("rounded p-0.5 transition-colors", X_COLORS[variant])}
                            aria-label={`Remove ${tag}`}
                        >
                            <X className="h-2.5 w-2.5"/>
                        </button>
                    )}
        </span>
            ))}
            <input
                ref={inputRef}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                onBlur={() => input.trim() && addTag(input)}
                disabled={disabled}
                placeholder={tags.length === 0 ? placeholder : ""}
                className={cn(
                    "min-w-[8rem] flex-1 bg-transparent text-sm text-[--foreground] outline-none",
                    "placeholder:text-[--muted-foreground] disabled:cursor-not-allowed"
                )}
            />
        </div>
    );
}
