'use client';

import {useCallback, useEffect, useState} from "react";
import {Sidebar} from "@/components/layout/Sidebar";
import {HeaderBar} from "@/components/layout/HeaderBar";
import {EmailVerificationBanner} from "@/components/layout/EmailVerificationBanner";

export function AppLayout({ children }: { children: React.ReactNode }) {
    const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
    const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
    const [isDesktop, setIsDesktop] = useState(false);

    useEffect(() => {
        const saved = localStorage.getItem("nabu-sidebar-collapsed");
        if (saved === "true") setSidebarCollapsed(true);

        const mq = window.matchMedia("(min-width: 1024px)");
        setIsDesktop(mq.matches);
        const handler = (e: MediaQueryListEvent) => setIsDesktop(e.matches);
        mq.addEventListener("change", handler);
        return () => mq.removeEventListener("change", handler);
    }, []);

    const toggleCollapse = useCallback(() => {
        setSidebarCollapsed(prev => {
            const next = !prev;
            localStorage.setItem("nabu-sidebar-collapsed", String(next));
            return next;
        });
    }, []);

    const closeMobile = useCallback(() => setMobileMenuOpen(false), []);

    const ml = isDesktop
        ? (sidebarCollapsed ? "var(--sidebar-collapsed-width)" : "var(--sidebar-width)")
        : "0px";

    return (
        <div className="flex min-h-screen bg-background text-foreground transition-colors">
            <Sidebar
                collapsed={sidebarCollapsed}
                onToggleCollapse={toggleCollapse}
                mobileOpen={mobileMenuOpen}
                onMobileClose={closeMobile}
            />

            <div
                className="flex-1 flex flex-col min-h-screen"
                style={{
                    marginLeft: ml,
                    transition: "margin-left 300ms cubic-bezier(0.4, 0, 0.2, 1)",
                }}
            >
                <HeaderBar onMenuClick={() => setMobileMenuOpen(true)}/>
                <EmailVerificationBanner/>

                <main className="flex-1 flex flex-col px-4 pb-8 pt-4 sm:px-6 lg:px-8 xl:px-10">
                    <div className="w-full flex flex-col flex-1 min-h-0">
                        {children}
                    </div>
                </main>

                <footer className="hidden sm:block py-4 px-4 sm:px-6 lg:px-8 border-t border-border">
                    <div className="flex flex-col sm:flex-row justify-between items-center gap-4">
                        <p className="text-xs text-muted-foreground">
                            © {new Date().getFullYear()} Samir Nabiyev. nabu-watch is open source (Apache-2.0).
                        </p>
                    </div>
                </footer>
            </div>
        </div>
    );
}
