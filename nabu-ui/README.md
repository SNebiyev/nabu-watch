# nabu-ui

Next.js 16 + React 19 + Tailwind 4 frontend for nabu-watch.

## Development

```bash
npm ci
npm run dev        # http://localhost:3000
```

The UI talks to the API through Next.js rewrites. `API_URL` (default `http://localhost:8080`) is the server-side
target; start the API first (see the root README).

## Environment

Variables prefixed `NEXT_PUBLIC_` are baked into the build, so rebuild after changing them. All are optional:

| Variable                         | Purpose                                                         |
|----------------------------------|-----------------------------------------------------------------|
| `NEXT_PUBLIC_SITE_URL`           | Public URL (canonical links, Open Graph). Default `http://localhost:3000` |
| `NEXT_PUBLIC_SITE_NAME`          | Product name in titles. Default `nabu-watch`                    |
| `NEXT_PUBLIC_SUPPORT_EMAIL`      | ADMIN account that receives in-app support messages             |
| `NEXT_PUBLIC_ABUSE_EMAIL`        | Contact shown to owners of paused websites                      |
| `NEXT_PUBLIC_TURNSTILE_SITE_KEY` | Cloudflare Turnstile site key for the public scan tools         |
| `NEXT_PUBLIC_UMAMI_HOST` / `NEXT_PUBLIC_UMAMI_WEBSITE_ID` | Opt-in Umami analytics (off when unset) |
| `NEXT_PUBLIC_SENTRY_DSN`         | Opt-in Sentry error reporting                                   |

## Checks

```bash
npx tsc --noEmit
npm run build
```
