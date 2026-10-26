"use client";

import {useCallback, useEffect, useState} from "react";
import {apiClient} from "@/lib/apiClient";
import {DOMAIN_VERIFIED_EVENT, type DomainVerifiedDetail} from "./useVerifiedAt";

export type SecurityScanKey =
  | "SECURITY_HEADERS"
  | "EXPOSED_FILES"
  | "TLS_GRADE"
  | "DNS_HYGIENE"
  | "OPEN_PORTS"
  | "JS_LIBRARY_CVE"
  | "TYPOSQUAT_WATCH";

export type LockReason = "VERIFIED_REQUIRED" | "NOT_YET_AVAILABLE";

export interface SecurityFeature {
  scan: SecurityScanKey;
  enabled: boolean;
  lockReason: LockReason | null;
}

/**
 * Fetches the per-scan availability list for a website and keeps it
 * in sync with the verified-at event fired by {@code VerifyDomainCard}.
 * `undefined` until the first response resolves so callers can render a
 * neutral skeleton instead of a misleading "locked" state.
 */
export function useSecurityFeatures(websiteId: string) {
  const [features, setFeatures] = useState<SecurityFeature[] | undefined>(undefined);

  const load = useCallback(async () => {
    try {
      const res = await apiClient(`/api/v1/security/websites/${websiteId}/features`);
      if (!res.ok) {
        setFeatures([]);
        return;
      }
      setFeatures(await res.json());
    } catch {
      setFeatures([]);
    }
  }, [websiteId]);

  useEffect(() => {
    void load();
    const onVerified = (ev: Event) => {
      const detail = (ev as CustomEvent<DomainVerifiedDetail>).detail;
      if (detail?.websiteId === websiteId) {
        void load();
      }
    };
    window.addEventListener(DOMAIN_VERIFIED_EVENT, onVerified);
    return () => window.removeEventListener(DOMAIN_VERIFIED_EVENT, onVerified);
  }, [websiteId, load]);

  return features;
}
