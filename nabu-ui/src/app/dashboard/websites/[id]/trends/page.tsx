"use client";

import {useEffect, useState} from "react";
import {useParams, useRouter} from "next/navigation";
import {ArrowLeft, Loader2, TrendingUp} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import type {ComplianceTrendBucket} from "@/lib/types";
import {ComplianceTrendChart} from "@/components/dashboard/ComplianceTrendChart";

type Period = "DAILY" | "WEEKLY" | "MONTHLY";

const PERIOD_OPTIONS: { value: Period; label: string }[] = [
    {value: "DAILY", label: "Daily"},
    {value: "WEEKLY", label: "Weekly"},
    {value: "MONTHLY", label: "Monthly"},
];

const MONTHS_OPTIONS = [3, 6, 12, 24];

const SUB_TYPE_OPTIONS: { value: string; label: string }[] = [
    {value: "", label: "All monitors"},
    {value: "SSL", label: "SSL Certificate"},
    {value: "PRIVACY_POLICY", label: "Privacy Policy"},
    {value: "TERMS_OF_SERVICE", label: "Terms of Service"},
    {value: "IMPRESSUM", label: "Impressum"},
    {value: "COOKIE_POLICY", label: "Cookie Policy"},
    {value: "GDPR_REQUEST", label: "GDPR / Data Request"},
    {value: "COOKIE_BANNER", label: "Cookie Banner"},
];

export default function TrendsPage() {
    const params = useParams<{ id: string }>();
    const router = useRouter();
    const websiteId = params.id;

    const [data, setData] = useState<ComplianceTrendBucket[]>([]);
    const [loading, setLoading] = useState(true);
    const [period, setPeriod] = useState<Period>("MONTHLY");
    const [months, setMonths] = useState(6);
    const [subType, setSubType] = useState("");
    const [websiteUrl, setWebsiteUrl] = useState<string>("");

    // Fetch website URL for display
    useEffect(() => {
        apiClient(`/api/v1/websites/${websiteId}`)
            .then((res) => (res.ok ? res.json() : null))
            .then((w) => {
                if (w?.url) setWebsiteUrl(w.url.replace(/^https?:\/\//, ""));
            })
            .catch(() => {
            });
    }, [websiteId]);

    // Fetch trend data
    useEffect(() => {
        setLoading(true);
        const qs = new URLSearchParams({
            period,
            months: String(months),
        });
        if (subType) qs.set("subType", subType);

        apiClient(`/api/v1/websites/${websiteId}/compliance-trend?${qs}`)
            .then((res) => (res.ok ? res.json() : []))
            .then((buckets: ComplianceTrendBucket[]) => setData(buckets))
            .catch(() => setData([]))
            .finally(() => setLoading(false));
    }, [websiteId, period, months, subType]);

    return (
        <div className="space-y-6">
            {/* Header */}
            <div className="flex items-center gap-3">
                <button
                    type="button"
                    onClick={() => router.back()}
                    className="flex h-8 w-8 items-center justify-center rounded-lg border border-border bg-card text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                >
                    <ArrowLeft className="h-4 w-4"/>
                </button>
                <div className="min-w-0 flex-1">
                    <h1 className="text-lg font-semibold flex items-center gap-2">
                        <TrendingUp className="h-5 w-5 text-sky-500"/>
                        Compliance Trend
                    </h1>
                    {websiteUrl && (
                        <p className="truncate text-sm text-muted-foreground">{websiteUrl}</p>
                    )}
                </div>
            </div>

            {/* Filters */}
            <div className="flex flex-wrap items-center gap-3">
                {/* Period */}
                <div className="inline-flex overflow-hidden rounded-lg border border-border shadow-sm">
                    {PERIOD_OPTIONS.map((opt) => (
                        <button
                            key={opt.value}
                            type="button"
                            onClick={() => setPeriod(opt.value)}
                            className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                                period === opt.value
                                    ? "bg-primary text-primary-foreground"
                                    : "bg-card text-muted-foreground hover:bg-secondary hover:text-foreground"
                            }`}
                        >
                            {opt.label}
                        </button>
                    ))}
                </div>

                {/* Months */}
                <select
                    value={months}
                    onChange={(e) => setMonths(Number(e.target.value))}
                    className="rounded-lg border border-border bg-card px-3 py-1.5 text-xs text-foreground shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
                >
                    {MONTHS_OPTIONS.map((m) => (
                        <option key={m} value={m}>
                            Last {m} months
                        </option>
                    ))}
                </select>

                {/* SubType filter */}
                <select
                    value={subType}
                    onChange={(e) => setSubType(e.target.value)}
                    className="rounded-lg border border-border bg-card px-3 py-1.5 text-xs text-foreground shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
                >
                    {SUB_TYPE_OPTIONS.map((opt) => (
                        <option key={opt.value} value={opt.value}>
                            {opt.label}
                        </option>
                    ))}
                </select>
            </div>

            {/* Chart */}
            <div className="rounded-2xl border border-border bg-card p-4 md:p-6">
                {loading ? (
                    <div className="flex h-64 items-center justify-center">
                        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground"/>
                    </div>
                ) : data.length === 0 ? (
                    <div className="flex h-64 flex-col items-center justify-center gap-2 text-muted-foreground">
                        <TrendingUp className="h-8 w-8 opacity-40"/>
                        <p className="text-sm">No scan data for this period</p>
                    </div>
                ) : (
                    <ComplianceTrendChart data={data}/>
                )}
            </div>

            {/* Summary stats */}
            {!loading && data.length > 0 && (
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    <StatCard
                        label="Avg. Pass Rate"
                        value={`${(data.reduce((s, d) => s + d.passRate, 0) / data.length).toFixed(1)}%`}
                    />
                    <StatCard
                        label="Total Scans"
                        value={String(data.reduce((s, d) => s + d.totalScans, 0))}
                    />
                    <StatCard
                        label="Total Passing"
                        value={String(data.reduce((s, d) => s + d.passingCount, 0))}
                        color="text-green-500"
                    />
                    <StatCard
                        label="Total Failing"
                        value={String(data.reduce((s, d) => s + d.failingCount, 0))}
                        color="text-red-500"
                    />
                </div>
            )}
        </div>
    );
}

function StatCard({label, value, color}: { label: string; value: string; color?: string }) {
    return (
        <div className="rounded-xl border border-border bg-card p-3 md:p-4">
            <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                {label}
            </p>
            <p className={`mt-1 text-xl font-semibold ${color ?? "text-foreground"}`}>{value}</p>
        </div>
    );
}
