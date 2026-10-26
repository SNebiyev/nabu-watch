"use client";

import Link from "next/link";
import {useState} from "react";
import {ArrowRight, Menu, Shield, X} from "lucide-react";
import {useAuth} from "@/lib/authContext";

interface NavLink {
  label: string;
  href: string;
}

const LINKS: NavLink[] = [
  {label: "Features", href: "/#features"},
  {label: "How It Works", href: "/#how-it-works"},
  {label: "Free tools", href: "/tools"},
];

export function SiteHeader() {
  const {email, loading} = useAuth();
  const loggedIn = !loading && !!email;
  const [mobileOpen, setMobileOpen] = useState(false);

  return (
    <nav className="fixed top-0 inset-x-0 z-50 border-b border-slate-800/60 bg-slate-950/95 md:bg-slate-950/80 md:backdrop-blur-md">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex h-16 items-center justify-between">
          <Link href="/" className="flex items-center gap-2">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-sky-500/10 border border-sky-500/30">
              <Shield className="h-4 w-4 text-sky-400" />
            </div>
            <span className="text-base font-semibold text-slate-100 tracking-tight">
              nabu<span className="text-sky-400">.watch</span>
            </span>
          </Link>

          <div className="hidden md:flex items-center gap-8">
            {LINKS.map((l) => (
              <Link
                key={l.label}
                href={l.href}
                className="text-sm text-slate-400 hover:text-slate-200 transition-colors"
              >
                {l.label}
              </Link>
            ))}
          </div>

          <div className="flex items-center gap-2 sm:gap-3">
            {loggedIn ? (
              <Link
                href="/dashboard"
                className="flex items-center gap-1.5 rounded-lg bg-sky-500 hover:bg-sky-400 px-3 sm:px-4 py-2 text-sm font-medium text-white transition-colors"
              >
                Dashboard
                <ArrowRight className="h-3.5 w-3.5" />
              </Link>
            ) : (
              <>
                <Link
                  href="/login"
                  className="hidden sm:block text-sm text-slate-400 hover:text-slate-200 transition-colors px-3 py-1.5"
                >
                  Sign In
                </Link>
                <Link
                  href="/register"
                  className="hidden sm:flex items-center gap-1.5 rounded-lg bg-sky-500 hover:bg-sky-400 px-4 py-2 text-sm font-medium text-white transition-colors"
                >
                  Get Started Free
                  <ArrowRight className="h-3.5 w-3.5" />
                </Link>
              </>
            )}

            <button
              type="button"
              aria-label={mobileOpen ? "Close menu" : "Open menu"}
              aria-expanded={mobileOpen}
              onClick={() => setMobileOpen((v) => !v)}
              className="md:hidden inline-flex h-9 w-9 items-center justify-center rounded-lg border border-slate-700 bg-slate-900/60 text-slate-200"
            >
              {mobileOpen ? <X className="h-4 w-4" /> : <Menu className="h-4 w-4" />}
            </button>
          </div>
        </div>

        {mobileOpen && (
          <div className="md:hidden border-t border-slate-800/60 py-3 flex flex-col gap-1">
            {LINKS.map((l) => (
              <Link
                key={l.label}
                href={l.href}
                onClick={() => setMobileOpen(false)}
                className="rounded-md px-3 py-2 text-sm text-slate-300 hover:bg-slate-900"
              >
                {l.label}
              </Link>
            ))}
            {!loggedIn && (
              <>
                <div className="my-2 h-px bg-slate-800/60" />
                <Link
                  href="/login"
                  onClick={() => setMobileOpen(false)}
                  className="rounded-md px-3 py-2 text-sm text-slate-300 hover:bg-slate-900"
                >
                  Sign in
                </Link>
                <Link
                  href="/register"
                  onClick={() => setMobileOpen(false)}
                  className="rounded-md bg-sky-500 px-3 py-2 text-sm font-medium text-white text-center hover:bg-sky-400"
                >
                  Get started free
                </Link>
              </>
            )}
          </div>
        )}
      </div>
    </nav>
  );
}
