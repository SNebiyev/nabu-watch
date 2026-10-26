# ─────────────────────────────────────────────
# Stage 1: Install dependencies
# ─────────────────────────────────────────────
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts

# ─────────────────────────────────────────────
# Stage 2: Build Next.js application
# ─────────────────────────────────────────────
FROM node:22-alpine AS builder
WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# API_URL is used at build time for server-side rewrites
ARG API_URL=http://nabu-api:8080
ENV API_URL=$API_URL

# Frontend env vars must be set at build time (NEXT_PUBLIC_*)
ARG NEXT_PUBLIC_API_URL=
ARG NEXT_PUBLIC_TURNSTILE_SITE_KEY=
ARG NEXT_PUBLIC_SITE_URL=
ARG NEXT_PUBLIC_SENTRY_DSN=
ARG NEXT_PUBLIC_SENTRY_ENVIRONMENT=production
ARG NEXT_PUBLIC_SUPPORT_EMAIL=
ARG NEXT_PUBLIC_ABUSE_EMAIL=
ARG NEXT_PUBLIC_SITE_NAME=
ARG NEXT_PUBLIC_UMAMI_HOST=
ARG NEXT_PUBLIC_UMAMI_WEBSITE_ID=
ARG SENTRY_ORG=
ARG SENTRY_PROJECT=
ARG SENTRY_AUTH_TOKEN=
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
ENV NEXT_PUBLIC_TURNSTILE_SITE_KEY=$NEXT_PUBLIC_TURNSTILE_SITE_KEY
ENV NEXT_PUBLIC_SITE_URL=$NEXT_PUBLIC_SITE_URL
ENV NEXT_PUBLIC_SENTRY_DSN=$NEXT_PUBLIC_SENTRY_DSN
ENV NEXT_PUBLIC_SENTRY_ENVIRONMENT=$NEXT_PUBLIC_SENTRY_ENVIRONMENT
ENV NEXT_PUBLIC_SUPPORT_EMAIL=$NEXT_PUBLIC_SUPPORT_EMAIL
ENV NEXT_PUBLIC_ABUSE_EMAIL=$NEXT_PUBLIC_ABUSE_EMAIL
ENV NEXT_PUBLIC_SITE_NAME=$NEXT_PUBLIC_SITE_NAME
ENV NEXT_PUBLIC_UMAMI_HOST=$NEXT_PUBLIC_UMAMI_HOST
ENV NEXT_PUBLIC_UMAMI_WEBSITE_ID=$NEXT_PUBLIC_UMAMI_WEBSITE_ID
ENV SENTRY_ORG=$SENTRY_ORG
ENV SENTRY_PROJECT=$SENTRY_PROJECT
ENV SENTRY_AUTH_TOKEN=$SENTRY_AUTH_TOKEN

RUN npm run build

# ─────────────────────────────────────────────
# Stage 3: Production runtime
# ─────────────────────────────────────────────
FROM node:22-alpine
WORKDIR /app

RUN addgroup -S nabu && adduser -S nabu -G nabu

# Copy standalone output (includes server.js + minimal node_modules)
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

RUN chown -R nabu:nabu /app
USER nabu

EXPOSE 3000
ENV HOSTNAME=0.0.0.0
ENV NODE_ENV=production

CMD ["node", "server.js"]
