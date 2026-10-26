/**
 * Single source of truth for the public free-tool catalogue.
 *
 * Used by:
 *  - the /tools index page (rich cards),
 *  - the landing-page hero scan widget (URL launcher dropdown).
 *
 * Adding a tool: append an entry here, create the corresponding page
 * under app/(marketing)/tools/<slug>/, add a backend endpoint, and
 * include the path in src/app/sitemap.ts.
 */
export interface ToolCatalogEntry {
  slug: string;
  title: string;
  shortLabel: string;
  blurb: string;
}

export const TOOLS: ToolCatalogEntry[] = [
  {
    slug: "gdpr-scan",
    title: "GDPR readiness scan",
    shortLabel: "Full GDPR scan",
    blurb: "Composite 0–100 score that bundles all four checks below into one verdict per site.",
  },
  {
    slug: "ssl-check",
    title: "SSL certificate checker",
    shortLabel: "SSL certificate",
    blurb: "Inspect any site's TLS certificate — issuer, expiry, protocol version. Catches expiry inside 30 days.",
  },
  {
    slug: "cookie-banner-checker",
    title: "Cookie banner checker",
    shortLabel: "Cookie banner",
    blurb: "Detect known consent platforms (OneTrust, Cookiebot, Usercentrics, Iubenda…). Static check — no headless rendering.",
  },
  {
    slug: "impressum-checker",
    title: "Impressum / legal notice checker",
    shortLabel: "Impressum",
    blurb: "Verify the homepage links to its legal notice. Required by German TMG §5 and equivalent EU rules.",
  },
  {
    slug: "privacy-policy-check",
    title: "Privacy policy checker",
    shortLabel: "Privacy policy",
    blurb: "Check whether the site exposes a privacy policy link. Required by GDPR Art. 13 wherever you process personal data.",
  },
];
