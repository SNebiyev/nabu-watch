# Responsible Use

## Purpose

nabu-watch audits websites for compliance signals: SSL/TLS, legal pages (privacy policy, terms,
imprint, cookie policy), cookie banners, and trackers. It is intended for auditing sites you own
or operate, or sites you are authorized or legally entitled to audit (for example regulators, data
protection officers, and researchers acting within their remit).

## Why the scanner fetches like a browser

Compliance auditing has to see a site the way a real visitor does. Many sites serve automated
clients a page without the cookie banner or trackers a visitor would get, so a plain HTTP client
can report false results. For that reason the worker (`nabu-worker/`) includes:

- TLS fingerprint impersonation (`utls_transport.go`, `tls_client.go`) so requests look like a
  mainstream browser at the TLS and HTTP/2 level.
- Browser-like request headers (`headers.go`).
- Headless Chrome via chromedp with stealth patches (`headless.go`, `stealth.go`).
- Optional FlareSolverr integration (`flaresolverr.go`), enabled only when `NABU_FLARESOLVERR_URL`
  is set.
- Optional per-region HTTP proxies you configure yourself (`NABU_PROXY_<REGION>`), used only as a
  last retry after direct fetches have been blocked.

Fetching escalates only when a lighter tier fails or returns a block page: static fetch first,
then headless Chrome, then the optional tiers above.

## robots.txt

Before scanning, the worker fetches the target's `robots.txt` (cached for 24 hours per domain) and
skips the scan if the path is disallowed for `User-agent: *`. This applies to the SSL and
compliance scanners. If `robots.txt` cannot be fetched, the worker allows the scan (fail-open).
The worker also limits concurrency (`NABU_MAX_CONCURRENT`, `NABU_MAX_CONCURRENT_CHROME`) and
enforces a per-scan timeout; scan frequency is set by your monitor schedules.

## What the tool deliberately does not do

- No CAPTCHA-solving services.
- No built-in residential proxy rotation or proxy purchasing. Proxies are only used if you
  configure them.
- No credential stuffing, brute forcing, or password guessing.
- No bypassing of logins or authentication; only publicly reachable pages are scanned.

## Your responsibility

You are responsible for complying with the laws that apply to you and with the terms of the sites
you scan. Stealth and fingerprinting features exist to obtain accurate audit results, not to
override a site owner's wishes. This document is not legal advice. The software is provided
"AS IS", without warranties, under the Apache License 2.0 (see `LICENSE`).

## Recommended operating defaults

- Keep robots.txt compliance enabled (it is on by default).
- Keep scan frequency modest; avoid scanning the same site more often than you need to.
- Keep concurrency limits at their defaults unless you run your own infrastructure.
- Scan only targets you are authorized to audit.
