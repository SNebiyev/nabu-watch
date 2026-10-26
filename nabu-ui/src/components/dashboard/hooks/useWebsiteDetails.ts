"use client";

import {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {apiClient, MfaChallengeError, RateLimitError} from "@/lib/apiClient";
import type {LatestScanItem, MonitorSubType, ScanBatch, SyncMonitorsResult, WebsiteHealth} from "@/lib/types";
import {useAuth} from "@/lib/authContext";
import {websiteCache} from "@/lib/websiteCache";
import {formatRetryAfter, hasComplianceData, normalizeResultJson} from "../drawer/utils";

// ── Module-level in-flight scan tracker ──────────────────────────────────────
// Persists across mount/unmount so reopening the panel while a
// user-triggered scan is still running restores the "Scanning..." state.
const inFlightScans = new Map<string, number>();

export interface UseWebsiteDetailsOptions {
    onMonitorsUpdated?: () => void;
    onClose?: () => void;
}

export function useWebsiteDetails(
    website: WebsiteHealth | null,
    options: UseWebsiteDetailsOptions = {},
) {
    const {onMonitorsUpdated, onClose} = options;

    const cachedInit = websiteCache.get(website?.id);
    const [scans, setScans] = useState<LatestScanItem[]>(cachedInit?.scans ?? []);
    const [loading, setLoading] = useState(false);
    const [rescanning, setRescanning] = useState(false);
    const [rescanDone, setRescanDone] = useState(false);
    const [refreshing, setRefreshing] = useState(false);
    const [downloadingPdf, setDownloadingPdf] = useState(false);
    const [expandSignal, setExpandSignal] = useState<{ open: boolean; n: number } | null>(null);
    const [anyExpanded, setAnyExpanded] = useState(false);
    const [syncing, setSyncing] = useState(false);
    const [manageMode, setManageMode] = useState(false);
    const [saveResult, setSaveResult] = useState<SyncMonitorsResult | null>(null);
    const [localActiveSubTypes, setLocalActiveSubTypes] = useState<MonitorSubType[] | null>(null);
    const [history, setHistory] = useState<ScanBatch[]>(cachedInit?.history ?? []);
    const [historyIndex, setHistoryIndex] = useState(0);
    const [rescanCooldownMsg, setRescanCooldownMsg] = useState<string | null>(null);
    const [cooldownMonitorId, setCooldownMonitorId] = useState<string | null>(null);
    const [batchCooldown, setBatchCooldown] = useState(cachedInit?.cooldown?.batch ?? 0);
    const [domainCooldown, setDomainCooldown] = useState(cachedInit?.cooldown?.domain ?? 0);
    const [completionBanner, setCompletionBanner] = useState<"issues" | "clear" | null>(null);
    const [totpOpen, setTotpOpen] = useState(false);
    const [totpError, setTotpError] = useState<string | undefined>();
    const [pendingForce, setPendingForce] = useState<"website" | string | null>(null);
    const [settingsOpen, setSettingsOpen] = useState(false);
    const [localStatus, setLocalStatus] = useState<string>(website?.websiteStatus ?? "ACTIVE");
    const [localScreenshots, setLocalScreenshots] = useState(website?.screenshotsEnabled ?? false);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleting, setDeleting] = useState(false);
    const [deleteError, setDeleteError] = useState<string | undefined>();

    const auth = useAuth();

    const isAdmin = auth.isAdmin;

    const deleteRequiresOtp =
        auth.isAdmin &&
        !!website?.ownerEmail &&
        auth.email !== website.ownerEmail;

    // ── Refs ─────────────────────────────────────────────────────────────────────
    const onMonitorsUpdatedRef = useRef(onMonitorsUpdated);
    onMonitorsUpdatedRef.current = onMonitorsUpdated;
    const onCloseRef = useRef(onClose);
    onCloseRef.current = onClose;
    const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
    const openedAtRef = useRef<number>(0);
    const websiteIdRef = useRef<string | undefined>(undefined);
    websiteIdRef.current = website?.id;
    const processingMonitorIdsRef = useRef<Set<string>>(new Set());
    const scansRef = useRef<LatestScanItem[]>([]);
    scansRef.current = scans;

    useEffect(() => {
        if (!website?.id || scans.length === 0) return;
        websiteCache.patch(website.id, {scans});
    }, [website?.id, scans]);

    // ── Cooldown tick ────────────────────────────────────────────────────────────
    useEffect(() => {
        if (batchCooldown <= 0 && domainCooldown <= 0) return;
        const timer = setInterval(() => {
            setBatchCooldown((prev) => Math.max(0, prev - 1));
            setDomainCooldown((prev) => Math.max(0, prev - 1));
        }, 1000);
        return () => clearInterval(timer);
    }, [batchCooldown, domainCooldown]);

    // ── Polling ──────────────────────────────────────────────────────────────────
    const stopPolling = useCallback(() => {
        if (pollingRef.current != null) {
            clearInterval(pollingRef.current);
            pollingRef.current = null;
        }
        processingMonitorIdsRef.current = new Set();
        setSyncing(false);
        setRescanDone(false);
    }, []);

    const startPolling = useCallback(() => {
        if (pollingRef.current != null) return;
        setSyncing(true);
        pollingRef.current = setInterval(async () => {
            const id = websiteIdRef.current;
            if (!id) {
                stopPolling();
                return;
            }
            try {
                const res = await apiClient(`/api/v1/websites/${id}/latest-scans`);
                if (!res.ok) return;
                const raw: LatestScanItem[] = await res.json();
                const tracking = processingMonitorIdsRef.current;

                if (tracking.size > 0) {
                    setScans((prev) =>
                        raw.map((incoming) => {
                            const isFresh =
                                incoming.scannedAt != null &&
                                new Date(incoming.scannedAt).getTime() > openedAtRef.current;
                            if (tracking.has(incoming.monitorId) && !isFresh) {
                                return prev.find((p) => p.monitorId === incoming.monitorId) ??
                                    {...incoming, resultJson: normalizeResultJson(incoming.resultJson)};
                            }
                            return {...incoming, resultJson: normalizeResultJson(incoming.resultJson)};
                        })
                    );
                    const updated = new Set(tracking);
                    for (const s of raw) {
                        if (
                            tracking.has(s.monitorId) &&
                            s.scannedAt != null &&
                            new Date(s.scannedAt).getTime() > openedAtRef.current
                        ) {
                            updated.delete(s.monitorId);
                        }
                    }
                    processingMonitorIdsRef.current = updated;
                    if (updated.size === 0) {
                        const hasComplianceIssues = raw.some(
                            (s) =>
                                s.monitorType === "COMPLIANCE" &&
                                (s.effectiveStatus === "FAILING" || s.effectiveStatus === "WARNING"),
                        );
                        inFlightScans.delete(id);
                        setCompletionBanner(hasComplianceIssues ? "issues" : "clear");
                        stopPolling();
                        onMonitorsUpdatedRef.current?.();
                    }
                } else {
                    setScans(raw.map((s) => ({...s, resultJson: normalizeResultJson(s.resultJson)})));
                    const allFinal = raw.every((s) => s.status !== "PENDING");
                    if (allFinal) {
                        const hasComplianceIssues = raw.some(
                            (s) =>
                                s.monitorType === "COMPLIANCE" &&
                                (s.effectiveStatus === "FAILING" || s.effectiveStatus === "WARNING"),
                        );
                        inFlightScans.delete(id);
                        setCompletionBanner(hasComplianceIssues ? "issues" : "clear");
                        stopPolling();
                        onMonitorsUpdatedRef.current?.();
                    }
                }
            } catch {
                // Transient — keep polling
            }
        }, 4000);
    }, [stopPolling]);

    // ── Sync local state from props ──────────────────────────────────────────────
    useEffect(() => {
        setLocalStatus(website?.websiteStatus ?? "ACTIVE");
    }, [website?.websiteStatus]);

    useEffect(() => {
        setLocalScreenshots(website?.screenshotsEnabled ?? false);
    }, [website?.screenshotsEnabled]);

    useEffect(() => {
        setLocalActiveSubTypes(null);
    }, [website?.monitors]);

    // ── Load data on website change ──────────────────────────────────────────────
    useEffect(() => {
        if (!website) {
            setScans([]);
            setManageMode(false);
            setSaveResult(null);
            setLocalActiveSubTypes(null);
            setHistory([]);
            setHistoryIndex(0);
            setCompletionBanner(null);
            setBatchCooldown(0);
            setDomainCooldown(0);
            stopPolling();
            return;
        }
        openedAtRef.current = Date.now();
        setHistoryIndex(0);
        const hasCached = !!websiteCache.get(website.id)?.scans;
        if (!hasCached) setLoading(true);
        setRescanDone(false);
        apiClient(`/api/v1/websites/${website.id}/latest-scans`)
            .then((res) => (res.ok ? res.json() : []))
            .then((data: LatestScanItem[]) => {
                const triggerTs = inFlightScans.get(website.id);
                if (triggerTs) {
                    openedAtRef.current = triggerTs;
                    const staleIds = new Set(
                        data
                            .filter((s) => s.status !== "INACTIVE" && (s.scannedAt == null || new Date(s.scannedAt).getTime() <= triggerTs))
                            .map((s) => s.monitorId),
                    );
                    if (staleIds.size > 0) {
                        processingMonitorIdsRef.current = staleIds;
                        setScans(
                            data.map((s) => ({
                                ...s,
                                status: staleIds.has(s.monitorId) ? ("PENDING" as const) : s.status,
                                resultJson: staleIds.has(s.monitorId) ? null : normalizeResultJson(s.resultJson),
                            })),
                        );
                        startPolling();
                    } else {
                        inFlightScans.delete(website.id);
                        setScans(data.map((s) => ({...s, resultJson: normalizeResultJson(s.resultJson)})));
                    }
                } else {
                    setScans(data.map((s) => ({...s, resultJson: normalizeResultJson(s.resultJson)})));
                    if (data.some((s) => s.status === "PENDING")) startPolling();
                }
            })
            .catch(() => setScans([]))
            .finally(() => setLoading(false));
        apiClient(`/api/v1/websites/${website.id}/scan-history`)
            .then((r) => (r.ok ? r.json() : []))
            .then((data: ScanBatch[]) => {
                setHistory(data);
                websiteCache.patch(website.id, {history: data});
            })
            .catch(() => {
            });
        apiClient(`/api/v1/websites/${website.id}/cooldown`)
            .then((r) => (r.ok ? r.json() : null))
            .then((data: { batchCooldownSeconds: number; domainCooldownSeconds: number } | null) => {
                if (data) {
                    setBatchCooldown(data.batchCooldownSeconds);
                    setDomainCooldown(data.domainCooldownSeconds);
                    websiteCache.patch(website.id, {cooldown: {batch: data.batchCooldownSeconds, domain: data.domainCooldownSeconds}});
                }
            })
            .catch(() => {
            });
        return () => stopPolling();
    }, [website, startPolling, stopPolling]);

    // ── Handlers ─────────────────────────────────────────────────────────────────

    const handleMonitorRescan = useCallback(async (monitorId: string) => {
        websiteCache.invalidate(websiteIdRef.current);
        setScans((prev) =>
            prev.map((s) =>
                s.monitorId === monitorId
                    ? {...s, status: "PENDING" as const, resultJson: null}
                    : s
            )
        );
        processingMonitorIdsRef.current = new Set([monitorId]);
        openedAtRef.current = Date.now();
        try {
            await apiClient(`/api/v1/monitors/${monitorId}/scan`, {method: "POST"});
            setRescanCooldownMsg(null);
            setCooldownMonitorId(null);
            startPolling();
            const wid = websiteIdRef.current;
            if (wid) {
                apiClient(`/api/v1/websites/${wid}/cooldown`)
                    .then((r) => (r.ok ? r.json() : null))
                    .then((data: { batchCooldownSeconds: number; domainCooldownSeconds: number } | null) => {
                        if (data) {
                            setBatchCooldown(data.batchCooldownSeconds);
                            setDomainCooldown(data.domainCooldownSeconds);
                        }
                    })
                    .catch(() => {
                    });
            }
        } catch (e) {
            if (e instanceof RateLimitError) {
                setRescanCooldownMsg(`Cooldown active. ${e.detail ?? formatRetryAfter(e.retryAfter)}`);
                setCooldownMonitorId(monitorId);
            }
            processingMonitorIdsRef.current.delete(monitorId);
            setScans((prev) =>
                prev.map((s) =>
                    s.monitorId === monitorId && s.status === "PENDING"
                        ? {...s, status: "FAILING" as const}
                        : s
                )
            );
        }
    }, [startPolling]);

    const handleRescan = useCallback(async () => {
        if (!website || rescanning) return;
        websiteCache.invalidate(website.id);
        setRescanning(true);
        setRescanDone(false);
        setRescanCooldownMsg(null);
        setCooldownMonitorId(null);
        setCompletionBanner(null);
        try {
            await apiClient(`/api/v1/websites/${website.id}/rescan`, {method: "POST"});
            setScans((prev) =>
                prev.map((s) =>
                    s.status === "INACTIVE" ? s : {...s, status: "PENDING" as const, resultJson: null}
                )
            );
            processingMonitorIdsRef.current = new Set(
                scansRef.current.filter((s) => s.status !== "INACTIVE").map((s) => s.monitorId)
            );
            openedAtRef.current = Date.now();
            inFlightScans.set(website.id, openedAtRef.current);
            setHistoryIndex(0);
            setRescanDone(true);
            startPolling();
            apiClient(`/api/v1/websites/${website.id}/cooldown`)
                .then((r) => (r.ok ? r.json() : null))
                .then((data: { batchCooldownSeconds: number; domainCooldownSeconds: number } | null) => {
                    if (data) {
                        setBatchCooldown(data.batchCooldownSeconds);
                        setDomainCooldown(data.domainCooldownSeconds);
                    }
                })
                .catch(() => {
                });
        } catch (e) {
            if (e instanceof RateLimitError) {
                setRescanCooldownMsg(`Cooldown active. ${e.detail ?? formatRetryAfter(e.retryAfter)}`);
            }
        } finally {
            setRescanning(false);
        }
    }, [website, rescanning, startPolling]);

    const handleRefresh = useCallback(async () => {
        if (!website || refreshing) return;
        setRefreshing(true);
        try {
            const [scansRes, historyRes] = await Promise.all([
                apiClient(`/api/v1/websites/${website.id}/latest-scans`),
                apiClient(`/api/v1/websites/${website.id}/scan-history`),
            ]);
            if (scansRes.ok) {
                const data: LatestScanItem[] = await scansRes.json();
                const tracking = processingMonitorIdsRef.current;
                const merged = data.map((s) => {
                    const norm = {...s, resultJson: normalizeResultJson(s.resultJson)};
                    if (tracking.has(s.monitorId) && s.status !== "PENDING") {
                        return {...norm, status: "PENDING" as const};
                    }
                    return norm;
                });
                setScans(merged);
                if (data.some((s) => s.status === "PENDING") || tracking.size > 0) startPolling();
            }
            if (historyRes.ok) {
                const data: ScanBatch[] = await historyRes.json();
                setHistory(data);
            }
            onMonitorsUpdatedRef.current?.();
        } finally {
            setRefreshing(false);
        }
    }, [website, refreshing, startPolling]);

    const handleForceRescan = useCallback(() => {
        setPendingForce(cooldownMonitorId ?? "website");
        setTotpError(undefined);
        setTotpOpen(true);
    }, [cooldownMonitorId]);

    const handleTotpConfirm = useCallback(async (code: string) => {
        if (!website || !pendingForce) return;
        websiteCache.invalidate(website.id);
        setTotpError(undefined);
        try {
            const path =
                pendingForce === "website"
                    ? `/api/v1/websites/${website.id}/rescan?force=true`
                    : `/api/v1/monitors/${pendingForce}/scan?force=true`;
            await apiClient(path, {
                method: "POST",
                headers: {"X-MFA-Token": code},
                skipAuthRedirect: true,
            });
            setTotpOpen(false);
            setPendingForce(null);
            setCooldownMonitorId(null);
            setRescanCooldownMsg(null);
            setCompletionBanner(null);
            openedAtRef.current = Date.now();
            inFlightScans.set(website.id, openedAtRef.current);
            setHistoryIndex(0);
            setRescanDone(true);

            if (pendingForce !== "website") {
                const monitorId = pendingForce;
                setScans((prev) =>
                    prev.map((s) =>
                        s.monitorId === monitorId
                            ? {...s, status: "PENDING" as const, resultJson: null}
                            : s
                    )
                );
                processingMonitorIdsRef.current = new Set([monitorId]);
            } else {
                setScans((prev) =>
                    prev.map((s) =>
                        s.status === "INACTIVE" ? s : {...s, status: "PENDING" as const, resultJson: null}
                    )
                );
                processingMonitorIdsRef.current = new Set(
                    scansRef.current.filter((s) => s.status !== "INACTIVE").map((s) => s.monitorId)
                );
            }
            startPolling();
        } catch (e) {
            if (e instanceof MfaChallengeError) {
                setTotpError("Invalid or expired TOTP code. Try again.");
            }
        }
    }, [website, pendingForce, startPolling]);

    const handleMonitorsSaved = useCallback(async (result: SyncMonitorsResult, enabledSubTypes: MonitorSubType[]) => {
        setSaveResult(result);
        setLocalActiveSubTypes(enabledSubTypes);
        setManageMode(false);
        onMonitorsUpdatedRef.current?.();
        if (website) {
            try {
                const res = await apiClient(`/api/v1/websites/${website.id}/latest-scans`);
                if (res.ok) {
                    const data: LatestScanItem[] = await res.json();
                    setScans(data.map((s) => ({...s, resultJson: normalizeResultJson(s.resultJson)})));
                }
            } catch {
                // Non-critical
            }
        }
    }, [website]);

    const handleRowExpand = useCallback(() => setAnyExpanded(true), []);

    const handleExpandAll = useCallback(() => {
        const shouldExpand = !anyExpanded;
        setExpandSignal((s) => ({open: shouldExpand, n: (s?.n ?? 0) + 1}));
        setAnyExpanded(shouldExpand);
    }, [anyExpanded]);

    const handleDownloadPdf = useCallback(async () => {
        if (!website?.id) return;
        setDownloadingPdf(true);
        try {
            const res = await apiClient(`/api/v1/websites/${website.id}/compliance-report.pdf`);
            const blob = await res.blob();
            const domain = (website.url ?? "report").replace(/^https?:\/\//, "").replace(/^www\./, "").split("/")[0];
            const date = new Date().toISOString().slice(0, 10);
            const a = document.createElement("a");
            a.href = URL.createObjectURL(blob);
            a.download = `nabu-compliance-report-${domain}-${date}.pdf`;
            a.click();
            URL.revokeObjectURL(a.href);
        } catch (err) {
            console.error("[panel] PDF download failed", err);
        } finally {
            setDownloadingPdf(false);
        }
    }, [website?.id, website?.url]);

    const handleManageMode = useCallback(() => {
        setManageMode((prev) => {
            if (!prev) setSaveResult(null);
            return !prev;
        });
    }, []);

    const handleTotpCancel = useCallback(() => {
        setTotpOpen(false);
        setPendingForce(null);
        setTotpError(undefined);
    }, []);

    const handleSettingsOpen = useCallback(() => setSettingsOpen(true), []);
    const handleSettingsClose = useCallback(() => setSettingsOpen(false), []);

    const handleStatusChanged = useCallback((newStatus: string) => {
        setLocalStatus(newStatus);
        onMonitorsUpdatedRef.current?.();
    }, []);

    const handleLanguageChanged = useCallback(() => {
        onMonitorsUpdatedRef.current?.();
    }, []);

    const handleScreenshotsChanged = useCallback((enabled: boolean) => {
        setLocalScreenshots(enabled);
        onMonitorsUpdatedRef.current?.();
    }, []);

    const handleDeleteCancel = useCallback(() => {
        setDeleteOpen(false);
        setDeleteError(undefined);
    }, []);

    const handleOpenDelete = useCallback(() => {
        setDeleteError(undefined);
        setDeleteOpen(true);
    }, []);

    const handleDismissCompletionBanner = useCallback(() => setCompletionBanner(null), []);

    const handleRequestDeleteOtp = useCallback(async () => {
        if (!website) return;
        const fallback = "Could not send the confirmation code.";
        setDeleteError(undefined);
        try {
            const res = await apiClient(`/api/v1/websites/${website.id}/delete-otp`, {
                method: "POST",
                skipAuthRedirect: true,
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({})) as { detail?: string };
                setDeleteError(body.detail ?? fallback);
            }
        } catch {
            setDeleteError(fallback);
        }
    }, [website]);

    const handleDeleteConfirm = useCallback(async (otpCode?: string) => {
        if (!website) return;
        setDeleting(true);
        setDeleteError(undefined);
        try {
            const res = await apiClient(`/api/v1/websites/${website.id}`, {
                method: "DELETE",
                ...(otpCode ? {headers: {"X-Delete-OTP": otpCode}} : {}),
                skipAuthRedirect: true,
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({})) as { errorCode?: string; detail?: string };
                if (body.errorCode === "OTP_INVALID") {
                    setDeleteError(body.detail ?? "Invalid or expired code. Please try again.");
                } else {
                    setDeleteError("Delete failed. Please try again.");
                }
                return;
            }
            setDeleteOpen(false);
            onCloseRef.current?.();
            onMonitorsUpdatedRef.current?.();
        } catch {
            setDeleteError("Delete failed. Please try again.");
        } finally {
            setDeleting(false);
        }
    }, [website]);

    // ── Computed values ──────────────────────────────────────────────────────────

    const activeComplianceSubTypes = useMemo(
        () =>
            localActiveSubTypes ??
            (website?.monitors ?? [])
                .filter((m) => m.type === "COMPLIANCE" && m.active)
                .map((m) => m.subType),
        [website, localActiveSubTypes]
    );

    const {failingCount, passingCount, warningCount, pendingCount, sslFailing, hasIncompleteScans} =
        useMemo(() => {
            let failing = 0;
            let passing = 0;
            let warnings = 0;
            let pending = 0;
            let ssl = false;
            let incomplete = false;

            for (const s of scans) {
                if (s.status === "PENDING") pending++;
                if (s.effectiveStatus === "FAILING") failing++;
                if (s.effectiveStatus === "PASSING") passing++;
                if (s.monitorType === "SSL" && s.effectiveStatus === "FAILING") ssl = true;
                if (s.effectiveStatus === "WARNING") warnings++;
                if (
                    s.monitorType === "COMPLIANCE" &&
                    s.status === "PASSING" &&
                    s.resultJson != null &&
                    !hasComplianceData(s.resultJson)
                )
                    incomplete = true;
            }
            return {
                failingCount: failing,
                passingCount: passing,
                warningCount: warnings,
                pendingCount: pending,
                sslFailing: ssl,
                hasIncompleteScans: incomplete,
            };
        }, [scans]);

    const isHistoryMode = historyIndex > 0;
    const rawDisplayed = isHistoryMode
        ? (history[historyIndex - 1]?.scans ?? [])
        : scans;

    const displayedScans = useMemo(() => {
        const seen = new Set<string>();
        return rawDisplayed.filter((s) => {
            if (seen.has(s.monitorId)) return false;
            seen.add(s.monitorId);
            return true;
        });
    }, [rawDisplayed]);

    const canDelete = auth.isAdmin || (!!website && auth.email === website.ownerEmail);

    return {
        // State (read-only for consumers)
        scans,
        loading,
        rescanning,
        rescanDone,
        refreshing,
        downloadingPdf,
        expandSignal,
        anyExpanded,
        syncing,
        manageMode,
        setManageMode,
        saveResult,
        history,
        historyIndex,
        setHistoryIndex,
        rescanCooldownMsg,
        batchCooldown,
        domainCooldown,
        completionBanner,
        totpOpen,
        totpError,
        settingsOpen,
        localStatus,
        localScreenshots,
        deleteOpen,
        deleting,
        deleteError,
        isAdmin,
        deleteRequiresOtp,
        auth,

        // Computed
        failingCount,
        passingCount,
        warningCount,
        pendingCount,
        sslFailing,
        hasIncompleteScans,
        isHistoryMode,
        displayedScans,
        canDelete,
        activeComplianceSubTypes,

        // Handlers
        handleRescan,
        handleRefresh,
        handleForceRescan,
        handleTotpConfirm,
        handleTotpCancel,
        handleMonitorRescan,
        handleMonitorsSaved,
        handleRowExpand,
        handleExpandAll,
        handleDownloadPdf,
        handleManageMode,
        handleOpenDelete,
        handleDeleteConfirm,
        handleDeleteCancel,
        handleRequestDeleteOtp,
        handleSettingsOpen,
        handleSettingsClose,
        handleStatusChanged,
        handleLanguageChanged,
        handleScreenshotsChanged,
        handleDismissCompletionBanner,
    };
}
