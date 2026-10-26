import type {AdminStats} from "@/lib/types";
import {ScoreRing} from "@/components/ui/ScoreRing";

interface AdminKpiCardsProps {
    stats: AdminStats;
    loading?: boolean;
}

export function AdminKpiCards({ stats, loading }: AdminKpiCardsProps) {
    if (loading) {
        return (
            <div
                className="glass-card flex h-10 animate-pulse items-center gap-0 divide-x divide-border overflow-x-auto px-4">
                {Array.from({length: 6}).map((_, i) => (
                    <div key={i} className={`flex items-center gap-2 ${i === 0 ? "pr-4" : "px-4"}`}>
                        <div className="h-3 w-16 rounded bg-secondary"/>
                    </div>
                ))}
            </div>
        );
    }

    const successRate = Math.max(0, 100 - stats.systemFailureRate);
    const workersOnline = stats.activeScansLastHour > 0;
    const isHealthy = successRate >= 95;

    const ringStroke =
        isHealthy
            ? "rgb(34,197,94)"
            : successRate >= 80
                ? "rgb(245,158,11)"
                : "rgb(239,68,68)";

    const scoreColor =
        isHealthy
            ? "text-green-500"
            : successRate >= 80
                ? "text-amber-500"
                : "text-red-500";

    return (
        <div className="glass-card flex h-10 items-center gap-0 divide-x divide-border overflow-x-auto px-4">
            {/* SVG ring + success rate */}
            <div className="flex items-center gap-2 pr-4">
                <ScoreRing value={successRate} stroke={ringStroke} sizeClass="h-6 w-6"/>
                <span className={`text-sm font-bold tabular-nums ${scoreColor}`}>
                    {successRate.toFixed(1)}%
                </span>
                <span className="text-xs text-muted-foreground">success</span>
            </div>

            {/* Users */}
            <div className="flex items-center gap-1.5 px-4">
                <span className="text-xs text-muted-foreground">Users</span>
                <span className="text-sm font-semibold tabular-nums text-foreground">
                    {stats.totalUsers.toLocaleString()}
                </span>
            </div>

            {/* Sites */}
            <div className="flex items-center gap-1.5 px-4">
                <span className="text-xs text-muted-foreground">Sites</span>
                <span className="text-sm font-semibold tabular-nums text-foreground">
                    {stats.totalWebsites.toLocaleString()}
                </span>
            </div>

            {/* Monitors */}
            <div className="flex items-center gap-1.5 px-4">
                <span className="text-xs text-muted-foreground">Monitors</span>
                <span className="text-sm font-semibold tabular-nums text-foreground">
                    {stats.totalMonitors.toLocaleString()}
                </span>
            </div>

            {/* Scans / hr */}
            <div className="flex items-center gap-1.5 px-4">
                <span className="text-xs text-muted-foreground">Scans/hr</span>
                <span className="text-sm font-semibold tabular-nums text-foreground">
                    {stats.activeScansLastHour.toLocaleString()}
                </span>
            </div>

            {/* Worker status */}
            <div className="flex items-center gap-1.5 pl-4">
                <span className="text-xs text-muted-foreground">Worker</span>
                <span
                    className={`text-sm font-semibold tabular-nums ${
                        workersOnline ? "text-green-500" : "text-foreground"
                    }`}
                >
                    {workersOnline ? "Online" : "Idle"}
                </span>
            </div>
        </div>
    );
}
