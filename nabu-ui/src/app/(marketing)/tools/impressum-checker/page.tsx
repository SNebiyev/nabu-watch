import type {Metadata} from "next";
import {Suspense} from "react";
import {LegalLinkCheckForm} from "@/components/tools/LegalLinkCheckForm";

export const metadata: Metadata = {
  title: "Free Impressum Checker",
  description:
    "Instantly check whether a website links to its Impressum / legal notice — required by German TMG §5 and similar EU rules. Free, no signup.",
  alternates: {canonical: "/tools/impressum-checker"},
  openGraph: {
    title: "Free Impressum Checker — nabu-watch",
    url: "/tools/impressum-checker",
  },
};

const JSON_LD = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Impressum Checker",
  applicationCategory: "BusinessApplication",
  operatingSystem: "Web",
  offers: {"@type": "Offer", price: "0", priceCurrency: "USD"},
};

export default function ImpressumCheckerPage() {
  return (
    <>
      <script type="application/ld+json" dangerouslySetInnerHTML={{__html: JSON.stringify(JSON_LD)}} />
      <main className="mx-auto max-w-3xl px-4 sm:px-6 lg:px-8 pt-32 pb-16">
        <div className="text-center mb-10">
          <p className="text-sm font-medium text-sky-400 uppercase tracking-widest mb-3">Free tool</p>
          <h1 className="text-3xl sm:text-4xl font-bold text-white tracking-tight">
            Impressum / legal notice checker
          </h1>
          <p className="mt-4 text-slate-400 max-w-xl mx-auto">
            German TMG §5 and equivalent EU rules require every commercial
            website to link to its legal notice. We scan the homepage for the
            link in seconds — no signup needed.
          </p>
        </div>

        <Suspense fallback={null}>
          <LegalLinkCheckForm endpoint="/api/v1/tools/impressum-checker" ctaLabel="Check Impressum" />
        </Suspense>
      </main>
    </>
  );
}
