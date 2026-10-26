/**
 * Turn a `Response` from a `/api/v1/tools/*` endpoint into a clean,
 * user-readable string. The backend emits Spring's default error envelope:
 *   { timestamp, status, error, message?, path }
 * — we surface `message` (or `error`) and translate well-known statuses.
 */
export async function readToolError(res: Response): Promise<string> {
  let payload: unknown = null;
  try {
    payload = await res.json();
  } catch {
    /* non-JSON body */
  }

  const fromBody = pickMessage(payload);

  if (res.status === 400) return fromBody ?? "Invalid URL";
  if (res.status === 403) return fromBody ?? "Bot challenge failed — please retry";
  if (res.status === 429) return fromBody ?? "Too many requests — please try again later";
  if (res.status >= 500) return "Server error — please try again";
  return fromBody ?? `Request failed (HTTP ${res.status})`;
}

function pickMessage(payload: unknown): string | null {
  if (!payload || typeof payload !== "object") return null;
  const obj = payload as Record<string, unknown>;
  for (const key of ["message", "detail", "error"]) {
    const v = obj[key];
    if (typeof v === "string" && v.trim().length > 0) return v;
  }
  return null;
}
