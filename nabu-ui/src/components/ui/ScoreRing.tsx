import type {ReactNode} from "react";

interface ScoreRingProps {
    /** 0–100 percentage value */
    value: number;
    /** CSS color string for the active arc, e.g. "rgb(34,197,94)" */
    stroke: string;
    /** Tailwind size class applied to both the wrapper and the SVG, e.g. "h-9 w-9" */
    sizeClass: string;
    /** Optional content rendered centered over the ring (e.g. percentage label) */
    children?: ReactNode;
}

export function ScoreRing({value, stroke, sizeClass, children}: ScoreRingProps) {
    return (
        <div className={`relative ${sizeClass} shrink-0`}>
            <svg viewBox="0 0 36 36" className={`${sizeClass} -rotate-90`}>
                <circle
                    cx="18" cy="18" r="15.9"
                    fill="none" stroke="currentColor" strokeOpacity="0.08" strokeWidth="3"
                />
                <circle
                    cx="18" cy="18" r="15.9"
                    fill="none" stroke={stroke} strokeWidth="3"
                    strokeDasharray={`${value} ${100 - value}`}
                    strokeLinecap="round"
                    style={{transition: "stroke-dasharray 0.8s cubic-bezier(0.4,0,0.2,1)"}}
                />
            </svg>
            {children && (
                <span className="absolute inset-0 flex items-center justify-center">
                    {children}
                </span>
            )}
        </div>
    );
}
