import * as Sentry from "@sentry/nextjs";
import {SENTRY_DSN, SENTRY_ENVIRONMENT} from "@/lib/siteConfig";

export async function register() {
  if (!SENTRY_DSN) return;
  Sentry.init({
    dsn: SENTRY_DSN,
    environment: SENTRY_ENVIRONMENT,
    tracesSampleRate: 0,
    sendDefaultPii: false,
  });
}

export const onRequestError = Sentry.captureRequestError;
