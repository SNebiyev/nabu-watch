"use client";

import {createContext, type ReactNode, useCallback, useContext, useEffect, useRef, useState,} from "react";
import {Client, type IMessage} from "@stomp/stompjs";
import SockJS from "sockjs-client";
import {apiClient} from "@/lib/apiClient";
import {getToken} from "@/lib/authService";
import {useAuth} from "@/lib/authContext";

// ── Types ─────────────────────────────────────────────────────────────────────

/** Shape of the WebSocket event pushed to /user/{email}/queue/messages by the backend. */
export interface WsMessage {
    id: string;
    senderId: string;
    receiverId: string;
    content: string;
    isRead: boolean;
    createdAt: string;
}

/** Shape of a message DTO from the REST inbox/sent endpoints. */
export interface MessageDto {
    id: string;
    senderId: string;
    receiverId: string;
    content: string;
    isRead: boolean;
    createdAt: string;
}

export interface MessagePageResponse {
    content: MessageDto[];
    last: boolean;
}

export interface UnreadSendersCountResponse {
    unreadSenders: number;
}

type NewMessageListener = (msg: WsMessage) => void;

interface MessageContextValue {
    /** Number of distinct senders with at least one unread message. */
    unreadSendersCount: number;
    /** Re-fetches the count from the backend — call after marking a conversation read. */
    refreshUnreadCount: () => void;
    /**
     * Registers a listener that fires whenever a new WS message arrives.
     * Returns an unsubscribe function — call it in a useEffect cleanup.
     */
    subscribeToNewMessages: (listener: NewMessageListener) => () => void;
}

const MessageContext = createContext<MessageContextValue>({
    unreadSendersCount: 0,
    refreshUnreadCount: () => {
    },
    subscribeToNewMessages: () => () => {
    },
});

// ── Provider ──────────────────────────────────────────────────────────────────

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function MessageProvider({children}: { children: ReactNode }) {
    const {email, loading: authLoading} = useAuth();

    const [unreadSendersCount, setUnreadSendersCount] = useState(0);

    /** All registered live-chat listeners from the messages page. */
    const listenersRef = useRef<Set<NewMessageListener>>(new Set());

    // ── Unread count ─────────────────────────────────────────────────────────

    const refreshUnreadCount = useCallback(() => {
        if (!email) return;
        apiClient("/api/v1/messages/unread-senders-count")
            .then((res) => (res.ok ? (res.json() as Promise<UnreadSendersCountResponse>) : null))
            .then((data) => {
                if (data != null) setUnreadSendersCount(data.unreadSenders);
            })
            .catch(() => {
            });
    }, [email]);

    // Initial load once authenticated
    useEffect(() => {
        refreshUnreadCount();
    }, [refreshUnreadCount]);

    // Keep a ref so the STOMP callback (set up once) always sees the latest fn
    const refreshUnreadCountRef = useRef(refreshUnreadCount);
    useEffect(() => {
        refreshUnreadCountRef.current = refreshUnreadCount;
    }, [refreshUnreadCount]);

    // ── WebSocket connection ──────────────────────────────────────────────────

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
                // Subscribe to the dedicated message destination.
                // The backend pushes WsMessageEvent here after every saved message.
                client.subscribe("/user/queue/messages", (frame: IMessage) => {
                    try {
                        const msg = JSON.parse(frame.body) as WsMessage;

                        // Fire every registered listener (messages page handlers)
                        listenersRef.current.forEach((fn) => fn(msg));

                        // Refresh the badge — the messages page will call
                        // refreshUnreadCount() itself after marking as read,
                        // so the net result is always accurate.
                        refreshUnreadCountRef.current();
                    } catch {
                        // malformed frame — ignore
                    }
                });
            },
            onStompError: (frame) => {
                console.warn("[ws-messages] STOMP error:", frame.headers?.message);
            },
        });

        client.activate();

        return () => {
            client.deactivate();
        };
    }, [email, authLoading]);

    // ── Public API ────────────────────────────────────────────────────────────

    const subscribeToNewMessages = useCallback(
        (listener: NewMessageListener): (() => void) => {
            listenersRef.current.add(listener);
            return () => listenersRef.current.delete(listener);
        },
        []
    );

    return (
        <MessageContext.Provider
            value={{unreadSendersCount, refreshUnreadCount, subscribeToNewMessages}}
        >
            {children}
        </MessageContext.Provider>
    );
}

export function useMessages(): MessageContextValue {
    return useContext(MessageContext);
}
