import * as Sentry from "@sentry/nextjs";
import {SENTRY_DSN, SENTRY_ENVIRONMENT} from "@/lib/siteConfig";

if (SENTRY_DSN) {
  Sentry.init({
    dsn: SENTRY_DSN,
    environment: SENTRY_ENVIRONMENT,
    tracesSampleRate: 0,
    sendDefaultPii: false,
  });
}

export const onRouterTransitionStart = Sentry.captureRouterTransitionStart;
