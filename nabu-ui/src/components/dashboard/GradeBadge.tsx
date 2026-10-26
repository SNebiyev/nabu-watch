function gradeColor(grade: string) {
    if (grade.startsWith("A")) return "bg-emerald-100 text-emerald-700 ring-emerald-200/80 dark:bg-emerald-950/60 dark:text-emerald-400 dark:ring-emerald-800/40";
    if (grade.startsWith("B")) return "bg-sky-100 text-sky-700 ring-sky-200/80 dark:bg-sky-950/60 dark:text-sky-400 dark:ring-sky-800/40";
    if (grade.startsWith("C")) return "bg-amber-100 text-amber-700 ring-amber-200/80 dark:bg-amber-950/60 dark:text-amber-400 dark:ring-amber-800/40";
    if (grade.startsWith("D")) return "bg-orange-100 text-orange-700 ring-orange-200/80 dark:bg-orange-950/60 dark:text-orange-400 dark:ring-orange-800/40";
    return "bg-red-100 text-red-700 ring-red-200/80 dark:bg-red-950/60 dark:text-red-400 dark:ring-red-900/40";
}

export function GradeBadge({grade, score}: { grade?: string | null; score?: number | null }) {
    if (!grade) return null;
    return (
        <span
            title={score != null ? `Compliance score: ${score}/100` : undefined}
            className={`inline-flex shrink-0 items-center rounded-lg px-2 py-0.5 text-[11px] font-bold ring-1 tabular-nums ${gradeColor(grade)}`}
        >
            {grade}
        </span>
    );
}
