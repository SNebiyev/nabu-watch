"use client";

import {createContext, type ReactNode, useCallback, useContext, useEffect, useRef, useState,} from "react";
import {Client, type IMessage} from "@stomp/stompjs";
import SockJS from "sockjs-client";
import {apiClient} from "@/lib/apiClient";
import {getToken} from "@/lib/authService";
import {useAuth} from "@/lib/authContext";

// ── Types ─────────────────────────────────────────────────────────────────────

export type NotificationType =
    | "SCAN_COMPLETED"
    | "SCAN_FAILED"
    | "CERTIFICATE_EXPIRY"
    | "COMPLIANCE_ISSUE"
    | "NEW_MESSAGE"
    | "SECURITY_ALERT"
    | "SCAN_SUMMARY";

export interface AppNotification {
    id: string;
    title: string;
    message: string;
    type: NotificationType;
    relatedEntityId: string | null;
    read: boolean;
    createdAt: string | number[];
    organizationName: string | null;
}

interface NotificationPageResponse {
    content: AppNotification[];
    last: boolean;
}

interface NotificationContextValue {
    notifications: AppNotification[];
    unreadCount: number;
    isLastPage: boolean;
    loadingMore: boolean;
    markAsRead: (id: string) => void;
    markAllAsRead: () => void;
    loadMore: () => void;
}

const NotificationContext = createContext<NotificationContextValue>({
    notifications: [],
    unreadCount: 0,
    isLastPage: true,
    loadingMore: false,
    markAsRead: () => {
    },
    markAllAsRead: () => {
    },
    loadMore: () => {
    },
});

// ── Notification sound ────────────────────────────────────────────────────────

function playNotificationSound() {
    try {
        const AudioCtx =
            window.AudioContext ||
            (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
        if (!AudioCtx) return;
        const ctx = new AudioCtx();
        const t = ctx.currentTime;

        // Two-note soft chime: C6 (1047 Hz) then E6 (1319 Hz)
        // Short, gentle, non-intrusive — like a bubble tap
        for (const [freq, start] of [[1047, 0], [1319, 0.1]] as const) {
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.connect(gain);
            gain.connect(ctx.destination);
            osc.type = "sine";
            osc.frequency.value = freq;
            gain.gain.setValueAtTime(0.04, t + start);
            gain.gain.exponentialRampToValueAtTime(0.001, t + start + 0.15);
            osc.start(t + start);
            osc.stop(t + start + 0.15);
        }
        // Close context after both notes finish
        setTimeout(() => ctx.close(), 400);
    } catch {
        // AudioContext not supported or blocked by autoplay policy — silent fail
    }
}

// ── Provider ──────────────────────────────────────────────────────────────────

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const PAGE_SIZE = 10;

export function NotificationProvider({children}: { children: ReactNode }) {
    const {email, loading: authLoading} = useAuth();

    const [notifications, setNotifications] = useState<AppNotification[]>([]);
    const [page, setPage] = useState(0);
    const [isLastPage, setIsLastPage] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);

    const clientRef = useRef<Client | null>(null);

    const unreadCount = notifications.filter((n) => !n.read).length;

    // ── Initial load (page 0) ─────────────────────────────────────────────────

    useEffect(() => {
        if (!email) return;

        apiClient(`/api/v1/notifications?page=0&size=${PAGE_SIZE}`)
            .then((res) => (res.ok ? (res.json() as Promise<NotificationPageResponse>) : null))
            .then((data) => {
                if (!data) return;
                setNotifications(data.content);
                setIsLastPage(data.last);
                setPage(0);
            })
            .catch(() => {
            });
    }, [email]);

    // ── Paginated "load more" ─────────────────────────────────────────────────

    const loadMore = useCallback(() => {
        if (isLastPage || loadingMore) return;

        const nextPage = page + 1;
        setLoadingMore(true);

        apiClient(`/api/v1/notifications?page=${nextPage}&size=${PAGE_SIZE}`)
            .then((res) => (res.ok ? (res.json() as Promise<NotificationPageResponse>) : null))
            .then((data) => {
                if (!data) return;
                setNotifications((prev) => {
                    const existingIds = new Set(prev.map((n) => n.id));
                    const fresh = data.content.filter((n) => !existingIds.has(n.id));
                    return [...prev, ...fresh];
                });
                setIsLastPage(data.last);
                setPage(nextPage);
            })
            .catch(() => {
            })
            .finally(() => setLoadingMore(false));
    }, [isLastPage, loadingMore, page]);

    // ── WebSocket connection ───────────────────────────────────────────────────

    useEffect(() => {
        if (authLoading || !email) return;

        const token = getToken();
        if (!token) return;

        const client = new Client({
            webSocketFactory: () =>
                new SockJS(`${API_URL}/ws-notifications`) as WebSocket,
            connectHeaders: {Authorization: `Bearer ${token}`},
            beforeConnect: () => {
                const freshToken = getToken();
                if (freshToken) {
                    client.connectHeaders = {Authorization: `Bearer ${freshToken}`};
                }
            },
            reconnectDelay: 5000,
            onConnect: () => {
                client.subscribe("/user/queue/notifications", (frame: IMessage) => {
                    try {
                        const incoming = JSON.parse(frame.body) as AppNotification;
                        // Prepend and deduplicate — the WS message may race with a fresh REST fetch
                        setNotifications((prev) => {
                            if (prev.some((n) => n.id === incoming.id)) return prev;
                            playNotificationSound();
                            return [{...incoming, read: false}, ...prev];
                        });
                    } catch {
                        // malformed frame — ignore
                    }
                });
            },
            onStompError: (frame) => {
                console.warn("[ws-notifications] STOMP error:", frame.headers?.message);
            },
        });

        client.activate();
        clientRef.current = client;

        return () => {
            client.deactivate();
            clientRef.current = null;
        };
    }, [email, authLoading]);

    // ── Actions ───────────────────────────────────────────────────────────────

    const markAsRead = useCallback((id: string) => {
        setNotifications((prev) =>
            prev.map((n) => (n.id === id ? {...n, read: true} : n))
        );
        apiClient(`/api/v1/notifications/${id}/read`, {method: "PUT"}).catch(() => {
        });
    }, []);

    const markAllAsRead = useCallback(() => {
        setNotifications((prev) => prev.map((n) => ({...n, read: true})));
        apiClient("/api/v1/notifications/read-all", {method: "PUT"}).catch(() => {
        });
    }, []);

    return (
        <NotificationContext.Provider
            value={{
                notifications,
                unreadCount,
                isLastPage,
                loadingMore,
                markAsRead,
                markAllAsRead,
                loadMore,
            }}
        >
            {children}
        </NotificationContext.Provider>
    );
}

export function useNotifications(): NotificationContextValue {
    return useContext(NotificationContext);
}
