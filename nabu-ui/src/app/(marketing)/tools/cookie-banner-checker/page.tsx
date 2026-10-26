import type {Metadata} from "next";
import {Suspense} from "react";
import {CookieBannerForm} from "./CookieBannerForm";

export const metadata: Metadata = {
  title: "Free Cookie Banner Checker",
  description:
    "Detect known consent-management platforms (OneTrust, Cookiebot, Usercentrics, Iubenda, …) on any website. Free, no signup.",
  alternates: {canonical: "/tools/cookie-banner-checker"},
  openGraph: {title: "Free Cookie Banner Checker — nabu-watch", url: "/tools/cookie-banner-checker"},
};

const JSON_LD = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Cookie Banner Checker",
  applicationCategory: "BusinessApplication",
  operatingSystem: "Web",
  offers: {"@type": "Offer", price: "0", priceCurrency: "USD"},
};

export default function CookieBannerCheckerPage() {
  return (
    <>
      <script type="application/ld+json" dangerouslySetInnerHTML={{__html: JSON.stringify(JSON_LD)}} />
      <main className="mx-auto max-w-3xl px-4 sm:px-6 lg:px-8 pt-32 pb-16">
        <div className="text-center mb-10">
          <p className="text-sm font-medium text-sky-400 uppercase tracking-widest mb-3">Free tool</p>
          <h1 className="text-3xl sm:text-4xl font-bold text-white tracking-tight">
            Cookie banner / CMP checker
          </h1>
          <p className="mt-4 text-slate-400 max-w-xl mx-auto">
            Detects whether the homepage embeds a known consent-management
            platform (OneTrust, Cookiebot, Usercentrics, Iubenda, TrustArc,
            Quantcast, Didomi, Klaro, CookieYes, Termly, Osano, Borlabs).
          </p>
          <p className="mt-3 text-xs text-slate-500 max-w-xl mx-auto">
            This static check doesn&apos;t run JavaScript. Single-page apps
            (React/Vue/Angular) often inject the banner client-side — for
            full headless-browser coverage and AI consent classification,
            use the signed-in scanner.
          </p>
        </div>

        <Suspense fallback={null}><CookieBannerForm /></Suspense>
      </main>
    </>
  );
}
