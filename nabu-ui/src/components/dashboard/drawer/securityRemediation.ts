/**
 * Static remediation catalogue for the 7 security-audit scans. Keyed by a
 * fingerprint derived from the failing check/finding. Copy is deliberately
 * technology-agnostic: we don't know whether the target site runs behind a
 * specific reverse proxy, hosting platform, or framework, so every step is
 * phrased in general terms (e.g. "your server / hosting layer" rather than
 * a brand name).
 */

export interface RemediationEntry {
  /** Short one-liner shown as the section header. */
  title: string;
  /** Bullet list of concrete actions the owner can take. */
  steps: string[];
}

// ── SECURITY_HEADERS ─────────────────────────────────────────────────────────
// Key: "{headerLabel}:{status}" where status ∈ "fail" | "warn".

const SECURITY_HEADERS_MAP: Record<string, RemediationEntry> = {
  "HSTS:fail": {
    title: "Enable HSTS",
    steps: [
      "Add a Strict-Transport-Security response header on your hosting or reverse-proxy layer.",
      "Set max-age to at least six months (15552000 seconds) once you are confident every subdomain serves HTTPS.",
      "Include the `includeSubDomains` directive if the site does not rely on an unprotected subdomain.",
      "Consider `preload` and submit the domain to the HSTS preload list only after the config has been stable for weeks.",
    ],
  },
  "HSTS:warn": {
    title: "Increase HSTS max-age",
    steps: [
      "Raise max-age to at least 15552000 (six months). Short lifetimes defeat the protection against downgrade attacks.",
      "Keep `includeSubDomains` unless an HTTP-only subdomain is still in use.",
    ],
  },
  "Content-Security-Policy:fail": {
    title: "Add a Content Security Policy",
    steps: [
      "Define a baseline CSP: `default-src 'self'` plus explicit allow-lists for each third-party origin the site loads (scripts, styles, fonts, images).",
      "Prefer hashes or nonces to `'unsafe-inline'` for inline scripts; avoid `'unsafe-eval'`.",
      "Roll out via Content-Security-Policy-Report-Only first to surface violations without breaking users.",
      "Include `frame-ancestors 'self'` to deny framing from other origins (also satisfies X-Frame-Options).",
    ],
  },
  "Content-Security-Policy:warn": {
    title: "Tighten your Content Security Policy",
    steps: [
      "Remove wildcard (`*`) sources where possible and list exact origins.",
      "Replace `'unsafe-inline'` / `'unsafe-eval'` with nonces or hashes.",
      "Add `object-src 'none'` and `base-uri 'self'` to shut off common bypass vectors.",
    ],
  },
  "X-Content-Type-Options:fail": {
    title: "Set X-Content-Type-Options to nosniff",
    steps: [
      "Return `X-Content-Type-Options: nosniff` on every response.",
      "This prevents browsers from guessing the MIME type of a resource (a known XSS vector for user-uploaded files).",
    ],
  },
  "X-Content-Type-Options:warn": {
    title: "Fix the X-Content-Type-Options value",
    steps: [
      "The only valid value is `nosniff`. Any other string is ignored and leaves MIME-sniffing enabled.",
    ],
  },
  "X-Frame-Options / frame-ancestors:fail": {
    title: "Block framing",
    steps: [
      "Add a CSP directive `frame-ancestors 'self'` (or `'none'` if the site should never be framed).",
      "As a fallback for older browsers, also send `X-Frame-Options: SAMEORIGIN` (or `DENY`).",
      "Without either, the site is exposed to clickjacking: an attacker iframes your page and overlays invisible controls.",
    ],
  },
  "X-Frame-Options / frame-ancestors:warn": {
    title: "Strengthen the framing policy",
    steps: [
      "Prefer `DENY` or `SAMEORIGIN` unless the site is deliberately embedded elsewhere.",
      "Migrate to CSP `frame-ancestors` — it is the current standard and supersedes X-Frame-Options.",
    ],
  },
  "Referrer-Policy:fail": {
    title: "Set a Referrer-Policy",
    steps: [
      "Start with `strict-origin-when-cross-origin` — it keeps the full referrer for same-origin requests and only the origin for cross-origin ones.",
      "Use `no-referrer` for highly sensitive apps where even the origin should not leak.",
    ],
  },
  "Permissions-Policy:fail": {
    title: "Set a Permissions-Policy",
    steps: [
      "Start with a minimal deny list for features the site does not use: `camera=(), microphone=(), geolocation=(), interest-cohort=()`.",
      "Explicitly deny `payment=()` and `usb=()` unless the site needs them — otherwise third-party iframes can silently request them.",
    ],
  },
};

// ── EXPOSED_FILES ────────────────────────────────────────────────────────────
// Key: exact probe path.

const EXPOSED_FILES_MAP: Record<string, RemediationEntry> = {
  "/.env": {
    title: "Environment file is publicly served",
    steps: [
      "Remove the file from the web-accessible directory immediately.",
      "Rotate every secret it contained (API keys, DB passwords, signing keys) — assume it is already scraped.",
      "Configure your server to deny access to dotfiles by default.",
      "Review the git history to ensure the file has never been committed.",
    ],
  },
  "/.git/config": {
    title: ".git directory is publicly served",
    steps: [
      "Block all access to `/.git/` at your reverse-proxy or server layer.",
      "Attackers can reconstruct the full source tree and commit history — audit for leaked credentials, rotate anything sensitive.",
      "Prefer building deployment artefacts outside the git working tree instead of copying `.git` to the server.",
    ],
  },
  "/.git/HEAD": {
    title: ".git directory is publicly served",
    steps: [
      "Block all access to `/.git/` at your reverse-proxy or server layer.",
      "Attackers can reconstruct the full source tree and commit history — audit for leaked credentials, rotate anything sensitive.",
    ],
  },
  "/.DS_Store": {
    title: ".DS_Store leaks directory contents",
    steps: [
      "Delete the file from the server.",
      "Add `.DS_Store` to `.gitignore` and your deployment ignore list so it never ships again.",
      "It lists every filename in the directory — useful to attackers for discovery.",
    ],
  },
  "/wp-config.php.bak": {
    title: "Backup of configuration file is public",
    steps: [
      "Delete the backup immediately.",
      "Rotate the database password and any other credentials it contained.",
      "Block `*.bak`, `*~`, `*.old`, `*.swp` at the server layer so editor artefacts never leak.",
    ],
  },
  "/wp-config.php~": {
    title: "Backup of configuration file is public",
    steps: [
      "Delete the backup immediately.",
      "Rotate the database password and any other credentials it contained.",
      "Block `*.bak`, `*~`, `*.old`, `*.swp` at the server layer.",
    ],
  },
  "/config.php.bak": {
    title: "Backup of configuration file is public",
    steps: [
      "Delete the backup immediately.",
      "Rotate any credentials it contained.",
      "Block `*.bak`, `*~`, `*.old`, `*.swp` at the server layer.",
    ],
  },
  "/web.config.bak": {
    title: "Backup of configuration file is public",
    steps: [
      "Delete the backup immediately.",
      "Rotate any credentials it contained.",
      "Block `*.bak`, `*~`, `*.old`, `*.swp` at the server layer.",
    ],
  },
  "/phpinfo.php": {
    title: "phpinfo page is publicly accessible",
    steps: [
      "Delete the file.",
      "It discloses the interpreter version, loaded modules, environment variables, and full filesystem paths — all useful for exploit targeting.",
    ],
  },
  "/.htaccess": {
    title: "Server config file is readable",
    steps: [
      "Configure the web server to deny requests for `.htaccess` (and any equivalent access-control config file).",
      "Even when the directives still apply, exposing the file gives attackers a map of authentication rules and rewrite patterns.",
    ],
  },
  "/.htpasswd": {
    title: "Credentials file is readable",
    steps: [
      "Block access to `.htpasswd` immediately.",
      "Rotate every password listed — the hashes are now public.",
    ],
  },
  "/backup.zip": {
    title: "Backup archive is publicly downloadable",
    steps: [
      "Remove the archive from the web root.",
      "Store backups in offline / access-controlled storage, never on the public-facing filesystem.",
      "Rotate any credentials contained in the backup.",
    ],
  },
  "/backup.sql": {
    title: "Database dump is publicly downloadable",
    steps: [
      "Remove the dump from the web root immediately.",
      "Assume user data (PII, password hashes, session tokens) has been exfiltrated — treat this as a breach.",
      "Notify affected users if your jurisdiction requires it.",
      "Store backups in offline / access-controlled storage.",
    ],
  },
  "/backup.tar.gz": {
    title: "Backup archive is publicly downloadable",
    steps: [
      "Remove the archive from the web root.",
      "Store backups in offline / access-controlled storage.",
      "Rotate any credentials contained in the backup.",
    ],
  },
  "/dump.sql": {
    title: "Database dump is publicly downloadable",
    steps: [
      "Remove the dump from the web root immediately.",
      "Assume user data has been exfiltrated — treat this as a breach.",
      "Notify affected users if your jurisdiction requires it.",
    ],
  },
  "/docker-compose.yml": {
    title: "Container orchestration config is public",
    steps: [
      "Move the file outside the web root.",
      "It typically contains service names, internal ports, and sometimes secrets passed via environment variables — rotate anything sensitive.",
    ],
  },
  "/Dockerfile": {
    title: "Container build file is public",
    steps: [
      "Move the file outside the web root.",
      "It reveals base image, installed packages, and internal file layout — useful for targeting known-vulnerable versions.",
    ],
  },
  "/.npmrc": {
    title: "Package manager config is public",
    steps: [
      "Remove the file from the web root.",
      "If it contained an auth token, rotate it on your registry immediately.",
    ],
  },
  "/id_rsa": {
    title: "Private SSH key is publicly downloadable",
    steps: [
      "Rotate the key pair immediately — the private key is in the wild.",
      "Revoke the corresponding public key from every authorized_keys file and managed-access system.",
      "Delete the file from the server and audit how it ended up in the web root.",
      "Review access logs for any successful SSH logins using the compromised key.",
    ],
  },
  "/.vscode/sftp.json": {
    title: "IDE deployment config is public",
    steps: [
      "Remove the file from the web root.",
      "Rotate any credentials it contained (SFTP user, password, deployment tokens).",
      "Add `.vscode/` to deployment-ignore rules.",
    ],
  },
  "/server-status": {
    title: "Server status endpoint is public",
    steps: [
      "Restrict the status endpoint to localhost or an internal IP range.",
      "It exposes the full request workers' state — URLs, user agents, sometimes auth tokens in query strings.",
    ],
  },
};

// ── TLS_GRADE ────────────────────────────────────────────────────────────────
// Key: grade letter.

const TLS_GRADE_MAP: Record<string, RemediationEntry> = {
  "F": {
    title: "TLS configuration is unsafe",
    steps: [
      "Disable SSL 3.0, TLS 1.0 and TLS 1.1 on your server; require TLS 1.2 as a minimum and prefer TLS 1.3.",
      "Remove weak ciphers (RC4, 3DES, export-grade, NULL) from the allow-list. Prefer ECDHE + AEAD suites.",
      "Renew the certificate if it is expired or self-signed in production.",
      "Run the scan again after each change to confirm the grade improves.",
    ],
  },
  "D": {
    title: "TLS grade is weak",
    steps: [
      "Require TLS 1.2 or higher; disable anything older.",
      "Drop weak ciphers (RC4, 3DES) and enable forward secrecy with ECDHE key-exchange.",
    ],
  },
  "C": {
    title: "TLS grade can be improved",
    steps: [
      "Enable TLS 1.3 alongside 1.2 and disable older protocols.",
      "Prefer AEAD cipher suites (AES-GCM, ChaCha20-Poly1305).",
      "Enable OCSP stapling to keep revocation checks fast.",
    ],
  },
  "B": {
    title: "TLS grade is good — minor tuning",
    steps: [
      "Make sure TLS 1.3 is enabled.",
      "Confirm the certificate chain is complete and uses a modern signature algorithm.",
    ],
  },
};

// ── DNS_HYGIENE ──────────────────────────────────────────────────────────────
// Key: "{checkLabel}:{status}".

const DNS_HYGIENE_MAP: Record<string, RemediationEntry> = {
  "SPF:fail": {
    title: "Publish an SPF record",
    steps: [
      "Add a TXT record at the apex: `v=spf1 include:<your-mail-provider> -all`.",
      "If you send from multiple services, chain them: `v=spf1 include:a include:b -all`.",
      "Use `-all` (hard fail) once you are confident every legitimate sender is listed. `~all` (soft fail) is acceptable during rollout.",
      "Never use `+all` — it permits every host on the internet to send as your domain.",
    ],
  },
  "SPF:warn": {
    title: "Tighten the SPF policy",
    steps: [
      "Replace `?all` (neutral) or a missing `all` mechanism with `~all` or `-all` so receivers can reject unauthorised senders.",
      "Audit every `include:` — each external service should still be legitimately sending for the domain.",
    ],
  },
  "DMARC:fail": {
    title: "Publish a DMARC policy",
    steps: [
      "Add a TXT record at `_dmarc.{yourdomain}` starting with `v=DMARC1; p=none; rua=mailto:dmarc@yourdomain`.",
      "`p=none` lets you collect reports without affecting mail flow. Review reports for two–four weeks, then escalate to `p=quarantine` and finally `p=reject`.",
      "Without DMARC, receivers cannot act on SPF/DKIM failures — meaning attackers can still spoof you.",
    ],
  },
  "DMARC:warn": {
    title: "Escalate the DMARC policy",
    steps: [
      "Move from `p=none` (monitor-only) to `p=quarantine`, then `p=reject`, once aggregate reports show no legitimate senders failing alignment.",
      "Keep `rua=` reports flowing so you can react to new services starting to send mail.",
    ],
  },
  "DKIM:warn": {
    title: "Enable DKIM signing",
    steps: [
      "Generate a DKIM key-pair in your mail-sending service and publish the public key at `<selector>._domainkey.{yourdomain}` as a TXT record.",
      "Every transactional / marketing sender should sign with a dedicated selector so keys can be rotated independently.",
      "After DKIM + SPF are both in place, receivers can enforce DMARC alignment on every message.",
    ],
  },
  "CAA:warn": {
    title: "Publish CAA records",
    steps: [
      "Add CAA records listing the certificate authorities allowed to issue for the domain — for example `0 issue \"letsencrypt.org\"`.",
      "Add `0 iodef \"mailto:security@{yourdomain}\"` so any unauthorised issuance attempt is reported to you.",
      "Without CAA, any CA in the world may issue a certificate for the domain if it accepts a forged proof.",
    ],
  },
};

// ── OPEN_PORTS ───────────────────────────────────────────────────────────────
// Key: port number.

const OPEN_PORTS_MAP: Record<number, RemediationEntry> = {
  21: {
    title: "FTP port is open",
    steps: [
      "FTP transmits credentials and data in plaintext. Replace with SFTP or SCP if file transfer is still needed.",
      "Block port 21 at your firewall / cloud security group.",
    ],
  },
  22: {
    title: "SSH port is open to the internet",
    steps: [
      "If SSH is genuinely needed, require key-based authentication (disable password auth) and restrict inbound connections to a known management IP range.",
      "If SSH is not needed on the public interface, block port 22 at your firewall.",
      "Rotate any credentials that may have been brute-forced while the port was exposed.",
    ],
  },
  3306: {
    title: "MySQL database port is public",
    steps: [
      "Database ports must never be reachable from the public internet.",
      "Bind the database to localhost or a private network interface and block the port at your firewall.",
      "Check logs for unauthorised connection attempts; rotate the DB password if in doubt.",
    ],
  },
  5432: {
    title: "PostgreSQL database port is public",
    steps: [
      "Database ports must never be reachable from the public internet.",
      "Bind the database to localhost or a private network interface and block the port at your firewall.",
      "Check logs for unauthorised connection attempts; rotate the DB password if in doubt.",
    ],
  },
  6379: {
    title: "Cache / key-value store is publicly reachable",
    steps: [
      "This class of service often has no authentication by default — a public instance is a data-leak and remote-code-execution risk.",
      "Block the port at your firewall immediately.",
      "Bind the service to localhost or a private network; require a strong password.",
      "Audit the dataset for suspicious keys (often `backup`, `ready`, etc.) which indicate a ransom attack.",
    ],
  },
  8080: {
    title: "Alternate HTTP port is open",
    steps: [
      "If this port runs an admin console or internal tool, restrict it to a private network or close it.",
      "If it fronts public traffic, make sure HTTPS is enforced and the same security headers apply.",
    ],
  },
  9000: {
    title: "Application-server port is public",
    steps: [
      "This is typically an internal app-server or admin UI port and should not be reachable from the internet.",
      "Close the port at your firewall, or bind the service to localhost behind the reverse proxy.",
    ],
  },
};

// ── JS_LIBRARY_CVE ───────────────────────────────────────────────────────────
// Key: severity.

const JS_LIBRARY_CVE_MAP: Record<string, RemediationEntry> = {
  "HIGH": {
    title: "High-severity vulnerable dependency",
    steps: [
      "Upgrade the library to the latest patched release listed in its security advisory.",
      "If an immediate upgrade is not possible, check the advisory for a mitigation (disable the affected feature, add input sanitisation).",
      "After the upgrade, re-run the scan to confirm the version is no longer flagged.",
    ],
  },
  "MEDIUM": {
    title: "Vulnerable dependency",
    steps: [
      "Upgrade to the patched release called out in the advisory.",
      "Add dependency-audit tooling to CI so new vulnerabilities are surfaced at build time, not by an external scan.",
    ],
  },
  "LOW": {
    title: "Minor dependency issue",
    steps: [
      "Plan an upgrade in the next maintenance window; the impact is limited but staying current reduces long-term risk.",
    ],
  },
};

// ── TYPOSQUAT_WATCH ──────────────────────────────────────────────────────────

const TYPOSQUAT_GENERIC: RemediationEntry = {
  title: "Look-alike domains are registered",
  steps: [
    "Review each registered look-alike. If one is actively impersonating your brand, file a takedown with its registrar (abuse contact in WHOIS) or initiate a UDRP action.",
    "Register the most dangerous variants defensively — in particular the homoglyph variants and the one-character-off permutations on major TLDs.",
    "Warn support / finance teams to watch for emails from these domains; attackers often combine typosquatting with phishing.",
    "Subscribe to a brand-monitoring service so new registrations trigger an alert rather than being surfaced only at scan time.",
  ],
};

// ── Lookup API ───────────────────────────────────────────────────────────────

export type ScanKey =
  | "SECURITY_HEADERS"
  | "EXPOSED_FILES"
  | "TLS_GRADE"
  | "DNS_HYGIENE"
  | "OPEN_PORTS"
  | "JS_LIBRARY_CVE"
  | "TYPOSQUAT_WATCH";

/**
 * Fingerprint of a failing finding. Each scan uses a different subset of
 * fields — the dispatcher picks the right lookup based on {@code scan}.
 */
export interface Fingerprint {
  /** SECURITY_HEADERS / DNS_HYGIENE check label ("HSTS", "SPF", …) */
  label?: string;
  /** SECURITY_HEADERS / DNS_HYGIENE check status */
  status?: "pass" | "warn" | "fail";
  /** EXPOSED_FILES probe path */
  path?: string;
  /** TLS_GRADE letter grade */
  grade?: string;
  /** OPEN_PORTS port number */
  port?: number;
  /** JS_LIBRARY_CVE severity ("HIGH" / "MEDIUM" / "LOW") */
  severity?: string;
}

/**
 * Single source of truth: given a scan and a fingerprint, return the
 * matching static remediation or {@code null} when none applies (e.g. a
 * passing check or an A-grade TLS result).
 */
export function remediationFor(scan: ScanKey, fp: Fingerprint): RemediationEntry | null {
  switch (scan) {
    case "SECURITY_HEADERS":
      if (!fp.label || !fp.status || fp.status === "pass") return null;
      return SECURITY_HEADERS_MAP[`${fp.label}:${fp.status}`] ?? null;
    case "DNS_HYGIENE":
      if (!fp.label || !fp.status || fp.status === "pass") return null;
      return DNS_HYGIENE_MAP[`${fp.label}:${fp.status}`] ?? null;
    case "EXPOSED_FILES":
      if (!fp.path) return null;
      return EXPOSED_FILES_MAP[fp.path] ?? GENERIC_EXPOSED_FILE;
    case "TLS_GRADE":
      if (!fp.grade || fp.grade === "A" || fp.grade === "A+") return null;
      return TLS_GRADE_MAP[fp.grade] ?? null;
    case "OPEN_PORTS":
      if (typeof fp.port !== "number") return null;
      return OPEN_PORTS_MAP[fp.port] ?? genericOpenPort(fp.port);
    case "JS_LIBRARY_CVE":
      if (!fp.severity) return null;
      return JS_LIBRARY_CVE_MAP[fp.severity.toUpperCase()] ?? JS_LIBRARY_CVE_MAP.MEDIUM;
    case "TYPOSQUAT_WATCH":
      return TYPOSQUAT_GENERIC;
  }
}

const GENERIC_EXPOSED_FILE: RemediationEntry = {
  title: "Unexpected file exposed",
  steps: [
    "Remove the file from the web-accessible directory.",
    "Review how it ended up there — usually an editor backup, a leftover deployment artefact, or a committed secret.",
    "Add a rule at your reverse-proxy / server to block similar paths going forward.",
  ],
};

function genericOpenPort(port: number): RemediationEntry {
  return {
    title: `Port ${port} is open`,
    steps: [
      "If the service is intentionally public, make sure it enforces authentication and rate-limiting.",
      "Otherwise, close the port at your firewall or bind the service to a private interface.",
    ],
  };
}
