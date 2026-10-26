"use client";

import {useEffect, useState} from "react";
import {apiClient} from "@/lib/apiClient";

interface VerificationStatus {
  verifiedAt: string | null;
}

/**
 * Event dispatched by {@code VerifyDomainCard} when a "Check now" call
 * transitions the site to VERIFIED. Sibling cards (security headers,
 * exposed files) listen and flip to the unlocked state without forcing
 * the user to navigate away and back.
 */
export const DOMAIN_VERIFIED_EVENT = "nabu:domain-verified";

export interface DomainVerifiedDetail {
  websiteId: string;
  verifiedAt: string;
}

/**
 * Shared fetch-and-subscribe hook for the verified-only scan cards.
 * `undefined` until the first fetch resolves (used to suppress the
 * "locked" hint during initial load), then the real ISO timestamp or
 * `null` when the site isn't verified.
 */
export function useVerifiedAt(websiteId: string) {
  const [verifiedAt, setVerifiedAt] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    apiClient(`/api/v1/websites/${websiteId}/verification`)
      .then(async (res) => {
        if (!res.ok) {
          if (!cancelled) setVerifiedAt(null);
          return;
        }
        const data: VerificationStatus = await res.json();
        if (!cancelled) setVerifiedAt(data.verifiedAt ?? null);
      })
      .catch(() => {
        if (!cancelled) setVerifiedAt(null);
      });

    const onVerified = (ev: Event) => {
      const detail = (ev as CustomEvent<DomainVerifiedDetail>).detail;
      if (detail?.websiteId === websiteId) {
        setVerifiedAt(detail.verifiedAt);
      }
    };
    window.addEventListener(DOMAIN_VERIFIED_EVENT, onVerified);

    return () => {
      cancelled = true;
      window.removeEventListener(DOMAIN_VERIFIED_EVENT, onVerified);
    };
  }, [websiteId]);

  return verifiedAt;
}
