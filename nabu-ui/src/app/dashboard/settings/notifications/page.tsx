"use client";

import {useCallback, useEffect, useState} from "react";
import {Bell, Loader2, Mail} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {Toggle} from "@/components/ui/Toggle";

interface NotificationPreferences {
    emailAlertsEnabled: boolean;
    weeklyDigestEnabled: boolean;
}

export default function NotificationSettingsPage() {
    const [prefs, setPrefs] = useState<NotificationPreferences | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);

    const fetchPrefs = useCallback(async () => {
        try {
            const res = await apiClient("/api/v1/users/me/notification-preferences");
            const data: NotificationPreferences = await res.json();
            setPrefs(data);
        } catch {
            setError("Failed to load notification preferences");
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchPrefs();
    }, [fetchPrefs]);

    const handleToggle = async (field: keyof NotificationPreferences, value: boolean) => {
        if (!prefs) return;
        setSaving(field);
        setError(null);
        setSuccess(null);
        try {
            const res = await apiClient("/api/v1/users/me/notification-preferences", {
                method: "PUT",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({[field]: value}),
            });
            const data: NotificationPreferences = await res.json();
            setPrefs(data);
            setSuccess("Preference updated");
            setTimeout(() => setSuccess(null), 2000);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to update preference");
        } finally {
            setSaving(null);
        }
    };

    if (loading) {
        return (
            <div className="flex min-h-[50vh] items-center justify-center">
                <Loader2 className="h-6 w-6 animate-spin text-slate-400"/>
            </div>
        );
    }

    return (
        <div className="w-full px-4 py-8 sm:px-6">
            {/* Header */}
            <div className="mb-8">
                <h1 className="text-xl font-bold text-slate-900 dark:text-white flex items-center gap-2">
                    <Bell className="h-5 w-5"/>
                    Notification Settings
                </h1>
                <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                    Control which email notifications you receive. These are your personal
                    preferences and apply across all your organizations.
                </p>
            </div>

            {/* Status messages */}
            {error && (
                <div
                    className="mb-4 rounded-lg border border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/20 px-4 py-3 text-sm text-red-700 dark:text-red-300">
                    {error}
                </div>
            )}
            {success && (
                <div
                    className="mb-4 rounded-lg border border-green-200 bg-green-50 dark:border-green-800 dark:bg-green-900/20 px-4 py-3 text-sm text-green-700 dark:text-green-300">
                    {success}
                </div>
            )}

            {prefs && (
                <div
                    className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 divide-y divide-slate-100 dark:divide-slate-800">
                    {/* Scan alert emails */}
                    <div className="flex items-center justify-between gap-4 px-5 py-4">
                        <div className="flex items-start gap-3 min-w-0">
                            <div
                                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-red-100 dark:bg-red-950/40 text-red-600 dark:text-red-400">
                                <Mail className="h-4 w-4"/>
                            </div>
                            <div className="min-w-0">
                                <p className="text-sm font-medium text-slate-900 dark:text-white">
                                    Scan alert emails
                                </p>
                                <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                                    Get an immediate email when a monitor regresses to HIGH or CRITICAL severity.
                                </p>
                            </div>
                        </div>
                        <div className="flex items-center gap-2 shrink-0">
                            {saving === "emailAlertsEnabled" && (
                                <Loader2 className="h-3.5 w-3.5 animate-spin text-slate-400"/>
                            )}
                            <Toggle
                                checked={prefs.emailAlertsEnabled}
                                onChange={(val) => handleToggle("emailAlertsEnabled", val)}
                                disabled={saving !== null}
                                aria-label="Toggle scan alert emails"
                            />
                        </div>
                    </div>

                    {/* Weekly digest */}
                    <div className="flex items-center justify-between gap-4 px-5 py-4">
                        <div className="flex items-start gap-3 min-w-0">
                            <div
                                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-blue-100 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400">
                                <Bell className="h-4 w-4"/>
                            </div>
                            <div className="min-w-0">
                                <p className="text-sm font-medium text-slate-900 dark:text-white">
                                    Weekly digest
                                </p>
                                <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                                    Receive a summary email every Monday with scan activity and failing websites across
                                    your organizations.
                                </p>
                            </div>
                        </div>
                        <div className="flex items-center gap-2 shrink-0">
                            {saving === "weeklyDigestEnabled" && (
                                <Loader2 className="h-3.5 w-3.5 animate-spin text-slate-400"/>
                            )}
                            <Toggle
                                checked={prefs.weeklyDigestEnabled}
                                onChange={(val) => handleToggle("weeklyDigestEnabled", val)}
                                disabled={saving !== null}
                                aria-label="Toggle weekly digest"
                            />
                        </div>
                    </div>
                </div>
            )}

            <p className="mt-4 text-xs text-slate-400 dark:text-slate-500">
                Note: Your organization owner may also control whether you receive notifications
                for specific organizations. Both your preference and the organization setting
                must be enabled for emails to be sent.
            </p>
        </div>
    );
}
