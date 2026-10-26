import type {Metadata} from "next";
import {Suspense} from "react";
import {LegalLinkCheckForm} from "@/components/tools/LegalLinkCheckForm";

export const metadata: Metadata = {
  title: "Free Privacy Policy Checker",
  description:
    "Instantly check whether a website links to its privacy policy — required by GDPR Art. 13. Free, no signup.",
  alternates: {canonical: "/tools/privacy-policy-check"},
  openGraph: {
    title: "Free Privacy Policy Checker — nabu-watch",
    url: "/tools/privacy-policy-check",
  },
};

const JSON_LD = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Privacy Policy Checker",
  applicationCategory: "BusinessApplication",
  operatingSystem: "Web",
  offers: {"@type": "Offer", price: "0", priceCurrency: "USD"},
};

export default function PrivacyPolicyCheckerPage() {
  return (
    <>
      <script type="application/ld+json" dangerouslySetInnerHTML={{__html: JSON.stringify(JSON_LD)}} />
      <main className="mx-auto max-w-3xl px-4 sm:px-6 lg:px-8 pt-32 pb-16">
        <div className="text-center mb-10">
          <p className="text-sm font-medium text-sky-400 uppercase tracking-widest mb-3">Free tool</p>
          <h1 className="text-3xl sm:text-4xl font-bold text-white tracking-tight">
            Privacy policy checker
          </h1>
          <p className="mt-4 text-slate-400 max-w-xl mx-auto">
            GDPR Art. 13 requires every site that processes personal data to
            link to its privacy policy. We scan the homepage for the link in
            seconds — no signup needed.
          </p>
        </div>

        <Suspense fallback={null}>
          <LegalLinkCheckForm endpoint="/api/v1/tools/privacy-policy-check" ctaLabel="Check privacy policy" />
        </Suspense>
      </main>
    </>
  );
}
