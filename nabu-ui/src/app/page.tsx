
"use client";

import React, {useEffect, useRef, useState} from "react";
import Link from "next/link";
import {SITE_DESCRIPTION, SITE_NAME, SITE_URL} from "@/lib/siteConfig";
import {SiteHeader} from "@/components/marketing/SiteHeader";
import {SiteFooter} from "@/components/marketing/SiteFooter";
import {TOOLS} from "@/lib/toolsCatalog";
import {
  ArrowRight,
  ArrowUp,
  BarChart3,
  Bot,
  Camera,
  CheckCircle2,
  ChevronRight,
  Clock,
  Code2,
  Eye,
  FileText,
  FileWarning,
  Fingerprint,
  Globe,
  Lock,
  Mail,
  Network,
  Shield,
  ShieldCheck,
  Zap,
} from "lucide-react";

// ─── Animation Components ─────────────────────────────────────────────────────

function FadeIn({children, className = "", delay = 0}: {
  children: React.ReactNode;
  className?: string;
  delay?: number
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const observer = new IntersectionObserver(
        ([entry]) => {
          if (entry.isIntersecting) setVisible(true);
        },
        {threshold: 0.1, rootMargin: "0px 0px -50px 0px"}
    );
    if (ref.current) observer.observe(ref.current);
    return () => observer.disconnect();
  }, []);

  return (
      <div
          ref={ref}
          className={`transition-all duration-700 motion-reduce:transition-none motion-reduce:!opacity-100 motion-reduce:!translate-y-0 ${visible ? "opacity-100 translate-y-0" : "opacity-0 translate-y-2"} ${className}`}
          style={{transitionDelay: `${delay}ms`}}
      >
        {children}
      </div>
  );
}

const SCAN_CASES = [
  {
    domain: "shop.example.de",
    items: [
      {label: "SSL Certificate", status: "pass", detail: "Valid · 142 days remaining"},
      {label: "Privacy Policy", status: "pass", detail: "Found in footer · Datenschutzerklärung"},
      {label: "Terms of Service", status: "pass", detail: "Found · AGB link verified"},
      {label: "Impressum", status: "fail", detail: "Not found on homepage"},
      {label: "Cookie Policy", status: "pass", detail: "Cookie-Richtlinie found"},
      {label: "GDPR Request", status: "pass", detail: "Datenschutzanfrage form found"},
      {label: "Cookie Banner", status: "warn", detail: "Banner detected · reject button missing"},
      {label: "Tracker Detection", status: "warn", detail: "2 trackers: Google Analytics, Meta Pixel"},
    ],
    ai: [
      {
        color: "text-amber-400",
        text: "Cookie banner lacks a visible reject/decline button — users must be able to reject non-essential cookies as easily as accepting them (GDPR Art. 7)."
      },
      {
        color: "text-red-400",
        text: "No Impressum page found — legally required for commercial websites in DE/AT/CH under TMG §5. Must include company name, address, and contact info."
      },
      {
        color: "text-amber-400",
        text: "2 third-party trackers detected (Google Analytics, Meta Pixel) but not disclosed in the Privacy Policy — GDPR Art. 13 requires listing all data recipients."
      },
      {
        color: "text-green-400",
        text: "Content quality score: 82/100 — Privacy Policy and Terms of Service are well-structured and cover the required GDPR disclosure elements."
      },
    ],
    score: "B", scoreColor: "text-amber-400", issues: 3,
  },
  {
    domain: "acme.example",
    items: [
      {label: "SSL Certificate", status: "pass", detail: "Valid · 364 days remaining"},
      {label: "Privacy Policy", status: "pass", detail: "Found · Privacy Notice"},
      {label: "Terms of Service", status: "pass", detail: "Found · Terms and Conditions"},
      {label: "Impressum", status: "pass", detail: "Found · Legal Notice"},
      {label: "Cookie Policy", status: "pass", detail: "Dedicated cookie policy page"},
      {label: "GDPR Request", status: "pass", detail: "DSAR form found"},
      {label: "Cookie Banner", status: "pass", detail: "OneTrust CMP · accept & reject present"},
      {label: "Tracker Detection", status: "pass", detail: "No third-party trackers detected"},
    ],
    ai: [
      {
        color: "text-green-400",
        text: "All 8 compliance checks passed. Legal pages are accessible from the homepage footer. Cookie consent banner implements proper accept/reject controls with granular preferences."
      },
      {
        color: "text-green-400",
        text: "Content quality score: 94/100 — comprehensive data processing disclosures, clear legal basis documentation, and complete contact information."
      },
      {
        color: "text-green-400",
        text: "No undisclosed third-party trackers found — the site respects user privacy by default. Clean network profile."
      },
    ],
    score: "A+", scoreColor: "text-green-400", issues: 0,
  },
  {
    domain: "startup.example",
    items: [
      {label: "SSL Certificate", status: "warn", detail: "Expiring in 12 days!"},
      {label: "Privacy Policy", status: "fail", detail: "Not found on homepage"},
      {label: "Terms of Service", status: "fail", detail: "Not found on homepage"},
      {label: "Impressum", status: "fail", detail: "Not found"},
      {label: "Cookie Policy", status: "fail", detail: "Not found"},
      {label: "GDPR Request", status: "fail", detail: "No data request page"},
      {label: "Cookie Banner", status: "fail", detail: "No consent banner detected"},
      {label: "Tracker Detection", status: "fail", detail: "4 trackers found · undisclosed"},
    ],
    ai: [
      {
        color: "text-red-400",
        text: "Critical: No Privacy Policy found — this is a mandatory requirement under GDPR Art. 13. Your website must disclose what personal data is collected and how it is processed."
      },
      {
        color: "text-red-400",
        text: "No cookie consent banner detected — 4 third-party trackers are active (Google Analytics, Facebook Pixel, Hotjar, Intercom) without any user consent mechanism."
      },
      {
        color: "text-amber-400",
        text: "SSL certificate expires in 12 days — renew immediately to avoid browser security warnings that will block visitors from accessing your site."
      },
      {
        color: "text-red-400",
        text: "Content quality score: 0/100 — no legal documents found. This site is at high risk of GDPR enforcement action."
      },
    ],
    score: "F", scoreColor: "text-red-400", issues: 7,
  },
  {
    domain: "legal-firm.example",
    items: [
      {label: "SSL Certificate", status: "pass", detail: "Valid · 89 days remaining"},
      {label: "Privacy Policy", status: "pass", detail: "Politique de Confidentialité"},
      {label: "Terms of Service", status: "pass", detail: "Conditions Générales d'Utilisation"},
      {label: "Impressum", status: "pass", detail: "Mentions Légales found"},
      {label: "Cookie Policy", status: "pass", detail: "Politique de Cookies"},
      {label: "GDPR Request", status: "warn", detail: "Contact form only — no dedicated page"},
      {label: "Cookie Banner", status: "pass", detail: "Cookiebot CMP · fully compliant"},
      {label: "Tracker Detection", status: "pass", detail: "1 tracker: Matomo (self-hosted)"},
    ],
    ai: [
      {
        color: "text-green-400",
        text: "Excellent compliance posture — all core legal documents found in both French and English. Cookie consent banner uses Cookiebot with proper granular controls."
      },
      {
        color: "text-amber-400",
        text: "GDPR data subject request: only a generic contact form was found. Consider adding a dedicated DSAR page with clear instructions on exercising data rights (access, erasure, portability)."
      },
      {
        color: "text-green-400",
        text: "Matomo analytics is self-hosted — no data leaves your servers. This is a privacy-friendly choice. Content quality score: 91/100."
      },
    ],
    score: "A-", scoreColor: "text-green-400", issues: 1,
  },
];

function ScannerAnimation() {
  const [caseIdx, setCaseIdx] = useState(0);
  const [phase, setPhase] = useState(0);
  // 0-5: items, 6: AI header, 7: AI dots, 8: AI results, 9: score, 10: pause 10s

  const c = SCAN_CASES[caseIdx];

  useEffect(() => {
    // 0-7: scan items (8 monitors), 8: AI header, 9: AI analyzing, 10: AI results, 11: score, 12: pause
    const delays: Record<number, number> = {
      0: 600, 1: 500, 2: 500, 3: 500, 4: 500, 5: 500, 6: 600, 7: 600,
      8: 400, 9: 2000, 10: 1000, 11: 800,
    };
    const delay = phase >= 12 ? 10000 : (delays[phase] ?? 700);
    const timer = setTimeout(() => {
      if (phase >= 12) {
        setCaseIdx(prev => (prev + 1) % SCAN_CASES.length);
        setPhase(0);
      } else {
        setPhase(prev => prev + 1);
      }
    }, delay);
    return () => clearTimeout(timer);
  }, [phase]);

  const aiActive = phase >= 8;
  const aiAnalyzing = phase === 9;
  const aiDone = phase >= 10;
  const showScore = phase >= 11;

  return (
      <div
          className="mx-auto mt-12 w-full max-w-2xl min-h-[540px] rounded-2xl border border-slate-800 bg-slate-900/80 p-5 sm:p-6 backdrop-blur-sm flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between mb-4 pb-3 border-b border-slate-800">
          <div className="flex items-center gap-2">
            <div
                className={`h-2 w-2 rounded-full shrink-0 ${showScore ? (c.issues === 0 ? "bg-green-400" : "bg-amber-400") : "bg-green-400 animate-pulse"}`}/>
            <span className="text-xs font-mono text-slate-400">
            {showScore ? "Scan complete" : `Scanning ${c.domain}...`}
          </span>
          </div>
          {showScore && (
              <span className="text-[10px] font-mono text-slate-500 shrink-0">8 checks · 2.1s</span>
          )}
        </div>

        {/* Scan items */}
          <div className="space-y-0.5 mb-auto">
          {c.items.map((item, i) => {
            const visible = i < phase;
            return (
                <div
                    key={item.label}
                    className={`flex items-center gap-2.5 py-2 transition-all duration-500 ${visible ? "opacity-100 translate-x-0" : "opacity-0 translate-x-3"}`}
                >
              <span className={`h-2 w-2 shrink-0 rounded-full ${
                  item.status === "pass" ? "bg-green-400" : item.status === "warn" ? "bg-amber-400" : "bg-red-400"
              }`}/>
                    <span className="text-xs sm:text-sm text-slate-200 whitespace-nowrap shrink-0">{item.label}</span>
                  <span
                      className="text-[11px] sm:text-xs text-slate-500 flex-1 truncate text-right">{visible ? item.detail : ""}</span>
                    <span className={`text-xs font-mono font-semibold shrink-0 w-12 text-right ${
                      item.status === "pass" ? "text-green-400" :
                          item.status === "warn" ? "text-amber-400" : "text-red-400"
                  }`}>
                {visible ? (item.status === "pass" ? "PASS" : item.status === "warn" ? "WARN" : "FAIL") : ""}
              </span>
                </div>
            );
          })}
        </div>

        {/* AI Analysis */}
        <div
            className={`pt-3 border-t border-slate-800 transition-all duration-500 ${aiActive ? "opacity-100" : "opacity-0 h-0 overflow-hidden"}`}>
          <div className="flex items-center gap-2 mb-2">
            <div
                className="flex h-5 w-5 items-center justify-center rounded-md bg-amber-500/15 ring-1 ring-amber-500/25">
              <Bot className="h-3 w-3 text-amber-400"/>
            </div>
            <span className="text-xs font-semibold text-amber-400">AI Analysis</span>
            {aiAnalyzing && (
                <span className="ml-auto flex items-center gap-1 text-[10px] text-amber-400/60">
              <span className="h-1 w-1 rounded-full bg-amber-400 animate-pulse"/>
              <span className="h-1 w-1 rounded-full bg-amber-400 animate-pulse" style={{animationDelay: "200ms"}}/>
              <span className="h-1 w-1 rounded-full bg-amber-400 animate-pulse" style={{animationDelay: "400ms"}}/>
              <span className="ml-1">analyzing</span>
            </span>
            )}
          </div>
          {aiDone && (
              <div className="space-y-2.5">
                {c.ai.map((a, i) => (
                    <p key={i} className="text-xs leading-relaxed text-slate-400">
                      <span className={a.color}>→</span> {a.text}
                    </p>
                ))}
              </div>
          )}
        </div>

        {/* Score */}
        <div
            className={`pt-3 mt-3 border-t border-slate-800 transition-all duration-500 ${showScore ? "opacity-100" : "opacity-0"}`}>
          <div className="flex items-center justify-between">
            <span className="text-xs text-slate-400">Compliance Score</span>
            <div className="flex items-center gap-2">
              <span className={`text-lg font-bold ${c.scoreColor}`}>{c.score}</span>
              {c.issues > 0 && (
                  <span
                      className="rounded-full bg-amber-500/10 px-2 py-0.5 text-[10px] font-medium text-amber-400 ring-1 ring-amber-500/20">
                {c.issues} {c.issues === 1 ? "issue" : "issues"}
              </span>
              )}
              {c.issues === 0 && (
                  <span
                      className="rounded-full bg-green-500/10 px-2 py-0.5 text-[10px] font-medium text-green-400 ring-1 ring-green-500/20">
                All clear
              </span>
              )}
            </div>
          </div>
          <a href="/register" className="block mt-2 text-[11px] text-sky-400 hover:text-sky-300">
            {c.issues > 0 ? "Create an account to fix these issues with step-by-step guidance →" : "Create an account to keep your compliance monitored 24/7 →"}
          </a>
        </div>
      </div>
  );
}

function FloatingOrbs() {
  // Hidden on mobile — heavy blur + animation causes compositor lag
  // and visible scroll jank on mobile GPUs.
  return (
      <div className="pointer-events-none absolute inset-0 overflow-hidden hidden md:block">
        <div className="absolute -top-40 -right-40 h-80 w-80 rounded-full bg-sky-500/10 blur-3xl animate-float-slow"/>
        <div
            className="absolute top-1/2 -left-20 h-60 w-60 rounded-full bg-purple-500/10 blur-3xl animate-float-slower"/>
        <div
            className="absolute -bottom-20 right-1/4 h-40 w-40 rounded-full bg-emerald-500/8 blur-3xl animate-float-medium"/>
      </div>
  );
}

function SectionDivider() {
  return <div className="h-px bg-gradient-to-r from-transparent via-slate-700 to-transparent"/>;
}

// ─── Feature Cards ─────────────────────────────────────────────────────────────

const features = [
  {
    icon: Shield,
    title: "SSL Monitoring",
    desc: "Get alerts before your SSL certificate expires. Never let a lapsed cert take your site offline.",
  },
  {
    icon: FileText,
    title: "Legal Document Detection",
    desc: "Automatically finds Privacy Policy, Terms of Service, Impressum, and more across your site.",
  },
  {
    icon: Eye,
    title: "Cookie Banner Compliance",
    desc: "Verifies GDPR-compliant consent banners with working accept and reject options.",
  },
  {
    icon: Bot,
    title: "AI Content Analysis",
    desc: "AI-powered quality scoring of your legal documents ensures they meet regulatory standards.",
  },
  {
    icon: Camera,
    title: "Screenshot Evidence",
    desc: "Visual proof of compliance status timestamped and stored for audit trails.",
  },
  {
    icon: Globe,
    title: "Multi-Region Support",
    desc: "13 regions with native-language keyword detection for localised compliance checking.",
  },
  {
    icon: ShieldCheck,
    title: "Status Override",
    desc: "Manually accept scan results you consider valid. Future scans with the same result inherit your decision.",
  },
];

// ─── Security Audit monitors (verified-only) ──────────────────────

const securityScans = [
  {icon: ShieldCheck, name: "Security Headers", desc: "CSP, HSTS, X-Frame-Options and friends — audit the full browser-hardening set."},
  {icon: FileWarning, name: "Exposed Files",    desc: "Probes .env, .git, backup archives and 20+ other leak-prone paths."},
  {icon: Lock,        name: "TLS Grade",        desc: "Protocol version, cipher suite and certificate chain — SSL Labs-style grade."},
  {icon: Mail,        name: "DNS Hygiene",      desc: "SPF, DMARC, DKIM and CAA records — email-spoofing and misissuance exposure."},
  {icon: Network,     name: "Open Ports",       desc: "TCP probe of FTP, SSH, database and alt-HTTP ports that shouldn't be public."},
  {icon: Code2,       name: "JS Library CVE",   desc: "Scans third-party scripts against known-vulnerable versions (jQuery, Bootstrap, Lodash and more)."},
  {icon: Fingerprint, name: "Typosquat Watch",  desc: "Permutes your domain and flags registered look-alikes that could be used for phishing."},
];

// ─── Page ──────────────────────────────────────────────────────────────────────

const LANDING_JSON_LD = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "Organization",
      "@id": `${SITE_URL}/#organization`,
      name: SITE_NAME,
      url: SITE_URL,
      logo: `${SITE_URL}/icon.svg`,
    },
    {
      "@type": "SoftwareApplication",
      "@id": `${SITE_URL}/#software`,
      name: SITE_NAME,
      url: SITE_URL,
      applicationCategory: "SecurityApplication",
      operatingSystem: "Web",
      description: SITE_DESCRIPTION,
      offers: {
        "@type": "Offer",
        price: "0",
        priceCurrency: "USD",
      },
      publisher: {"@id": `${SITE_URL}/#organization`},
    },
  ],
};

export default function LandingPage() {
  return (
      <div className="dark min-h-screen bg-slate-950 text-slate-200" style={{scrollBehavior: "smooth"}}>
        <script
          type="application/ld+json"
          dangerouslySetInnerHTML={{__html: JSON.stringify(LANDING_JSON_LD)}}
        />
        <SiteHeader />

        {/* ── Hero ──────────────────────────────────────────────────────────── */}
        <section className="relative overflow-hidden pt-32 pb-24 px-4 sm:px-6 lg:px-8">
          <FloatingOrbs/>
          {/* Radial glow */}
          <div
              aria-hidden
              className="pointer-events-none absolute inset-0 hidden md:flex items-start justify-center"
          >
            <div className="h-[600px] w-[900px] rounded-full bg-sky-500/10 blur-[120px] -translate-y-1/4"/>
          </div>

          <div className="relative mx-auto max-w-4xl text-center">
            {/* Badge */}
            <div
                className="inline-flex items-center gap-2 rounded-full border border-sky-500/30 bg-sky-500/10 px-4 py-1.5 text-xs font-medium text-sky-400 mb-8">
              <span className="h-1.5 w-1.5 rounded-full bg-sky-400 animate-pulse"/>
              24/7 SSL + GDPR compliance monitoring
            </div>

            <h1 className="text-4xl sm:text-5xl lg:text-6xl font-bold text-white tracking-tight leading-tight">
              Monitor SSL + GDPR compliance for{" "}
              <span className="bg-gradient-to-r from-sky-400 to-blue-500 bg-clip-text text-transparent">
                every website you own
              </span>
            </h1>

            <p className="mt-6 text-lg sm:text-xl text-slate-400 max-w-2xl mx-auto leading-relaxed">
              SSL expiry, privacy policy, Impressum, cookie banners — checked
              24/7 across every site you own. Try a free check or start the
              full account in one click.
            </p>

            <div className="mt-10 flex flex-col sm:flex-row items-center justify-center gap-4">
              <Link
                href="/register"
                className="flex items-center gap-2 rounded-xl bg-sky-500 hover:bg-sky-400 px-7 py-3.5 text-base font-semibold text-white transition-colors shadow-lg shadow-sky-500/20"
              >
                Get started
                <ArrowRight className="h-4 w-4" />
              </Link>
              <Link
                href="/tools"
                className="flex items-center gap-2 rounded-xl border border-slate-700 hover:border-slate-600 bg-slate-800/50 hover:bg-slate-800 px-7 py-3.5 text-base font-medium text-slate-300 transition-colors"
              >
                Try a free tool
                <ChevronRight className="h-4 w-4" />
              </Link>
            </div>

            <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
              {TOOLS.map((tool) => (
                <Link
                  key={tool.slug}
                  href={`/tools/${tool.slug}`}
                  className="rounded-full border border-slate-700/60 bg-slate-900/50 px-3.5 py-1.5 text-xs font-medium text-slate-300 hover:border-sky-500/60 hover:text-white transition-colors"
                >
                  {tool.shortLabel}
                </Link>
              ))}
            </div>
            <p className="mt-3 text-xs text-slate-500">No signup needed for the free tools.</p>

              <div className="mt-12">
                  <ScannerAnimation/>
              </div>
          </div>
        </section>

        <SectionDivider/>

        {/* ── Features ──────────────────────────────────────────────────────── */}
        <section id="features" className="py-24 px-4 sm:px-6 lg:px-8 border-t border-slate-800/60">
          <div className="mx-auto max-w-7xl">
            <div className="text-center mb-16">
              <p className="text-sm font-medium text-sky-400 uppercase tracking-widest mb-3">
                Features
              </p>
              <h2 className="text-3xl sm:text-4xl font-bold text-white">
                Everything you need for compliance
              </h2>
              <p className="mt-4 text-slate-400 max-w-xl mx-auto">
                One platform to monitor all aspects of your website's legal and security compliance.
              </p>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6">
              {features.map(({icon: Icon, title, desc}, index) => (
                  <FadeIn key={title} delay={index * 100}>
                    <div
                        className="rounded-2xl border border-slate-800 bg-slate-900/50 p-6 hover:border-slate-700 hover:bg-slate-900 transition-all group h-full">
                      <div
                          className="flex h-11 w-11 items-center justify-center rounded-xl bg-sky-500/10 border border-sky-500/20 mb-4 group-hover:bg-sky-500/15 transition-colors">
                        <Icon className="h-5 w-5 text-sky-400"/>
                      </div>
                      <h3 className="text-base font-semibold text-slate-100 mb-2">{title}</h3>
                      <p className="text-sm text-slate-400 leading-relaxed">{desc}</p>
                    </div>
                  </FadeIn>
              ))}
            </div>
          </div>
        </section>

        <SectionDivider/>

        {/* ── Security Audit (verified-only) ────────────────────────────────── */}
        <section id="security-audit" className="py-24 px-4 sm:px-6 lg:px-8 bg-gradient-to-b from-slate-900/40 via-slate-950 to-slate-950 border-t border-slate-800/60">
          <div className="mx-auto max-w-6xl">
            <div className="text-center mb-14">
              <p className="text-sm font-medium text-amber-400 uppercase tracking-widest mb-3 flex items-center justify-center gap-2">
                <ShieldCheck className="h-4 w-4" /> Security Audit
              </p>
              <h2 className="text-3xl sm:text-4xl font-bold text-white">
                Unlock 7 security checks when you verify ownership
              </h2>
              <p className="mt-4 text-slate-400 max-w-2xl mx-auto">
                Verified domain owners get a dedicated audit suite on top of compliance monitoring —
                owner-triggered checks gated by DNS-TXT ownership proof so no one can point them at a
                site they don't control.
              </p>
              <div className="mt-5 inline-flex items-center gap-2 rounded-full border border-amber-500/30 bg-amber-500/5 px-3 py-1 text-[11px] text-amber-300">
                <Lock className="h-3 w-3" /> Verified-only · DNS-TXT ownership check
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
              {securityScans.map(({icon: Icon, name, desc}, index) => (
                <FadeIn key={name} delay={index * 80}>
                  <div className="h-full rounded-2xl border border-slate-800 bg-slate-900/60 p-5 hover:border-amber-500/30 hover:bg-slate-900 transition-colors">
                    <div className="flex items-center justify-between mb-3">
                      <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-amber-500/10 ring-1 ring-amber-500/20">
                        <Icon className="h-4.5 w-4.5 text-amber-400" />
                      </div>
                    </div>
                    <h3 className="text-base font-semibold text-slate-100 mb-1.5">{name}</h3>
                    <p className="text-sm text-slate-400 leading-relaxed">{desc}</p>
                  </div>
                </FadeIn>
              ))}
            </div>

            <div className="mt-12 grid grid-cols-1 md:grid-cols-3 gap-4 text-sm">
              <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-4">
                <p className="font-semibold text-white mb-1 flex items-center gap-2">
                  <CheckCircle2 className="h-4 w-4 text-emerald-400" /> Ownership-proof gate
                </p>
                <p className="text-slate-400 text-xs leading-relaxed">
                  Every scan requires an active DNS-TXT verification on the domain. No way to weaponise it against a third party.
                </p>
              </div>
              <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-4">
                <p className="font-semibold text-white mb-1 flex items-center gap-2">
                  <Lock className="h-4 w-4 text-amber-400" /> Owner-triggered only
                </p>
                <p className="text-slate-400 text-xs leading-relaxed">
                  These checks never run on a schedule — they go off only when the verified owner clicks Run scan.
                </p>
              </div>
              <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-4">
                <p className="font-semibold text-white mb-1 flex items-center gap-2">
                  <Shield className="h-4 w-4 text-sky-400" /> Open and auditable
                </p>
                <p className="text-slate-400 text-xs leading-relaxed">
                  Every check is available to every user, and the source code is public so you can read exactly what runs against your domain.
                </p>
              </div>
            </div>
          </div>
        </section>

        <SectionDivider/>

        {/* ── How It Works ──────────────────────────────────────────────────── */}
        <section
            id="how-it-works"
            className="py-24 px-4 sm:px-6 lg:px-8 bg-slate-900/30 border-t border-slate-800/60"
        >
          <div className="mx-auto max-w-5xl">
            <div className="text-center mb-16">
              <p className="text-sm font-medium text-sky-400 uppercase tracking-widest mb-3">
                How It Works
              </p>
              <h2 className="text-3xl sm:text-4xl font-bold text-white">
                Up and running in minutes
              </h2>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-8 relative">
              {/* Connector line (desktop only) */}
              <div
                  className="hidden md:block absolute top-8 left-[calc(16.66%+1rem)] right-[calc(16.66%+1rem)] h-px bg-gradient-to-r from-transparent via-slate-700 to-transparent"/>

              {[
                {
                  num: "01",
                  title: "Add your website",
                  desc: "Enter your URL and select your target region. We handle the rest.",
                  icon: Globe,
                },
                {
                  num: "02",
                  title: "We scan automatically",
                  desc: "Our scanners check SSL, legal pages, and cookie consent every hour.",
                  icon: Zap,
                },
                {
                  num: "03",
                  title: "Get actionable insights",
                  desc: "See compliance status, fix issues, and download evidence reports.",
                  icon: BarChart3,
                },
              ].map(({num, title, desc, icon: Icon}, index) => (
                  <FadeIn key={num} delay={index * 150}>
                    <div className="flex flex-col items-center text-center">
                      <div
                          className="relative flex h-16 w-16 items-center justify-center rounded-2xl bg-slate-900 border border-slate-700 mb-5 z-10">
                        <Icon className="h-7 w-7 text-sky-400"/>
                        <span
                            className="absolute -top-2.5 -right-2.5 flex h-6 w-6 items-center justify-center rounded-full bg-sky-500 text-[10px] font-bold text-white">
                      {num.slice(1)}
                    </span>
                      </div>
                      <h3 className="text-base font-semibold text-slate-100 mb-2">{title}</h3>
                      <p className="text-sm text-slate-400 leading-relaxed">{desc}</p>
                    </div>
                  </FadeIn>
              ))}
            </div>
          </div>
        </section>

        <SectionDivider/>

        {/* ── Trust Stats ───────────────────────────────────────────────────── */}
        <section className="py-20 px-4 sm:px-6 lg:px-8 bg-slate-900/30 border-t border-slate-800/60">
          <div className="mx-auto max-w-5xl">
            <p className="text-center text-sm text-slate-500 mb-12 uppercase tracking-widest font-medium">
              Built for GDPR, TMG and ePrivacy checks
            </p>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-8">
              {[
                {icon: Globe, label: "Regions", value: "13"},
                {icon: Clock, label: "Monitoring", value: "24/7"},
                {icon: Bot, label: "AI-Powered", value: "100%"},
                {icon: ShieldCheck, label: "GDPR Ready", value: "✓"},
              ].map(({icon: Icon, label, value}, index) => (
                  <FadeIn key={label} delay={index * 100}>
                    <div className="flex flex-col items-center gap-3">
                      <div
                          className="flex h-12 w-12 items-center justify-center rounded-2xl bg-slate-800 border border-slate-700">
                        <Icon className="h-5 w-5 text-sky-400"/>
                      </div>
                      <p className="text-2xl font-bold text-white">{value}</p>
                      <p className="text-sm text-slate-500">{label}</p>
                    </div>
                  </FadeIn>
              ))}
            </div>
          </div>
        </section>

        <SectionDivider/>

        {/* ── CTA Banner ────────────────────────────────────────────────────── */}
        <section className="py-24 px-4 sm:px-6 lg:px-8 border-t border-slate-800/60 relative overflow-hidden">
          <div
              aria-hidden
              className="pointer-events-none absolute inset-0 hidden md:flex items-center justify-center"
          >
            <div className="h-[500px] w-[800px] rounded-full bg-sky-600/10 blur-[100px]"/>
          </div>
          <FadeIn>
            <div className="relative mx-auto max-w-3xl text-center">
              <h2 className="text-3xl sm:text-4xl font-bold text-white mb-4">
                Ready to secure your compliance?
              </h2>
              <p className="text-lg text-slate-400 mb-10">
                nabu-watch is free and open source (Apache-2.0). Deploy it on your own server with Docker Compose.
              </p>
              <Link
                  href="/register"
                  className="inline-flex items-center gap-2 rounded-xl bg-sky-500 hover:bg-sky-400 px-8 py-4 text-base font-semibold text-white transition-colors shadow-lg shadow-sky-500/25"
              >
                Get Started
                <ArrowRight className="h-4 w-4"/>
              </Link>
              <p className="mt-6 text-sm text-slate-500">
                Source code and self-hosting guide on{" "}
                <a
                    href="https://github.com/SNebiyev/nabu-watch"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-sky-400 hover:text-sky-300 underline underline-offset-2"
                >
                  GitHub
                </a>
              </p>
            </div>
          </FadeIn>
        </section>

        <FaqSection />

        <SiteFooter />

        <ScrollToTop/>
      </div>
  );
}

function ScrollToTop() {
  const [show, setShow] = useState(false);

  useEffect(() => {
    const onScroll = () => setShow(window.scrollY > 400);
    window.addEventListener("scroll", onScroll, {passive: true});
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
      <button
          onClick={() => window.scrollTo({top: 0, behavior: "smooth"})}
          aria-label="Scroll to top"
          className={`fixed bottom-6 right-6 z-50 flex h-10 w-10 items-center justify-center rounded-full border border-slate-700 bg-slate-800/90 text-slate-300 shadow-lg backdrop-blur-sm transition-all duration-300 hover:bg-sky-500 hover:text-white hover:border-sky-400 hover:shadow-sky-500/20 ${
              show ? "opacity-100 translate-y-0" : "opacity-0 translate-y-4 pointer-events-none"
          }`}
      >
        <ArrowUp className="h-4 w-4"/>
      </button>
  );
}

// ─── FAQ ──────────────────────────────────────────────────────────────────────

const FAQS: Array<{q: string; a: string}> = [
  {
    q: "Do you store the websites I scan with the free tools?",
    a: "No. The free tools accept a URL, run the scan, return the result, and only keep it briefly (about 15 minutes) so duplicate scans can be skipped. No account is created and no email is sent based on tool usage.",
  },
  {
    q: "What's the difference between the free tools and an account?",
    a: "The tools run a single one-shot check per request — no JavaScript rendering, no AI. An account adds continuous 24/7 monitoring across all your sites, real-browser scans that handle JavaScript-rendered pages, AI policy review against GDPR / TMG, and email + Slack alerts when something breaks.",
  },
  {
    q: "Which regions and laws do you cover?",
    a: "GDPR (EU/EEA), Germany TMG §5 (Impressum), UK GDPR + PECR, Switzerland nDSG, and ePrivacy variants across DE / FR / ES / IT / NL / PL. SSL checks are region-agnostic.",
  },
  {
    q: "How do you scan cookie banners?",
    a: "Free tool: a static fingerprint scan against the 12 most common consent platforms (OneTrust, Cookiebot, Usercentrics, Iubenda, etc.). Monitoring loads the page in a real browser, accept and reject the banner programmatically, and verify that no third-party cookies are dropped before consent.",
  },
  {
    q: "Where is the data stored?",
    a: "Data stays on the server you deploy nabu-watch to. Nothing leaves it except the optional integrations you configure yourself (SMTP, AI providers, webhooks).",
  },
  {
    q: "Can I export findings or hook them into my own tooling?",
    a: "Yes — outbound webhooks fire for every scan result and status change, so you can feed compliance data into Linear, Slack, PagerDuty, or your own dashboard.",
  },
  {
    q: "How accurate is the AI policy review?",
    a: "The AI is a guide, not a lawyer. It flags clauses that are missing or vague against the relevant statute and points you to the exact GDPR / TMG article. We always recommend running material findings past your privacy counsel before publishing changes.",
  },
];

const FAQ_JSON_LD = {
  "@context": "https://schema.org",
  "@type": "FAQPage",
  mainEntity: FAQS.map((f) => ({
    "@type": "Question",
    name: f.q,
    acceptedAnswer: {"@type": "Answer", text: f.a},
  })),
};

function FaqSection() {
  return (
    <section id="faq" className="py-24 px-4 sm:px-6 lg:px-8 border-t border-slate-800/60">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{__html: JSON.stringify(FAQ_JSON_LD)}}
      />
      <div className="mx-auto max-w-3xl">
        <div className="text-center mb-12">
          <p className="text-sm font-medium text-sky-400 uppercase tracking-widest mb-3">FAQ</p>
          <h2 className="text-3xl sm:text-4xl font-bold text-white">
            Common questions
          </h2>
        </div>

        <div className="space-y-3">
          {FAQS.map((f) => (
            <details
              key={f.q}
              className="group rounded-xl border border-slate-800 bg-slate-900/50 open:border-slate-700"
            >
              <summary className="flex cursor-pointer items-center justify-between gap-4 px-5 py-4 text-left">
                <span className="text-sm sm:text-base font-medium text-white">{f.q}</span>
                <span className="text-xs text-slate-500 group-open:rotate-180 transition-transform">▼</span>
              </summary>
              <p className="px-5 pb-5 text-sm text-slate-400 leading-relaxed">{f.a}</p>
            </details>
          ))}
        </div>
      </div>
    </section>
  );
}
