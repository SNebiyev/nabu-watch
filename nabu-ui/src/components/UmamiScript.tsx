"use client";

import Script from "next/script";
import {usePathname} from "next/navigation";
import {useEffect, useRef} from "react";
import {UMAMI_EXCLUDED_ROUTES, UMAMI_HOST, UMAMI_WEBSITE_ID} from "@/lib/siteConfig";

declare global {
  interface Window {
    umami?: {track: (...args: unknown[]) => void};
  }
}

function isExcluded(pathname: string): boolean {
  return UMAMI_EXCLUDED_ROUTES.some(
    (r) => pathname === r || pathname.startsWith(`${r}/`),
  );
}

// Umami's tracker has no built-in path exclusion, so we disable auto-tracking
// (data-auto-track="false") and record pageviews manually — skipping private
// areas (dashboard/admin/monitoring). onLoad covers the first view; the effect
// covers client-side route changes.
export function UmamiScript() {
  const pathname = usePathname();
  const loaded = useRef(false);

  useEffect(() => {
    if (!loaded.current) return; // first view handled by onLoad
    if (isExcluded(pathname)) return;
    window.umami?.track();
  }, [pathname]);

  if (!UMAMI_HOST || !UMAMI_WEBSITE_ID) return null;

  return (
    <Script
      strategy="afterInteractive"
      data-website-id={UMAMI_WEBSITE_ID}
      data-auto-track="false"
      src={`${UMAMI_HOST}/script.js`}
      onLoad={() => {
        loaded.current = true;
        if (!isExcluded(window.location.pathname)) window.umami?.track();
      }}
    />
  );
}
