import type {Metadata} from "next";
import {Suspense} from "react";
import {GdprScanForm} from "./GdprScanForm";

export const metadata: Metadata = {
  title: "Free GDPR Compliance Scan",
  description:
    "One-shot GDPR readiness check: SSL, Impressum, privacy policy and cookie banner — combined into a single 0–100 score. Free, no signup.",
  alternates: {canonical: "/tools/gdpr-scan"},
  openGraph: {title: "Free GDPR Compliance Scan — nabu-watch", url: "/tools/gdpr-scan"},
};

const JSON_LD = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "GDPR Compliance Scan",
  applicationCategory: "BusinessApplication",
  operatingSystem: "Web",
  offers: {"@type": "Offer", price: "0", priceCurrency: "USD"},
};

export default function GdprScanPage() {
  return (
    <>
      <script type="application/ld+json" dangerouslySetInnerHTML={{__html: JSON.stringify(JSON_LD)}} />
      <main className="mx-auto max-w-3xl px-4 sm:px-6 lg:px-8 pt-32 pb-16">
        <div className="text-center mb-10">
          <p className="text-sm font-medium text-sky-400 uppercase tracking-widest mb-3">Free tool</p>
          <h1 className="text-3xl sm:text-4xl font-bold text-white tracking-tight">
            GDPR readiness scan
          </h1>
          <p className="mt-4 text-slate-400 max-w-xl mx-auto">
            Bundles SSL, Impressum, privacy policy and cookie-banner checks
            into a single 0–100 score with per-check verdicts. No signup needed. This is a
            one-shot static check; full monitoring runs from your dashboard.
          </p>
        </div>

        <Suspense fallback={null}><GdprScanForm /></Suspense>
      </main>
    </>
  );
}
