import {ChevronLeft, ChevronRight, User, Zap} from "lucide-react";
import type {ScanBatch} from "@/lib/types";

export interface HistoryNavigatorProps {
    history: ScanBatch[];
    historyIndex: number;
    onOlder: () => void;
    onNewer: () => void;
    onLatest: () => void;
    /** compact=true → smaller buttons (mobile), compact=false → default (desktop) */
    compact?: boolean;
}

const btnBase = "flex items-center rounded border bg-card text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-30 transition-colors border-border";
const latestBtnBase = "flex items-center rounded border border-sky-500/40 bg-sky-950/30 font-medium text-sky-400 hover:bg-sky-900/40 hover:text-sky-300 transition-colors";

export function HistoryNavigator({
                                     history,
                                     historyIndex,
                                     onOlder,
                                     onNewer,
                                     onLatest,
                                     compact = false,
                                 }: HistoryNavigatorProps) {
    const iconSize = compact ? "h-3.5 w-3.5" : "h-3 w-3";

    return (
        <div className="flex items-center gap-1.5">
            <div className="flex items-center gap-0.5">
                <button
                    type="button"
                    onClick={onOlder}
                    disabled={historyIndex >= history.length}
                    className={`${btnBase} ${compact ? "h-6 px-1.5 gap-0.5" : "h-5 px-1.5 gap-1"}`}
                    title="View older scan"
                >
                    <ChevronLeft className={iconSize}/>
                    {!compact && <span className="text-[10px]">Prev. State</span>}
                </button>
                <button
                    type="button"
                    onClick={onNewer}
                    disabled={historyIndex === 0}
                    className={`${btnBase} ${compact ? "h-6 px-1.5 gap-0.5" : "h-5 px-1.5 gap-1"}`}
                    title="View newer scan"
                >
                    {!compact && <span className="text-[10px]">Next State</span>}
                    <ChevronRight className={iconSize}/>
                </button>
                {historyIndex > 0 && (
                    <button
                        type="button"
                        onClick={onLatest}
                        className={`${latestBtnBase} ${compact ? "h-6" : "h-5"} px-1.5 text-[10px]`}
                        title="Go to latest state"
                    >
                        Latest State
                    </button>
                )}
            </div>

            <span className="text-[10px] text-muted-foreground">
        {historyIndex === 0 ? (
            <span className="font-medium text-foreground">Latest State</span>
        ) : (() => {
            const b = history[historyIndex - 1];
            return (
                <span className="flex items-center gap-1.5">
                    <span className="whitespace-nowrap">
                        {new Date(b.timestamp).toLocaleString(undefined, {
                            month: "short",
                            day: "numeric",
                            hour: "2-digit",
                            minute: "2-digit",
                        })}
                    </span>
                    <span
                        className={`rounded px-1 py-0.5 text-[9px] font-semibold ${
                            b.scanType === "FULL"
                                ? "bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300"
                                : "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400"
                        }`}
                    >
                        {b.scanType === "FULL" ? "Full" : "Partial"}
                    </span>
                    {b.triggerType && (
                        <span className="flex items-center gap-0.5 text-[9px] text-muted-foreground">
                            {b.triggerType === "MANUAL" ? (
                                <>
                                    <User className="h-2 w-2 shrink-0"/>
                                    <span className="truncate max-w-[80px]" title={b.triggeredByEmail ?? undefined}>
                                        {b.triggeredByEmail ?? "Manual"}
                                    </span>
                                </>
                            ) : (
                                <>
                                    <Zap className="h-2 w-2 shrink-0"/>
                                    Scheduled
                                </>
                            )}
                        </span>
                    )}
                </span>
            );
        })()}
      </span>
        </div>
    );
}
