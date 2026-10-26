"use client";

import {useEffect, useRef, useState} from "react";
import {ArrowDownAZ, ArrowUpAZ, ChevronDown, Filter, Globe, Search, X} from "lucide-react";
import type {MonitorSubType} from "@/lib/types";
import type {DropdownOption} from "@/components/ui/Dropdown";
import {Dropdown} from "@/components/ui/Dropdown";

// ── constants ────────────────────────────────────────────────────────────────

const STATUS_OPTIONS = [
    {value: "", label: "All statuses"},
    {value: "ISSUES", label: "Issues found"},
    {value: "FAILING", label: "Failing"},
    {value: "WARNING", label: "Warning"},
    {value: "PASSING", label: "Passing"},
    {value: "PENDING", label: "Pending"},
    {value: "PAUSED", label: "Paused"},
];

const MONITOR_SUBTYPES: { value: MonitorSubType; label: string }[] = [
    {value: "SSL", label: "SSL Certificate"},
    {value: "PRIVACY_POLICY", label: "Privacy Policy"},
    {value: "TERMS_OF_SERVICE", label: "Terms of Service"},
    {value: "IMPRESSUM", label: "Impressum"},
    {value: "COOKIE_POLICY", label: "Cookie Policy"},
    {value: "GDPR_REQUEST", label: "GDPR Request"},
    {value: "COOKIE_BANNER", label: "Cookie Consent Banner"},
    {value: "TRACKER_DETECTION", label: "Tracker Detection"},
];

const MONITOR_STATUS_OPTIONS = [
    {value: "", label: "Any status"},
    {value: "ISSUES", label: "Issues found"},
    {value: "FAILING", label: "Failing"},
    {value: "WARNING", label: "Warning"},
    {value: "PASSING", label: "Passing"},
    {value: "PENDING", label: "Pending"},
];

export type SortKey = "lastCheckedAt" | "nextScanAt" | "region" | "grade" | "status";
export type SortDirection = "asc" | "desc";

const SORT_OPTIONS: { value: SortKey; label: string }[] = [
    {value: "lastCheckedAt", label: "Last scan"},
    {value: "nextScanAt", label: "Next scan"},
    {value: "region", label: "Region"},
    {value: "grade", label: "Grade"},
    {value: "status", label: "Status"},
];

const SORT_DROPDOWN_OPTIONS: DropdownOption[] = SORT_OPTIONS.map(o => ({id: o.value, name: o.label}));

// ── types ────────────────────────────────────────────────────────────────────

export interface FilterState {
    search: string;
    status: string;
    monitorSubType: string;
    monitorStatus: string;
    languageOnly: boolean;
}

interface WebsiteFilterBarProps {
    filters: FilterState;
    onChange: (partial: Partial<FilterState>) => void;
    sortKey: SortKey;
    sortDirection: SortDirection;
    onSortChange: (key: SortKey, direction: SortDirection) => void;
}

// ── Option maps for Dropdown ──────────────────────────────────────────────────

const STATUS_DROPDOWN_OPTIONS: DropdownOption[] = STATUS_OPTIONS.map(o => ({id: o.value, name: o.label}));
const MONITOR_TYPE_DROPDOWN_OPTIONS: DropdownOption[] = [{id: "", name: "Any type"}, ...MONITOR_SUBTYPES.map(o => ({
    id: o.value,
    name: o.label
}))];
const MONITOR_STATUS_DROPDOWN_OPTIONS: DropdownOption[] = MONITOR_STATUS_OPTIONS.map(o => ({
    id: o.value,
    name: o.label
}));

// ── Monitor filter popover ────────────────────────────────────────────────────

function MonitorFilterPopover({
                                  subType,
                                  monitorStatus,
                                  onChange,
                              }: {
    subType: string;
    monitorStatus: string;
    onChange: (subType: string, monitorStatus: string) => void;
}) {
    const [open, setOpen] = useState(false);
    const ref = useRef<HTMLDivElement>(null);
    const active = subType !== "" || monitorStatus !== "";

    useEffect(() => {
        if (!open) return;
        const handler = (e: MouseEvent) => {
            if (ref.current?.contains(e.target as Node)) return;
            let target = e.target as HTMLElement | null;
            while (target) {
                if (target.dataset?.portal === "dropdown") return;
                target = target.parentElement;
            }
            setOpen(false);
        };
        document.addEventListener("mousedown", handler);
        return () => document.removeEventListener("mousedown", handler);
    }, [open]);

    return (
        <div ref={ref} className="relative w-full md:w-auto">
            <button
                type="button"
                onClick={() => setOpen((p) => !p)}
                className={`flex h-9 w-full md:w-[180px] items-center gap-1.5 rounded-xl border px-3 text-xs font-medium transition-all duration-200 ${
                    active
                        ? "border-violet-300 bg-violet-50 text-violet-700 hover:bg-violet-100 dark:border-violet-700/50 dark:bg-violet-950 dark:text-violet-300 dark:hover:bg-violet-900"
                        : "border-border bg-card text-muted-foreground hover:bg-secondary hover:text-foreground"
                }`}
            >
                <Filter className="h-3.5 w-3.5"/>
                Monitor filter
                {active && (
                    <span
                        className="ml-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-violet-500/20 text-[10px] font-semibold text-violet-700 dark:text-violet-300">
                        {(subType ? 1 : 0) + (monitorStatus ? 1 : 0)}
                    </span>
                )}
                <ChevronDown
                    className={`h-3.5 w-3.5 ml-auto transition-transform duration-200 ${open ? "rotate-180" : ""}`}/>
            </button>

            {open && (
                <div
                    className="absolute left-0 right-0 md:right-auto top-11 z-50 md:w-72 rounded-2xl border border-border bg-card p-4 shadow-xl">
                    <p className="mb-3 text-xs font-semibold text-foreground">Filter by monitor</p>
                    <div className="space-y-3">
                        <div>
                            <label className="mb-1.5 block text-[11px] text-muted-foreground">Monitor type</label>
                            <Dropdown
                                options={MONITOR_TYPE_DROPDOWN_OPTIONS}
                                value={subType}
                                onChange={(v) => onChange(String(v), monitorStatus)}
                                placeholder="Any type"
                            />
                        </div>
                        <div>
                            <label className="mb-1.5 block text-[11px] text-muted-foreground">Monitor status</label>
                            <Dropdown
                                options={MONITOR_STATUS_DROPDOWN_OPTIONS}
                                value={monitorStatus}
                                onChange={(v) => onChange(subType, String(v))}
                                placeholder="Any status"
                            />
                        </div>
                    </div>
                    {active && (
                        <button
                            type="button"
                            onClick={() => {
                                onChange("", "");
                                setOpen(false);
                            }}
                            className="mt-3 flex w-full items-center justify-center gap-1 rounded-xl border border-border py-1.5 text-xs text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors duration-200"
                        >
                            <X className="h-3 w-3"/>
                            Clear monitor filter
                        </button>
                    )}
                </div>
            )}
        </div>
    );
}

// ── Main component ─────────────────────────────────────────────────────────────

export function WebsiteFilterBar({filters, onChange, sortKey, sortDirection, onSortChange}: WebsiteFilterBarProps) {
    const [inputValue, setInputValue] = useState(filters.search);
    const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    useEffect(() => {
        setInputValue(filters.search);
    }, [filters.search]);

    function handleSearchInput(value: string) {
        setInputValue(value);
        if (debounceRef.current) clearTimeout(debounceRef.current);
        debounceRef.current = setTimeout(() => onChange({search: value}), 400);
    }

    useEffect(() => () => {
        if (debounceRef.current) clearTimeout(debounceRef.current);
    }, []);

    const hasAnyFilter =
        filters.search !== "" || filters.status !== "" ||
        filters.monitorSubType !== "" || filters.monitorStatus !== "" ||
        filters.languageOnly;

    return (
        <div className="glass-card px-3 py-2.5">
            <div className="flex flex-col md:flex-row md:items-center gap-2">
                {/* Search */}
                <div className="relative w-full md:flex-1">
                    <Search
                        className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground"/>
                    <input
                        type="text"
                        value={inputValue}
                        onChange={(e) => handleSearchInput(e.target.value)}
                        placeholder="Search websites..."
                        className="h-9 w-full rounded-xl border border-border bg-card pl-8 pr-3 text-sm text-foreground placeholder:text-muted-foreground transition-all duration-200 hover:bg-secondary focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary/30"
                    />
                    {inputValue && (
                        <button
                            type="button"
                            onClick={() => {
                                setInputValue("");
                                onChange({search: ""});
                            }}
                            className="absolute right-2.5 top-1/2 -translate-y-1/2 rounded-full p-0.5 hover:bg-secondary text-muted-foreground hover:text-foreground transition-colors"
                        >
                            <X className="h-3 w-3"/>
                        </button>
                    )}
                </div>

                <Dropdown
                    options={STATUS_DROPDOWN_OPTIONS}
                    value={filters.status}
                    onChange={(v) => onChange({status: String(v)})}
                    placeholder="All statuses"
                    className="w-full md:w-[150px] md:shrink-0"
                />

                <MonitorFilterPopover
                    subType={filters.monitorSubType}
                    monitorStatus={filters.monitorStatus}
                    onChange={(subType, monitorStatus) => onChange({monitorSubType: subType, monitorStatus})}
                />

                <div className="flex w-full md:w-auto gap-1.5 md:shrink-0">
                    <Dropdown
                        options={SORT_DROPDOWN_OPTIONS}
                        value={sortKey}
                        onChange={(v) => onSortChange(String(v) as SortKey, sortDirection)}
                        placeholder="Sort by"
                        className="flex-1 md:w-[140px]"
                    />
                    <button
                        type="button"
                        onClick={() => onSortChange(sortKey, sortDirection === "asc" ? "desc" : "asc")}
                        className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-border bg-card text-muted-foreground transition-all duration-200 hover:bg-secondary hover:text-foreground"
                        title={sortDirection === "asc" ? "Ascending (click for descending)" : "Descending (click for ascending)"}
                        aria-label={`Sort direction: ${sortDirection === "asc" ? "ascending" : "descending"}`}
                    >
                        {sortDirection === "asc"
                            ? <ArrowUpAZ className="h-3.5 w-3.5"/>
                            : <ArrowDownAZ className="h-3.5 w-3.5"/>}
                    </button>
                </div>

                <button
                    type="button"
                    onClick={() => onChange({languageOnly: !filters.languageOnly})}
                    className={`flex h-9 w-full md:w-auto items-center justify-center md:justify-start gap-1.5 rounded-xl border px-3 text-xs font-medium transition-all duration-200 md:shrink-0 ${
                        filters.languageOnly
                            ? "border-amber-300 bg-amber-50 text-amber-700 hover:bg-amber-100 dark:border-amber-700/50 dark:bg-amber-950 dark:text-amber-300 dark:hover:bg-amber-900"
                            : "border-border bg-card text-muted-foreground hover:bg-secondary hover:text-foreground"
                    }`}
                    title="Show only sites with language inconsistency"
                >
                    <Globe className="h-3 w-3"/>
                    Lang
                </button>

                {hasAnyFilter && (
                    <button
                        type="button"
                        onClick={() => onChange({
                            search: "",
                            status: "",
                            monitorSubType: "",
                            monitorStatus: "",
                            languageOnly: false
                        })}
                        className="flex h-9 w-full md:w-auto items-center justify-center gap-1 rounded-xl border border-border bg-card px-3 text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-secondary transition-all duration-200 md:shrink-0"
                    >
                        <X className="h-3 w-3"/>
                        Clear
                    </button>
                )}
            </div>
        </div>
    );
}
