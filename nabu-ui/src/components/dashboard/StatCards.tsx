import {ArrowDownCircle, ArrowUpCircle, Globe, Lock, ShieldAlert, ShieldCheck,} from "lucide-react";
import type {DashboardStats} from "@/lib/types";
import {ScoreRing} from "@/components/ui/ScoreRing";

interface StatCardsProps {
    stats: DashboardStats;
}

export function StatCards({stats}: StatCardsProps) {
    const score =
        stats.totalSites === 0
            ? 100
            : Math.round((stats.passingSites / stats.totalSites) * 100);

    const scoreColor =
        score >= 80 ? "text-green-500"
            : score >= 50 ? "text-amber-500"
                : "text-red-500";

    const ringStroke =
        score >= 80 ? "rgb(34,197,94)"
            : score >= 50 ? "rgb(245,158,11)"
                : "rgb(239,68,68)";

    const scoreGradient =
        score >= 80 ? "from-green-500/10 to-green-500/0"
            : score >= 50 ? "from-amber-500/10 to-amber-500/0"
                : "from-red-500/10 to-red-500/0";

    return (
        <>
            {/* ── Mobile: two side-by-side badges ──────────────────── */}
            <div className="grid grid-cols-2 gap-2.5 sm:hidden">
                {/* Score badge — centered */}
                <div
                    className={`glass-card-elevated relative overflow-hidden px-3 py-2.5 flex items-center justify-center`}>
                    <div className={`absolute inset-0 bg-gradient-to-br ${scoreGradient}`}/>
                    <div className="relative flex items-center gap-2.5">
                        <ScoreRing value={score} stroke={ringStroke} sizeClass="h-9 w-9">
                            <span className={`text-[10px] font-bold tabular-nums ${scoreColor}`}>{score}%</span>
                        </ScoreRing>
                        <div>
                            <p className="text-[10px] font-medium text-muted-foreground">Compliance</p>
                            <p className={`text-base font-bold tabular-nums leading-tight ${scoreColor}`}>Score</p>
                        </div>
                    </div>
                </div>

                {/* Stats badge — 2-column grid */}
                <div className="glass-card px-3 py-2 flex items-center">
                    <div className="grid grid-cols-2 gap-x-4 gap-y-0.5 w-full">
                        <span className="text-[11px] text-muted-foreground">Sites: <span
                            className="font-semibold tabular-nums text-foreground">{stats.totalSites}</span></span>
                        <span className="text-[11px] text-green-500">Pass: <span
                            className="font-semibold tabular-nums">{stats.passingSites}</span></span>
                        <span
                            className={`text-[11px] ${stats.failingSites > 0 ? "text-red-500" : "text-muted-foreground"}`}>Issues: <span
                            className="font-semibold tabular-nums">{stats.failingSites}</span></span>
                        <span
                            className={`text-[11px] ${stats.sslIssues > 0 ? "text-red-500" : "text-green-500"}`}>SSL: <span
                            className="font-semibold tabular-nums">{stats.sslIssues > 0 ? `${stats.sslIssues} fail` : "OK"}</span></span>
                        <span className="text-[11px] text-green-500">Recovered: <span
                            className="font-semibold tabular-nums">{stats.recoveredCount}</span></span>
                        <span
                            className={`text-[11px] ${stats.degradedCount > 0 ? "text-red-500" : "text-muted-foreground"}`}>Degraded: <span
                            className="font-semibold tabular-nums">{stats.degradedCount}</span></span>
                    </div>
                </div>
            </div>

            {/* ── Desktop: original grid ─────────────────────────────── */}
            <div className="hidden sm:grid sm:grid-cols-4 lg:grid-cols-7 gap-3">
                {/* Compliance Score — larger card */}
                <div className={`glass-card-elevated relative overflow-hidden p-4`}>
                    <div className={`absolute inset-0 bg-gradient-to-br ${scoreGradient}`}/>
                    <div className="relative flex items-center gap-3">
                        <ScoreRing value={score} stroke={ringStroke} sizeClass="h-11 w-11">
                            <span className={`text-xs font-bold tabular-nums ${scoreColor}`}>{score}%</span>
                        </ScoreRing>
                        <div>
                            <p className="text-[11px] font-medium text-muted-foreground">Compliance</p>
                            <p className={`text-lg font-bold tabular-nums ${scoreColor}`}>Score</p>
                        </div>
                    </div>
                </div>

                <StatCard icon={Globe} label="Total Sites" value={stats.totalSites}
                          iconColor="text-blue-500" iconBg="bg-blue-500/10"/>
                <StatCard icon={ShieldCheck} label="Passing" value={stats.passingSites}
                          iconColor="text-green-500" iconBg="bg-green-500/10"/>
                <StatCard icon={ShieldAlert} label="Issues" value={stats.failingSites}
                          iconColor={stats.failingSites > 0 ? "text-red-500" : "text-muted-foreground"}
                          iconBg={stats.failingSites > 0 ? "bg-red-500/10" : "bg-muted/50"}
                          valueColor={stats.failingSites > 0 ? "text-red-500" : undefined}/>
                <StatCard icon={ArrowUpCircle} label="Recovered" value={stats.recoveredCount}
                          iconColor="text-green-500" iconBg="bg-green-500/10"
                          valueColor={stats.recoveredCount > 0 ? "text-green-500" : undefined}/>
                <StatCard icon={ArrowDownCircle} label="Degraded" value={stats.degradedCount}
                          iconColor={stats.degradedCount > 0 ? "text-red-500" : "text-muted-foreground"}
                          iconBg={stats.degradedCount > 0 ? "bg-red-500/10" : "bg-muted/50"}
                          valueColor={stats.degradedCount > 0 ? "text-red-500" : undefined}/>
                <StatCard icon={Lock} label="SSL"
                          value={stats.sslIssues > 0 ? `${stats.sslIssues} fail` : "OK"}
                          iconColor={stats.sslIssues > 0 ? "text-red-500" : "text-green-500"}
                          iconBg={stats.sslIssues > 0 ? "bg-red-500/10" : "bg-green-500/10"}
                          valueColor={stats.sslIssues > 0 ? "text-red-500" : "text-green-500"}/>
            </div>
        </>
    );
}

// ── Reusable stat card ──────────────────────────────────────────────────────

interface StatCardProps {
    icon: typeof Globe;
    label: string;
    value: number | string;
    iconColor: string;
    iconBg: string;
    valueColor?: string;
}

function StatCard({icon: Icon, label, value, iconColor, iconBg, valueColor}: StatCardProps) {
    return (
        <div className="glass-card p-4 flex items-center gap-3 transition-all duration-200 hover:shadow-sm">
            <div className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${iconBg}`}>
                <Icon className={`h-[18px] w-[18px] ${iconColor}`}/>
            </div>
            <div className="min-w-0">
                <p className="text-[11px] font-medium text-muted-foreground">{label}</p>
                <p className={`text-lg font-bold tabular-nums ${valueColor ?? "text-foreground"}`}>{value}</p>
            </div>
        </div>
    );
}

