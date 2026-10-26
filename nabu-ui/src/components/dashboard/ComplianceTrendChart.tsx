"use client";

import {Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis,} from "recharts";
import type {ComplianceTrendBucket} from "@/lib/types";

interface Props {
    data: ComplianceTrendBucket[];
}

export function ComplianceTrendChart({data}: Props) {
    return (
        <ResponsiveContainer width="100%" height={320}>
            <AreaChart data={data} margin={{top: 8, right: 8, left: -16, bottom: 0}}>
                <defs>
                    <linearGradient id="gradPass" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor="#22c55e" stopOpacity={0.3}/>
                        <stop offset="100%" stopColor="#22c55e" stopOpacity={0}/>
                    </linearGradient>
                    <linearGradient id="gradFail" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor="#ef4444" stopOpacity={0.2}/>
                        <stop offset="100%" stopColor="#ef4444" stopOpacity={0}/>
                    </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border)"/>
                <XAxis
                    dataKey="period"
                    tick={{fontSize: 11, fill: "var(--muted-foreground)"}}
                    tickLine={false}
                    axisLine={{stroke: "var(--border)"}}
                />
                <YAxis
                    domain={[0, 100]}
                    tick={{fontSize: 11, fill: "var(--muted-foreground)"}}
                    tickLine={false}
                    axisLine={{stroke: "var(--border)"}}
                    tickFormatter={(v: number) => `${v}%`}
                />
                <Tooltip content={<TrendTooltip/>}/>
                <Area
                    type="monotone"
                    dataKey="passRate"
                    name="Pass Rate"
                    stroke="#22c55e"
                    strokeWidth={2}
                    fill="url(#gradPass)"
                    dot={{r: 3, fill: "#22c55e", strokeWidth: 0}}
                    activeDot={{r: 5, fill: "#22c55e", strokeWidth: 2, stroke: "#fff"}}
                />
            </AreaChart>
        </ResponsiveContainer>
    );
}

function TrendTooltip({active, payload, label}: any) {
    if (!active || !payload?.length) return null;
    const d = payload[0].payload as ComplianceTrendBucket;
    return (
        <div className="rounded-lg border border-border bg-card px-3 py-2 text-xs shadow-lg">
            <p className="font-medium text-foreground">{label}</p>
            <div className="mt-1.5 space-y-0.5 text-muted-foreground">
                <p>
                    Pass rate:{" "}
                    <span className="font-semibold text-green-500">{d.passRate}%</span>
                </p>
                <p>
                    Passing: <span className="text-green-500">{d.passingCount}</span> / Failing:{" "}
                    <span className="text-red-500">{d.failingCount}</span>
                </p>
                <p>Total scans: {d.totalScans}</p>
            </div>
        </div>
    );
}
