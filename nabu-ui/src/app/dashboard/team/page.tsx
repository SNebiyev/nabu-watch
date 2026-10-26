"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {Bell, Check, Loader2, Mail, Pencil, Plus, Shield, Trash2, User, Users, X} from "lucide-react";
import {apiClient} from "@/lib/apiClient";
import {useAuth} from "@/lib/authContext";
import {Toggle} from "@/components/ui/Toggle";

interface Member {
    userId: string;
    email: string;
    fullName: string;
    role: "OWNER" | "MEMBER";
    joinedAt: string;
}

interface MemberNotificationSettings {
    userId: string;
    alertsEnabled: boolean;
    digestEnabled: boolean;
}

export default function TeamPage() {
    const {currentOrganizationId, currentOrganizationName, currentOrganizationRole, refreshMe} = useAuth();
    const [members, setMembers] = useState<Member[]>([]);
    const [loading, setLoading] = useState(true);
    const [inviteEmail, setInviteEmail] = useState("");
    const [inviting, setInviting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);

    // Org name editing
    const [editingName, setEditingName] = useState(false);
    const [orgNameDraft, setOrgNameDraft] = useState("");
    const [savingName, setSavingName] = useState(false);
    const nameInputRef = useRef<HTMLInputElement>(null);

    // Notification settings (owner only)
    const [notifSettings, setNotifSettings] = useState<Map<string, MemberNotificationSettings>>(new Map());
    const [savingNotif, setSavingNotif] = useState<string | null>(null);

    const isOwner = currentOrganizationRole === "OWNER";

    const fetchMembers = useCallback(async () => {
        if (!currentOrganizationId) return;
        try {
            const res = await apiClient(`/api/v1/organizations/${currentOrganizationId}/members`);
            const data: Member[] = await res.json();
            setMembers(data);
        } catch {
            setError("Failed to load team members");
        } finally {
            setLoading(false);
        }
    }, [currentOrganizationId]);

    const fetchNotifSettings = useCallback(async () => {
        if (!currentOrganizationId || !isOwner) return;
        try {
            const res = await apiClient(`/api/v1/organizations/${currentOrganizationId}/notification-settings`);
            const data: MemberNotificationSettings[] = await res.json();
            const map = new Map<string, MemberNotificationSettings>();
            for (const s of data) map.set(s.userId, s);
            setNotifSettings(map);
        } catch {
            // Silently fail — notification settings are supplementary
        }
    }, [currentOrganizationId, isOwner]);

    useEffect(() => {
        fetchMembers();
    }, [fetchMembers]);

    useEffect(() => {
        fetchNotifSettings();
    }, [fetchNotifSettings]);

    const startEditingName = () => {
        setOrgNameDraft(currentOrganizationName ?? "");
        setEditingName(true);
        setTimeout(() => nameInputRef.current?.focus(), 0);
    };

    const handleRenameSave = async () => {
        const trimmed = orgNameDraft.trim();
        if (!trimmed || !currentOrganizationId) return;
        if (trimmed === currentOrganizationName) {
            setEditingName(false);
            return;
        }
        setSavingName(true);
        setError(null);
        try {
            await apiClient(`/api/v1/organizations/${currentOrganizationId}`, {
                method: "PATCH",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({name: trimmed}),
            });
            await refreshMe();
            setEditingName(false);
            setSuccess("Workspace name updated");
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to rename workspace");
        } finally {
            setSavingName(false);
        }
    };

    const handleInvite = async () => {
        if (!inviteEmail.trim() || !currentOrganizationId) return;
        setInviting(true);
        setError(null);
        setSuccess(null);
        try {
            await apiClient(`/api/v1/organizations/${currentOrganizationId}/members`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({email: inviteEmail.trim()}),
            });
            setSuccess(`${inviteEmail.trim()} has been added to the team`);
            setInviteEmail("");
            await Promise.all([fetchMembers(), fetchNotifSettings()]);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to add member");
        } finally {
            setInviting(false);
        }
    };

    const handleRemove = async (userId: string, email: string) => {
        if (!currentOrganizationId) return;
        if (!confirm(`Remove ${email} from this workspace?`)) return;
        setError(null);
        try {
            await apiClient(`/api/v1/organizations/${currentOrganizationId}/members/${userId}`, {
                method: "DELETE",
            });
            await Promise.all([fetchMembers(), fetchNotifSettings()]);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to remove member");
        }
    };

    const handleRoleChange = async (userId: string, newRole: string) => {
        if (!currentOrganizationId) return;
        setError(null);
        try {
            await apiClient(`/api/v1/organizations/${currentOrganizationId}/members/${userId}`, {
                method: "PATCH",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({role: newRole}),
            });
            await fetchMembers();
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to change role");
        }
    };

    const handleNotifToggle = async (
        userId: string,
        field: "alertsEnabled" | "digestEnabled",
        value: boolean
    ) => {
        if (!currentOrganizationId) return;
        setSavingNotif(`${userId}-${field}`);
        try {
            const res = await apiClient(
                `/api/v1/organizations/${currentOrganizationId}/members/${userId}/notification-settings`,
                {
                    method: "PUT",
                    headers: {"Content-Type": "application/json"},
                    body: JSON.stringify({[field]: value}),
                }
            );
            const data: MemberNotificationSettings = await res.json();
            setNotifSettings((prev) => new Map(prev).set(data.userId, data));
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to update notification setting");
        } finally {
            setSavingNotif(null);
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
                    <Users className="h-5 w-5"/>
                    Team
                </h1>
                <div className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                    {editingName ? (
                        <div className="flex items-center gap-2 mt-1">
                            <input
                                ref={nameInputRef}
                                type="text"
                                value={orgNameDraft}
                                onChange={(e) => setOrgNameDraft(e.target.value)}
                                onKeyDown={(e) => {
                                    if (e.key === "Enter") handleRenameSave();
                                    if (e.key === "Escape") setEditingName(false);
                                }}
                                maxLength={40}
                                className="rounded-lg border border-blue-400 bg-white dark:bg-slate-800 px-2.5 py-1 text-sm font-medium text-slate-900 dark:text-white focus:outline-none focus:ring-1 focus:ring-blue-400"
                            />
                            <button
                                onClick={handleRenameSave}
                                disabled={savingName || !orgNameDraft.trim()}
                                className="flex h-7 w-7 items-center justify-center rounded-lg text-green-600 hover:bg-green-50 dark:hover:bg-green-900/20 disabled:opacity-40"
                                title="Save"
                            >
                                {savingName ? <Loader2 className="h-3.5 w-3.5 animate-spin"/> :
                                    <Check className="h-3.5 w-3.5"/>}
                            </button>
                            <button
                                onClick={() => setEditingName(false)}
                                className="flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
                                title="Cancel"
                            >
                                <X className="h-3.5 w-3.5"/>
                            </button>
                        </div>
                    ) : (
                        <p>
                            Manage members of{" "}
                            <span className="font-medium text-slate-700 dark:text-slate-300">
                                {currentOrganizationName ?? "your workspace"}
                            </span>
                            {isOwner && (
                                <button
                                    onClick={startEditingName}
                                    className="ml-1.5 inline-flex items-center text-slate-400 hover:text-blue-500 transition-colors"
                                    title="Rename workspace"
                                >
                                    <Pencil className="h-3 w-3"/>
                                </button>
                            )}
                        </p>
                    )}
                </div>
            </div>

            {/* Invite form (owner only) */}
            {isOwner && (
                <div
                    className="mb-6 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 p-4">
                    <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">Add a team member</h2>
                    <div className="flex gap-2">
                        <input
                            type="email"
                            value={inviteEmail}
                            onChange={(e) => setInviteEmail(e.target.value)}
                            onKeyDown={(e) => e.key === "Enter" && handleInvite()}
                            placeholder="colleague@company.com"
                            className="flex-1 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 px-3 py-2 text-sm text-slate-900 dark:text-white placeholder:text-slate-400 focus:border-blue-400 focus:outline-none focus:ring-1 focus:ring-blue-400"
                        />
                        <button
                            onClick={handleInvite}
                            disabled={inviting || !inviteEmail.trim()}
                            className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
                        >
                            {inviting ? <Loader2 className="h-4 w-4 animate-spin"/> : <Plus className="h-4 w-4"/>}
                            Add
                        </button>
                    </div>
                    <p className="mt-2 text-xs text-slate-400 dark:text-slate-500">
                        The user must already have an account on this instance.
                    </p>
                </div>
            )}

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

            {/* Members list */}
            <div
                className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 divide-y divide-slate-100 dark:divide-slate-800">
                {members.map((m) => {
                    const ns = notifSettings.get(m.userId);
                    return (
                        <div key={m.userId} className="px-4 py-3">
                            <div className="flex items-center justify-between">
                                <div className="flex items-center gap-3 min-w-0">
                                    <div
                                        className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white ${
                                            m.role === "OWNER" ? "bg-amber-500" : "bg-blue-600"
                                        }`}>
                                        {m.role === "OWNER" ? <Shield className="h-3.5 w-3.5"/> :
                                            <User className="h-3.5 w-3.5"/>}
                                    </div>
                                    <div className="min-w-0">
                                        <p className="truncate text-sm font-medium text-slate-900 dark:text-white">
                                            {m.fullName}
                                        </p>
                                        <p className="truncate text-xs text-slate-500 dark:text-slate-400">
                                            {m.email}
                                        </p>
                                    </div>
                                </div>

                                <div className="flex items-center gap-2 shrink-0 ml-4">
                                    {isOwner ? (
                                        <select
                                            value={m.role}
                                            onChange={(e) => handleRoleChange(m.userId, e.target.value)}
                                            className="rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 px-2 py-1 text-xs text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-1 focus:ring-blue-400"
                                        >
                                            <option value="OWNER">Owner</option>
                                            <option value="MEMBER">Member</option>
                                        </select>
                                    ) : (
                                        <span
                                            className={`rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide ${
                                                m.role === "OWNER"
                                                    ? "bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-400"
                                                    : "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400"
                                            }`}>
                                            {m.role}
                                        </span>
                                    )}

                                    {isOwner && m.role !== "OWNER" && (
                                        <button
                                            onClick={() => handleRemove(m.userId, m.email)}
                                            className="rounded-lg p-1.5 text-slate-400 transition-colors hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-900/20 dark:hover:text-red-400"
                                            title={`Remove ${m.email}`}
                                        >
                                            <Trash2 className="h-3.5 w-3.5"/>
                                        </button>
                                    )}
                                </div>
                            </div>

                            {/* Owner notification controls per member */}
                            {isOwner && ns && (
                                <div className="mt-2 ml-11 flex items-center gap-4">
                                    <div className="flex items-center gap-1.5">
                                        <Mail className="h-3 w-3 text-slate-400"/>
                                        <span className="text-[11px] text-slate-500 dark:text-slate-400">Alerts</span>
                                        {savingNotif === `${m.userId}-alertsEnabled` && (
                                            <Loader2 className="h-2.5 w-2.5 animate-spin text-slate-400"/>
                                        )}
                                        <Toggle
                                            checked={ns.alertsEnabled}
                                            onChange={(val) => handleNotifToggle(m.userId, "alertsEnabled", val)}
                                            disabled={savingNotif !== null}
                                            size="sm"
                                            aria-label={`Toggle alerts for ${m.fullName}`}
                                        />
                                    </div>
                                    <div className="flex items-center gap-1.5">
                                        <Bell className="h-3 w-3 text-slate-400"/>
                                        <span className="text-[11px] text-slate-500 dark:text-slate-400">Digest</span>
                                        {savingNotif === `${m.userId}-digestEnabled` && (
                                            <Loader2 className="h-2.5 w-2.5 animate-spin text-slate-400"/>
                                        )}
                                        <Toggle
                                            checked={ns.digestEnabled}
                                            onChange={(val) => handleNotifToggle(m.userId, "digestEnabled", val)}
                                            disabled={savingNotif !== null}
                                            size="sm"
                                            aria-label={`Toggle digest for ${m.fullName}`}
                                        />
                                    </div>
                                </div>
                            )}
                        </div>
                    );
                })}

                {members.length === 0 && (
                    <div className="px-4 py-8 text-center text-sm text-slate-400 dark:text-slate-500">
                        No members found
                    </div>
                )}
            </div>

            {isOwner && members.length > 0 && (
                <p className="mt-3 text-xs text-slate-400 dark:text-slate-500">
                    Toggle who receives scan alert emails and weekly digests for this organization.
                    Members can also disable notifications from their own profile settings.
                </p>
            )}
        </div>
    );
}
