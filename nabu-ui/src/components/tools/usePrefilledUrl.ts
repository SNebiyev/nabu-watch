"use client";

import {useState} from "react";
import {useSearchParams} from "next/navigation";

/**
 * Initial value for tool form URL inputs. Reads `?url=` from the current
 * route so the landing-page hero widget can hand a URL to the matching tool
 * without forcing the visitor to retype it.
 */
export function usePrefilledUrl(): [string, (next: string) => void] {
  const params = useSearchParams();
  const [url, setUrl] = useState(() => params?.get("url")?.trim() ?? "");
  return [url, setUrl];
}
