export const SITE_URL =
  process.env.NEXT_PUBLIC_SITE_URL?.replace(/\/$/, "") || "http://localhost:3000";

export const SITE_NAME = process.env.NEXT_PUBLIC_SITE_NAME || "nabu-watch";

// Contact addresses shown in the UI. Empty means the related link is not rendered.
export const SUPPORT_EMAIL = process.env.NEXT_PUBLIC_SUPPORT_EMAIL ?? "";
export const ABUSE_EMAIL = process.env.NEXT_PUBLIC_ABUSE_EMAIL || SUPPORT_EMAIL;

export const SITE_DESCRIPTION =
  "Continuous SSL, GDPR, and security compliance monitoring for your websites — across regions, with instant alerts.";

export const SITE_TAGLINE = "Website security & compliance monitor";

export const SOCIAL_TWITTER = "@nabudotwatch";

// Cloudflare Turnstile site key for public scan tools. Empty in local dev,
// populated in production. When empty the widget is skipped and the backend
// likewise skips verification (spring.tools.turnstile.enabled=false).
export const TURNSTILE_SITE_KEY =
  process.env.NEXT_PUBLIC_TURNSTILE_SITE_KEY ?? "";

export const SENTRY_DSN = process.env.NEXT_PUBLIC_SENTRY_DSN ?? "";

export const SENTRY_ENVIRONMENT =
  process.env.NEXT_PUBLIC_SENTRY_ENVIRONMENT ?? process.env.NODE_ENV;

export const UMAMI_HOST = process.env.NEXT_PUBLIC_UMAMI_HOST ?? "";

// Umami website UUID (Umami dashboard → website settings). Empty disables tracking.
export const UMAMI_WEBSITE_ID = process.env.NEXT_PUBLIC_UMAMI_WEBSITE_ID ?? "";

// Routes excluded from Umami analytics. Add new private areas here.
export const UMAMI_EXCLUDED_ROUTES = [
  "/dashboard",
  "/admin",
  "/monitoring",
] as const;
