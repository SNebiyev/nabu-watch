/**
 * Compliance Matrix API — thin wrappers around apiClient.
 *
 * Spring Boot REST contract:
 *   POST   /api/v1/admin/compliance/regions                              { code, name }
 *   GET    /api/v1/admin/compliance/regions
 *   PATCH  /api/v1/admin/compliance/regions/{id}                        { active }
 *   GET    /api/v1/admin/compliance/monitor-types
 *   GET    /api/v1/admin/compliance/regions/{id}/rules
 *   PUT    /api/v1/admin/compliance/regions/{id}/rules/{monitorTypeId}
 *   DELETE /api/v1/admin/compliance/regions/{id}/rules/{monitorTypeId}
 *   GET    /api/v1/admin/compliance/global-fallbacks
 *   PUT    /api/v1/admin/compliance/global-fallbacks/{monitorTypeId}    { keywords }
 */
import {apiClient} from "@/lib/apiClient";
import {extractErrorMessage} from "@/lib/utils";
import type {
  BannerKeywordGroup,
  BannerKeywordType,
  ComplianceMonitorType,
  ComplianceRegion,
  GlobalFallbackItem,
  RegionRuleDetail,
  UpsertRuleRequest,
} from "@/lib/complianceTypes";

const BASE = "/api/v1/admin/compliance";

// ── Regions ────────────────────────────────────────────────────────────────────

export async function createRegion(code: string, name: string): Promise<ComplianceRegion> {
  const res = await apiClient(`${BASE}/regions`, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({code, name}),
  });
  if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to create region"));
  return res.json();
}

export async function fetchRegions(): Promise<ComplianceRegion[]> {
  const res = await apiClient(`${BASE}/regions`);
  if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to load regions"));
  return res.json();
}

export async function patchRegionActive(regionId: string, active: boolean): Promise<ComplianceRegion> {
  const res = await apiClient(`${BASE}/regions/${regionId}`, {
    method: "PATCH",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({active}),
  });
  if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to update region"));
  return res.json();
}

// ── Monitor types ──────────────────────────────────────────────────────────────

export async function fetchMonitorTypes(): Promise<ComplianceMonitorType[]> {
  const res = await apiClient(`${BASE}/monitor-types`);
  if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to load monitor types"));
  return res.json();
}

// ── Region rules ───────────────────────────────────────────────────────────────

export async function fetchRegionRules(regionId: string): Promise<RegionRuleDetail[]> {
  const res = await apiClient(`${BASE}/regions/${regionId}/rules`);
  if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to load region rules"));
  return res.json();
}

export async function upsertRegionRule(
    regionId: string,
    monitorTypeId: string,
    body: UpsertRuleRequest
): Promise<RegionRuleDetail> {
  const res = await apiClient(`${BASE}/regions/${regionId}/rules/${monitorTypeId}`, {
    method: "PUT",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to save rule"));
  return res.json();
}

export async function deleteRegionRule(regionId: string, monitorTypeId: string): Promise<void> {
  const res = await apiClient(`${BASE}/regions/${regionId}/rules/${monitorTypeId}`, {
    method: "DELETE",
  });
  if (!res.ok && res.status !== 404)
    throw new Error(await extractErrorMessage(res, "Failed to remove rule"));
}

// ── Global fallbacks ───────────────────────────────────────────────────────────

export async function fetchGlobalFallbacks(): Promise<GlobalFallbackItem[]> {
  const res = await apiClient(`${BASE}/global-fallbacks`);
  if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to load global fallbacks"));
  return res.json();
}

export async function updateGlobalFallback(
    monitorTypeId: string,
    keywords: string[]
): Promise<GlobalFallbackItem> {
  const res = await apiClient(`${BASE}/global-fallbacks/${monitorTypeId}`, {
    method: "PUT",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({keywords}),
  });
  if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to update fallback keywords"));
  return res.json();
}

// ── Banner keywords ────────────────────────────────────────────────────────────
//
// Region-scoped keyword lists used by the Go worker's BannerScanner to
// recognize cookie banners and classify their buttons (Accept/Reject/Prefs).
// Always scoped to a region (GLOBAL is a real compliance_regions row).

export async function fetchBannerKeywordsForRegion(
    regionId: string
): Promise<BannerKeywordGroup[]> {
  const res = await apiClient(`${BASE}/regions/${regionId}/banner-keywords`);
  if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to load banner keywords"));
  return res.json();
}

export async function updateBannerKeywordGroup(
    regionId: string,
    keywordType: BannerKeywordType,
    keywords: string[]
): Promise<BannerKeywordGroup> {
  const res = await apiClient(`${BASE}/regions/${regionId}/banner-keywords/${keywordType}`, {
    method: "PUT",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({keywords}),
  });
  if (!res.ok) throw new Error(await extractErrorMessage(res, "Failed to update banner keywords"));
  return res.json();
}
