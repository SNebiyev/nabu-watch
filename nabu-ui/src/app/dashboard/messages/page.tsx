"use client";

import {Suspense, useCallback, useEffect, useMemo, useRef, useState,} from "react";
import {useRouter, useSearchParams} from "next/navigation";
import {useAuth} from "@/lib/authContext";
import {apiClient} from "@/lib/apiClient";
import {type MessageDto, type MessagePageResponse, useMessages,} from "@/lib/MessageContext";
import {useTimeAgo} from "@/hooks/useTimeAgo";
import {SUPPORT_EMAIL} from "@/lib/siteConfig";
import {ArrowLeft, Loader2, Mail, MessageSquare, PenSquare, Send, Shield, X,} from "lucide-react";

// ── Constants ─────────────────────────────────────────────────────────────────

// SUPPORT_EMAIL (siteConfig, from NEXT_PUBLIC_SUPPORT_EMAIL) must correspond to a real
// admin account in the database so that messages are delivered and the admin can reply.

// ── Helpers ───────────────────────────────────────────────────────────────────

function avatarLetter(email: string): string {
    return (email?.[0] ?? "?").toUpperCase();
}

// ── Types ─────────────────────────────────────────────────────────────────────

interface Contact {
    email: string;
    lastMessage: string;
    lastMessageAt: string;
    hasUnread: boolean;
}

// ── Live-timestamp sub-components (hooks cannot be called inside .map()) ──────

function ContactTime({dateValue}: { dateValue: unknown }) {
    const label = useTimeAgo(dateValue);
    return <span className="shrink-0 text-[10px] text-slate-400 dark:text-slate-500">{label}</span>;
}

function BubbleTime({dateValue, isMine}: { dateValue: unknown; isMine: boolean }) {
    const label = useTimeAgo(dateValue);
    return (
        <p className={`mt-1 text-[10px] ${isMine ? "text-blue-200" : "text-slate-400 dark:text-slate-500"}`}>
            {label}
        </p>
    );
}

// ── Inner component (uses useSearchParams — must live inside a Suspense boundary)

function MessagesInner() {
    const {email: myEmail, isAdmin, loading: authLoading} = useAuth();
    const {refreshUnreadCount, subscribeToNewMessages} = useMessages();
    const searchParams = useSearchParams();
    const router = useRouter();

    // ── Data ──────────────────────────────────────────────────────────────────
    const [inbox, setInbox] = useState<MessageDto[]>([]);
    const [sent, setSent] = useState<MessageDto[]>([]);
    const [loadingData, setLoadingData] = useState(true);

    // ── UI ────────────────────────────────────────────────────────────────────
    const [selectedContact, setSelectedContact] = useState<string | null>(null);
    const [messageInput, setMessageInput] = useState("");
    const [sending, setSending] = useState(false);
    const [sendError, setSendError] = useState<string | null>(null);

    // Admin-only compose dialog state
    const [composeOpen, setComposeOpen] = useState(false);
    const [composeEmail, setComposeEmail] = useState("");
    const [composeError, setComposeError] = useState("");

    const bottomRef = useRef<HTMLDivElement>(null);
    const textareaRef = useRef<HTMLTextAreaElement>(null);
    const composeInputRef = useRef<HTMLInputElement>(null);

    // Fires once: was the page opened with ?new=support?
    const shouldOpenSupport = useRef(searchParams.get("new") === "support");

    // ── Fetch ─────────────────────────────────────────────────────────────────

    const fetchMessages = useCallback(async () => {
        setLoadingData(true);
        try {
            const [inboxRes, sentRes] = await Promise.all([
                apiClient("/api/v1/messages/inbox?page=0&size=100"),
                apiClient("/api/v1/messages/sent?page=0&size=100"),
            ]);
            const [inboxData, sentData]: [MessagePageResponse, MessagePageResponse] =
                await Promise.all([
                    inboxRes.ok ? inboxRes.json() : {content: [], last: true},
                    sentRes.ok ? sentRes.json() : {content: [], last: true},
                ]);
            setInbox(inboxData.content);
            setSent(sentData.content);
        } catch {
            // apiClient already handles auth-level redirects
        } finally {
            setLoadingData(false);
        }
    }, []);

    useEffect(() => {
        fetchMessages();
    }, [fetchMessages]);

    // ── Derive contacts ───────────────────────────────────────────────────────

    const contacts: Contact[] = useMemo(() => {
        const map = new Map<string, { latestMsg: MessageDto; hasUnread: boolean }>();

        for (const msg of inbox) {
            const existing = map.get(msg.senderId);
            const isNewer =
                !existing ||
                new Date(msg.createdAt) > new Date(existing.latestMsg.createdAt);
            map.set(msg.senderId, {
                latestMsg: isNewer ? msg : existing!.latestMsg,
                hasUnread: (existing?.hasUnread ?? false) || !msg.isRead,
            });
        }

        for (const msg of sent) {
            if (!map.has(msg.receiverId)) {
                map.set(msg.receiverId, {latestMsg: msg, hasUnread: false});
            } else {
                const existing = map.get(msg.receiverId)!;
                if (
                    new Date(msg.createdAt) >
                    new Date(existing.latestMsg.createdAt)
                ) {
                    map.set(msg.receiverId, {...existing, latestMsg: msg});
                }
            }
        }

        return Array.from(map.entries())
            .map(([email, {latestMsg, hasUnread}]) => ({
                email,
                lastMessage: latestMsg.content,
                lastMessageAt: latestMsg.createdAt,
                hasUnread,
            }))
            .sort(
                (a, b) =>
                    new Date(b.lastMessageAt).getTime() -
                    new Date(a.lastMessageAt).getTime()
            );
    }, [inbox, sent]);

    /**
     * Privacy guard: regular users see at most ONE entry labelled "Support"
     * (their most recent admin contact).  Admins see the full unfiltered list.
     */
    const displayContacts = useMemo((): Contact[] => {
        if (isAdmin) return contacts;
        // Show only the most-recent admin/support contact; never expose peer emails
        return contacts.length > 0 ? [contacts[0]] : [];
    }, [contacts, isAdmin]);

    // A non-admin can start a thread only if there is an existing support contact or a
    // configured SUPPORT_EMAIL. Otherwise the "message support" actions are hidden.
    const canContactSupport = isAdmin || displayContacts.length > 0 || !!SUPPORT_EMAIL;

    // ── Deep-link: ?new=support ───────────────────────────────────────────────
    //   Waits for both data fetch and auth resolution before acting so that
    //   `isAdmin` and `contacts` are accurate.

    useEffect(() => {
        if (loadingData || authLoading || !shouldOpenSupport.current) return;
        shouldOpenSupport.current = false; // run exactly once

        if (!isAdmin) {
            // Select the existing admin/support contact, or fall back to the
            // configured SUPPORT_EMAIL so the user can start a fresh thread.
            const target = contacts[0]?.email ?? SUPPORT_EMAIL;
            if (target) setSelectedContact(target);
        }

        // Strip the query param without a full navigation to keep the URL clean
        router.replace("/dashboard/messages", {scroll: false});
    }, [loadingData, authLoading, isAdmin, contacts, router]);

    // ── Derive conversation ───────────────────────────────────────────────────

    const conversation: MessageDto[] = useMemo(() => {
        if (!selectedContact) return [];
        const received = inbox.filter((m) => m.senderId === selectedContact);
        const dispatched = sent.filter((m) => m.receiverId === selectedContact);
        return [...received, ...dispatched].sort(
            (a, b) =>
                new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
        );
    }, [inbox, sent, selectedContact]);

    // ── Auto-scroll ───────────────────────────────────────────────────────────

    useEffect(() => {
        bottomRef.current?.scrollIntoView({behavior: "smooth"});
    }, [conversation]);

    // ── Mark as read on open ──────────────────────────────────────────────────

    useEffect(() => {
        if (!selectedContact) return;
        // Optimistic local update
        setInbox((prev) =>
            prev.map((m) =>
                m.senderId === selectedContact ? {...m, isRead: true} : m
            )
        );
        apiClient(
            `/api/v1/messages/conversations/${encodeURIComponent(selectedContact)}/read`,
            {method: "PUT"}
        )
            .then(() => refreshUnreadCount())
            .catch(() => {
            });
    }, [selectedContact, refreshUnreadCount]);

    // ── WebSocket handler ─────────────────────────────────────────────────────

    useEffect(() => {
        const unsubscribe = subscribeToNewMessages((msg) => {
            const incoming: MessageDto = {
                id: msg.id,
                senderId: msg.senderId,
                receiverId: msg.receiverId,
                content: msg.content,
                isRead: false,
                createdAt: msg.createdAt,
            };

            setInbox((prev) => {
                if (prev.some((m) => m.id === msg.id)) return prev;
                return [incoming, ...prev];
            });

            // Active conversation → mark as read immediately
            if (selectedContact === msg.senderId) {
                setInbox((prev) =>
                    prev.map((m) =>
                        m.senderId === msg.senderId ? {...m, isRead: true} : m
                    )
                );
                apiClient(
                    `/api/v1/messages/conversations/${encodeURIComponent(msg.senderId)}/read`,
                    {method: "PUT"}
                )
                    .then(() => refreshUnreadCount())
                    .catch(() => {
                    });
            }
        });
        return unsubscribe;
    }, [subscribeToNewMessages, selectedContact, refreshUnreadCount]);

    // ── Send ──────────────────────────────────────────────────────────────────

    async function handleSend() {
        const content = messageInput.trim();
        if (!content || !selectedContact || sending) return;
        setSending(true);
        setSendError(null);

        try {
            const res = await apiClient("/api/v1/messages", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({receiverEmail: selectedContact, content}),
            });

            if (res.status === 400) {
                const body = await res.json().catch(() => ({}));
                setSendError(
                    (body as { detail?: string }).detail ??
                    "Message blocked by security policy."
                );
                return;
            }

            if (!res.ok) {
                setSendError("Failed to send. Please try again.");
                return;
            }

            const saved: MessageDto = await res.json();
            setSent((prev) => [saved, ...prev]);
            setMessageInput("");
            if (textareaRef.current) textareaRef.current.style.height = "auto";
            textareaRef.current?.focus();
        } catch {
            setSendError("Network error. Please try again.");
        } finally {
            setSending(false);
        }
    }

    function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
        if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
            e.preventDefault();
            handleSend();
        }
    }

    // ── Compose / new-message actions ─────────────────────────────────────────

    function handleNewMessage() {
        if (isAdmin) {
            setComposeEmail("");
            setComposeError("");
            setComposeOpen(true);
            setTimeout(() => composeInputRef.current?.focus(), 50);
        } else {
            // Non-admin: go straight to the Support thread
            const target = displayContacts[0]?.email ?? SUPPORT_EMAIL;
            if (target) setSelectedContact(target);
        }
    }

    function handleComposeSubmit() {
        const email = composeEmail.trim();
        if (!email || !email.includes("@")) {
            setComposeError("Enter a valid email address.");
            return;
        }
        if (email === myEmail) {
            setComposeError("You cannot message yourself.");
            return;
        }
        setComposeOpen(false);
        setSelectedContact(email);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    return (
        <div className="flex flex-col flex-1 min-h-0">
            {/* ── Page title ───────────────────────────────────────────────── */}
            <div className="mb-4 flex items-center gap-2.5">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-blue-600/10">
                    <Mail className="h-4 w-4 text-blue-600"/>
                </div>
                <h1 className="text-lg font-bold text-slate-900 dark:text-white">
                    Messages
                </h1>
                {!isAdmin && (
                    <span
                        className="rounded-full bg-blue-100 dark:bg-blue-950/40 px-2.5 py-0.5 text-[10px] font-semibold text-blue-600 dark:text-blue-400">
                        Support
                    </span>
                )}
            </div>

            {/* ── Split-pane container ─────────────────────────────────────── */}
            <div
                className="flex flex-1 min-h-0 rounded-2xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900/50 overflow-hidden shadow-sm">

                {/* ══ LEFT PANE — contacts ══════════════════════════════════ */}
                <aside
                    className={`${selectedContact ? "hidden md:flex" : "flex"} w-full md:w-72 shrink-0 flex-col border-r border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900`}>

                    {/* Pane header */}
                    <div
                        className="flex shrink-0 items-center justify-between border-b border-slate-100 dark:border-slate-800 px-4 py-2.5">
                        <p className="text-xs font-semibold uppercase tracking-widest text-slate-400 dark:text-slate-500">
                            {isAdmin ? "Conversations" : "Support"}
                        </p>
                        {canContactSupport && (
                        <button
                            onClick={handleNewMessage}
                            className="flex items-center gap-1.5 rounded-lg bg-blue-50 dark:bg-blue-950/30 px-2.5 py-1 text-[11px] font-semibold text-blue-600 dark:text-blue-400 transition-colors hover:bg-blue-100 dark:hover:bg-blue-950/50"
                            title={isAdmin ? "Compose new message" : "Contact Support"}
                        >
                            <PenSquare className="h-3 w-3"/>
                            {isAdmin ? "New" : "New message"}
                        </button>
                        )}
                    </div>

                    {/* Contact list — THIS is the only scrollable region on the left */}
                    <div className="flex-1 overflow-y-auto">
                        {loadingData ? (
                            <div className="flex items-center justify-center py-10">
                                <Loader2 className="h-5 w-5 animate-spin text-slate-400"/>
                            </div>
                        ) : displayContacts.length === 0 ? (
                            <div className="flex flex-col items-center justify-center gap-3 py-12 text-center px-4">
                                {isAdmin ? (
                                    <MessageSquare className="h-8 w-8 text-slate-300 dark:text-slate-600"/>
                                ) : (
                                    <Shield className="h-8 w-8 text-slate-300 dark:text-slate-600"/>
                                )}
                                <p className="text-sm text-slate-400 dark:text-slate-500">
                                    {isAdmin
                                        ? "No conversations yet"
                                        : "No messages from Support yet"}
                                </p>
                            </div>
                        ) : (
                            displayContacts.map((contact) => {
                                const isActive = selectedContact === contact.email;
                                return (
                                    <button
                                        key={contact.email}
                                        onClick={() => setSelectedContact(contact.email)}
                                        className={`w-full text-left px-4 py-3 border-b border-slate-50 dark:border-slate-800/60 last:border-b-0 transition-colors hover:bg-slate-50 dark:hover:bg-slate-800/40 ${
                                            isActive
                                                ? "bg-blue-50 dark:bg-blue-950/30"
                                                : ""
                                        }`}
                                    >
                                        <div className="flex items-start gap-3">
                                            {/* Avatar — letter for admin view, Shield for user view */}
                                            <div
                                                className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white ${
                                                    isActive
                                                        ? "bg-blue-600"
                                                        : "bg-slate-400 dark:bg-slate-600"
                                                }`}
                                            >
                                                {isAdmin ? (
                                                    avatarLetter(contact.email)
                                                ) : (
                                                    <Shield className="h-3.5 w-3.5"/>
                                                )}
                                            </div>

                                            <div className="min-w-0 flex-1">
                                                <div className="flex items-center justify-between gap-1">
                                                    {/*
                                                        Task 2 — Admin anonymity:
                                                        · Admin → real email
                                                        · Regular user → hardcoded "Support"
                                                    */}
                                                    <p
                                                        className={`truncate text-sm ${
                                                            contact.hasUnread && !isActive
                                                                ? "font-semibold text-slate-900 dark:text-white"
                                                                : "font-medium text-slate-700 dark:text-slate-300"
                                                        }`}
                                                    >
                                                        {isAdmin ? contact.email : "Support"}
                                                    </p>
                                                    <ContactTime dateValue={contact.lastMessageAt}/>
                                                </div>

                                                <div className="flex items-center gap-1.5 mt-0.5">
                                                    <p className="flex-1 truncate text-xs text-slate-400 dark:text-slate-500">
                                                        {contact.lastMessage}
                                                    </p>
                                                    {contact.hasUnread && !isActive && (
                                                        <span className="shrink-0 h-2 w-2 rounded-full bg-blue-500"/>
                                                    )}
                                                </div>
                                            </div>
                                        </div>
                                    </button>
                                );
                            })
                        )}
                    </div>
                </aside>

                {/* ══ RIGHT PANE — chat ════════════════════════════════════ */}
                <div
                    className={`${!selectedContact ? "hidden md:flex" : "flex"} flex-1 flex-col min-w-0 bg-slate-50 dark:bg-slate-950`}>
                    {!selectedContact ? (
                        /* Empty / welcome state */
                        <div className="flex flex-1 flex-col items-center justify-center gap-3 text-center p-8">
                            <div
                                className="flex h-14 w-14 items-center justify-center rounded-2xl bg-blue-100 dark:bg-blue-950/40">
                                {isAdmin ? (
                                    <Mail className="h-7 w-7 text-blue-500"/>
                                ) : (
                                    <Shield className="h-7 w-7 text-blue-500"/>
                                )}
                            </div>
                            <div>
                                <p className="text-sm font-semibold text-slate-700 dark:text-slate-300">
                                    {isAdmin
                                        ? "Select a conversation"
                                        : "Contact our support team"}
                                </p>
                                <p className="mt-1 text-xs text-slate-400 dark:text-slate-500">
                                    {isAdmin
                                        ? "Choose a contact on the left, or start a new conversation"
                                        : "Our team typically responds within 24 hours"}
                                </p>
                                {!isAdmin && !canContactSupport && (
                                    <p className="mt-4 text-xs text-slate-400 dark:text-slate-500">
                                        Support contact is not configured.
                                    </p>
                                )}
                                {!isAdmin && canContactSupport && (
                                    <button
                                        onClick={handleNewMessage}
                                        className="mt-4 inline-flex items-center gap-1.5 rounded-xl bg-blue-600 px-4 py-2 text-xs font-semibold text-white hover:bg-blue-700 active:scale-95 transition-all"
                                    >
                                        <PenSquare className="h-3 w-3"/>
                                        Start a conversation
                                    </button>
                                )}
                            </div>
                        </div>
                    ) : (
                        <>
                            {/* Chat header */}
                            <div
                                className="flex shrink-0 items-center gap-3 border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-5 py-3">
                                {/* Back button — mobile only */}
                                <button
                                    onClick={() => setSelectedContact(null)}
                                    className="md:hidden flex items-center justify-center h-7 w-7 rounded-lg text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                                    aria-label="Back to contacts"
                                >
                                    <ArrowLeft className="h-4 w-4"/>
                                </button>
                                {isAdmin ? (
                                    <>
                                        <div
                                            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-blue-600 text-xs font-bold text-white">
                                            {avatarLetter(selectedContact)}
                                        </div>
                                        <div>
                                            <p className="text-sm font-semibold text-slate-900 dark:text-white">
                                                {selectedContact}
                                            </p>
                                            <p className="text-[10px] text-slate-400 dark:text-slate-500">
                                                Platform user
                                            </p>
                                        </div>
                                    </>
                                ) : (
                                    <>
                                        <div
                                            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-blue-600">
                                            <Shield className="h-4 w-4 text-white"/>
                                        </div>
                                        <div>
                                            <p className="text-sm font-semibold text-slate-900 dark:text-white">
                                                Support
                                            </p>
                                            <p className="flex items-center gap-1 text-[10px] text-slate-400 dark:text-slate-500">
                                                <Shield className="h-2.5 w-2.5"/>
                                                Support team
                                            </p>
                                        </div>
                                    </>
                                )}
                            </div>

                            {/* Message bubbles — THIS is the only scrollable region on the right */}
                            <div className="flex-1 overflow-y-auto px-5 py-4 space-y-3">
                                {conversation.length === 0 ? (
                                    <div className="flex flex-col items-center justify-center gap-2 py-12 text-center">
                                        <MessageSquare className="h-7 w-7 text-slate-300 dark:text-slate-600"/>
                                        <p className="text-sm text-slate-400 dark:text-slate-500">
                                            {isAdmin
                                                ? "Start the conversation"
                                                : "Send your first message to get help"}
                                        </p>
                                    </div>
                                ) : (
                                    conversation.map((msg) => {
                                        const isMine = msg.senderId === myEmail;
                                        return (
                                            <div
                                                key={msg.id}
                                                className={`flex ${isMine ? "justify-end" : "justify-start"}`}
                                            >
                                                <div
                                                    className={`max-w-[70%] rounded-2xl px-4 py-2.5 ${
                                                        isMine
                                                            ? "rounded-br-sm bg-blue-600 text-white"
                                                            : "rounded-bl-sm bg-white dark:bg-slate-800 text-slate-900 dark:text-white shadow-sm"
                                                    }`}
                                                >
                                                    <p className="whitespace-pre-wrap break-words text-sm leading-relaxed">
                                                        {msg.content}
                                                    </p>
                                                    <BubbleTime dateValue={msg.createdAt} isMine={isMine}/>
                                                </div>
                                            </div>
                                        );
                                    })
                                )}
                                {/* Auto-scroll anchor */}
                                <div ref={bottomRef}/>
                            </div>

                            {/* Input area — pinned, never scrolls */}
                            <div
                                className="shrink-0 border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4">
                                {sendError && (
                                    <p className="mb-2 rounded-lg bg-red-50 dark:bg-red-950/30 px-3 py-2 text-xs text-red-600 dark:text-red-400">
                                        {sendError}
                                    </p>
                                )}
                                <div className="flex items-end gap-3">
                                    <textarea
                                        ref={textareaRef}
                                        value={messageInput}
                                        onChange={(e) => {
                                            setMessageInput(e.target.value);
                                            setSendError(null);
                                            e.target.style.height = "auto";
                                            e.target.style.height =
                                                Math.min(e.target.scrollHeight, 120) + "px";
                                        }}
                                        onKeyDown={handleKeyDown}
                                        placeholder={
                                            isAdmin
                                                ? "Write a message… (⌘↵ to send)"
                                                : "Describe your issue… (⌘↵ to send)"
                                        }
                                        rows={1}
                                        maxLength={5000}
                                        className="flex-1 resize-none rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 px-3.5 py-2.5 text-sm text-slate-900 dark:text-white placeholder:text-slate-400 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20 overflow-hidden"
                                        style={{minHeight: "2.75rem", maxHeight: "7.5rem"}}
                                    />
                                    <button
                                        onClick={handleSend}
                                        disabled={!messageInput.trim() || sending}
                                        className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-blue-600 text-white shadow-sm transition-all hover:bg-blue-700 active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed"
                                        aria-label="Send message"
                                    >
                                        {sending ? (
                                            <Loader2 className="h-4 w-4 animate-spin"/>
                                        ) : (
                                            <Send className="h-4 w-4"/>
                                        )}
                                    </button>
                                </div>
                                <p className="mt-1.5 text-right text-[10px] text-slate-400 dark:text-slate-500">
                                    {messageInput.length}/5000
                                </p>
                            </div>
                        </>
                    )}
                </div>
            </div>

            {/* ── Admin compose dialog ──────────────────────────────────────── */}
            {composeOpen && isAdmin && (
                <div
                    className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
                    onClick={(e) => {
                        if (e.target === e.currentTarget) setComposeOpen(false);
                    }}
                >
                    <div
                        className="w-full max-w-sm rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 p-6 shadow-2xl">
                        {/* Dialog header */}
                        <div className="mb-4 flex items-center justify-between">
                            <div className="flex items-center gap-2">
                                <PenSquare className="h-4 w-4 text-blue-600"/>
                                <h2 className="text-sm font-semibold text-slate-900 dark:text-white">
                                    New Message
                                </h2>
                            </div>
                            <button
                                onClick={() => setComposeOpen(false)}
                                className="rounded-lg p-1 text-slate-400 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
                                aria-label="Close"
                            >
                                <X className="h-4 w-4"/>
                            </button>
                        </div>

                        <p className="mb-3 text-xs text-slate-500 dark:text-slate-400">
                            Enter the email of the user you want to message.
                        </p>

                        <input
                            ref={composeInputRef}
                            type="email"
                            value={composeEmail}
                            onChange={(e) => {
                                setComposeEmail(e.target.value);
                                setComposeError("");
                            }}
                            onKeyDown={(e) => {
                                if (e.key === "Enter") handleComposeSubmit();
                                if (e.key === "Escape") setComposeOpen(false);
                            }}
                            placeholder="user@example.com"
                            autoComplete="email"
                            className="w-full rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 px-3.5 py-2.5 text-sm text-slate-900 dark:text-white placeholder:text-slate-400 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20"
                        />

                        {composeError && (
                            <p className="mt-1.5 text-xs text-red-500 dark:text-red-400">
                                {composeError}
                            </p>
                        )}

                        <div className="mt-4 flex justify-end gap-2">
                            <button
                                onClick={() => setComposeOpen(false)}
                                className="rounded-xl px-4 py-2 text-xs font-medium text-slate-600 dark:text-slate-400 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={handleComposeSubmit}
                                className="rounded-xl bg-blue-600 px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-blue-700"
                            >
                                Start Chat
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}

// ── Default export (Suspense boundary required by useSearchParams) ─────────────

export default function MessagesPage() {
    return (
        <Suspense
            fallback={
                <div className="flex items-center justify-center py-20">
                    <Loader2 className="h-6 w-6 animate-spin text-slate-400"/>
                </div>
            }
        >
            <MessagesInner/>
        </Suspense>
    );
}
