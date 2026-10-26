import type {Metadata} from "next";
import Link from "next/link";
import {ArrowRight, Cookie, FileText, Gauge, ScrollText, ShieldCheck} from "lucide-react";
import {TOOLS} from "@/lib/toolsCatalog";

export const metadata: Metadata = {
  title: "Free Compliance & Security Tools",
  description:
    "Free, no-signup tools for SSL, GDPR, Impressum and privacy-policy compliance — instant checks for any website.",
  alternates: {canonical: "/tools"},
  openGraph: {title: "Free Tools — nabu-watch", url: "/tools"},
};

const TOOL_ICONS: Record<string, React.ReactNode> = {
  "ssl-check": <ShieldCheck className="h-5 w-5 text-sky-400" />,
  "impressum-checker": <ScrollText className="h-5 w-5 text-amber-400" />,
  "privacy-policy-check": <FileText className="h-5 w-5 text-emerald-400" />,
  "cookie-banner-checker": <Cookie className="h-5 w-5 text-rose-400" />,
  "gdpr-scan": <Gauge className="h-5 w-5 text-purple-400" />,
};

export default function ToolsIndexPage() {
  return (
    <main className="mx-auto max-w-5xl px-4 sm:px-6 lg:px-8 pt-32 pb-20">
      <div className="text-center mb-12">
        <p className="text-sm font-medium text-sky-400 uppercase tracking-widest mb-3">Free tools</p>
        <h1 className="text-3xl sm:text-4xl font-bold text-white tracking-tight">
          One-shot compliance checks. No signup.
        </h1>
        <p className="mt-4 text-slate-400 max-w-2xl mx-auto">
          Each tool runs a single instant scan against any URL. For continuous
          monitoring across all your domains with email + Slack alerts,
          {" "}<Link href="/register" className="text-sky-400 hover:text-sky-300">create a free account</Link>.
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 items-stretch">
        {TOOLS.map((tool) => (
          <Link
            key={tool.slug}
            href={`/tools/${tool.slug}`}
            className="group rounded-2xl border border-slate-800 bg-slate-900/50 p-5 hover:border-sky-500/60 hover:bg-slate-900 transition-colors"
          >
            <div className="flex items-center gap-2.5 mb-3">
              {TOOL_ICONS[tool.slug]}
              <p className="text-sm font-semibold text-white">{tool.title}</p>
            </div>
            <p className="text-sm text-slate-400">{tool.blurb}</p>
            <span className="mt-4 inline-flex items-center gap-1.5 text-xs font-medium text-sky-400 group-hover:text-sky-300">
              Open tool
              <ArrowRight className="h-3.5 w-3.5" />
            </span>
          </Link>
        ))}
      </div>
    </main>
  );
}
