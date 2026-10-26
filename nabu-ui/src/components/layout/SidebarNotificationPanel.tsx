"use client";

import {useRouter} from "next/navigation";
import {Bell, Loader2, X} from "lucide-react";
import {useAuth} from "@/lib/authContext";
import {type AppNotification, useNotifications} from "@/lib/NotificationContext";
import {NotificationItem, notificationRouteFor} from "./HeaderBar";

interface Props {
    open: boolean;
    sidebarCollapsed: boolean;
    onClose: () => void;
}

export function SidebarNotificationPanel({open, sidebarCollapsed, onClose}: Props) {
    const router = useRouter();
    const {organizations} = useAuth();
    const {
        notifications, unreadCount,
        isLastPage, loadingMore, loadMore,
        markAsRead, markAllAsRead,
    } = useNotifications();

    if (!open) return null;

    const left = sidebarCollapsed ? "var(--sidebar-collapsed-width)" : "var(--sidebar-width)";

    function handleClick(n: AppNotification) {
        markAsRead(n.id);
        const route = notificationRouteFor(n);
        if (route) {
            router.push(route);
            onClose();
        }
    }

    return (
        <>
            <div
                className="fixed inset-0 z-40 hidden lg:block bg-black/20"
                onClick={onClose}
                aria-hidden
            />
            <aside
                className="fixed top-0 z-50 hidden lg:flex h-screen w-96 flex-col border-r border-border bg-card shadow-xl"
                style={{left}}
            >
                <div className="flex items-center justify-between border-b border-border px-4 py-3">
                    <p className="text-sm font-semibold text-foreground">
                        Notifications
                        {unreadCount > 0 && (
                            <span className="ml-2 rounded-full bg-red-100 dark:bg-red-950/60 px-1.5 py-0.5 text-[10px] font-bold text-red-600 dark:text-red-400">
                                {unreadCount}
                            </span>
                        )}
                    </p>
                    <div className="flex items-center gap-1">
                        {unreadCount > 0 && (
                            <button
                                onClick={markAllAsRead}
                                className="text-[11px] font-medium text-primary hover:underline px-1.5"
                            >
                                Mark all read
                            </button>
                        )}
                        <button
                            onClick={onClose}
                            className="rounded-lg p-1 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                            aria-label="Close notifications"
                        >
                            <X className="h-4 w-4"/>
                        </button>
                    </div>
                </div>

                <div className="flex-1 overflow-y-auto">
                    {notifications.length === 0 ? (
                        <div className="flex flex-col items-center justify-center gap-2 py-16 text-center">
                            <Bell className="h-7 w-7 text-muted-foreground/30"/>
                            <p className="text-xs text-muted-foreground">No notifications yet</p>
                        </div>
                    ) : (
                        <>
                            {notifications.map((n) => (
                                <NotificationItem
                                    key={n.id}
                                    n={n}
                                    onClick={handleClick}
                                    showOrgBadge={organizations.length > 1}
                                />
                            ))}
                            {!isLastPage && (
                                <button
                                    onClick={loadMore}
                                    disabled={loadingMore}
                                    className="flex w-full items-center justify-center gap-1.5 border-t border-border py-2.5 text-[11px] font-medium text-primary transition-colors hover:bg-secondary disabled:opacity-50"
                                >
                                    {loadingMore && <Loader2 className="h-3 w-3 animate-spin"/>}
                                    {loadingMore ? "Loading..." : "Load more"}
                                </button>
                            )}
                        </>
                    )}
                </div>
            </aside>
        </>
    );
}
