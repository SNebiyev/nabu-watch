import type {Metadata} from "next";
import Link from "next/link";
import {Suspense} from "react";
import {SslCheckForm} from "./SslCheckForm";

export const metadata: Metadata = {
  title: "Free SSL Certificate Checker",
  description:
    "Instantly check any website's SSL/TLS certificate — issuer, expiry, protocol version. Free, no signup, results in seconds.",
  alternates: {canonical: "/tools/ssl-check"},
  openGraph: {
    title: "Free SSL Certificate Checker — nabu-watch",
    url: "/tools/ssl-check",
  },
};

const JSON_LD = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "SSL Certificate Checker",
  applicationCategory: "SecurityApplication",
  operatingSystem: "Web",
  offers: {"@type": "Offer", price: "0", priceCurrency: "USD"},
};

export default function SslCheckPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{__html: JSON.stringify(JSON_LD)}}
      />
      <main className="mx-auto max-w-3xl px-4 sm:px-6 lg:px-8 pt-32 pb-16">
        <div className="text-center mb-10">
          <p className="text-sm font-medium text-sky-400 uppercase tracking-widest mb-3">
            Free tool
          </p>
          <h1 className="text-3xl sm:text-4xl font-bold text-white tracking-tight">
            SSL certificate checker
          </h1>
          <p className="mt-4 text-slate-400 max-w-xl mx-auto">
            Check any website&apos;s TLS certificate in seconds — issuer, expiry,
            protocol version. No signup. For continuous monitoring across all
            your domains, <Link className="text-sky-400 hover:text-sky-300" href="/register">create a free account</Link>.
          </p>
        </div>

        <Suspense fallback={null}><SslCheckForm /></Suspense>

        <section className="mt-16 grid gap-6 sm:grid-cols-3 text-sm">
          <Card title="Issuer + chain">
            Inspect the certificate authority and verify the chain validates
            against trusted roots.
          </Card>
          <Card title="Expiry alerts">
            We highlight certificates that expire in under 30 days — and warn
            critically inside 7 days.
          </Card>
          <Card title="Protocol version">
            See which TLS version was negotiated. Anything below TLS 1.2 is
            considered insecure.
          </Card>
        </section>
      </main>
    </>
  );
}

function Card({title, children}: {title: string; children: React.ReactNode}) {
  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-5">
      <p className="font-semibold text-white mb-1.5">{title}</p>
      <p className="text-slate-400">{children}</p>
    </div>
  );
}
