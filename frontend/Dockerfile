# ---- deps ----
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
# npm ci when a lockfile exists (reproducible), npm install otherwise.
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app

# No NEXT_PUBLIC_* build arg: every API call runs server-side, so the image carries
# no environment-specific value and the same image runs anywhere.
ENV NEXT_TELEMETRY_DISABLED=1

COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ---- run ----
FROM node:22-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

RUN apk add --no-cache tzdata \
    && addgroup -g 1001 -S nodejs \
    && adduser -S nextjs -u 1001

# `output: standalone` emits a minimal server plus only the modules it actually uses.
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=build --chown=nextjs:nodejs /app/public ./public

USER nextjs
EXPOSE 3000

CMD ["node", "server.js"]
