# Multi-stage Dockerfile for Kinetix-Sentry Backend on Render
FROM node:20-alpine AS builder

WORKDIR /app

# Copy root and package dependency manifests
COPY package*.json ./
COPY packages/types/package*.json ./packages/types/
COPY packages/protocol/package*.json ./packages/protocol/
COPY packages/validation/package*.json ./packages/validation/
COPY packages/crypto/package*.json ./packages/crypto/
COPY backend/package*.json ./backend/

# Install dependencies across all workspaces
RUN npm ci

# Copy source code
COPY packages/ ./packages/
COPY backend/ ./backend/
COPY tsconfig.json ./

# Build all packages & backend
RUN npm run build

# Production Runner
FROM node:20-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production
ENV PORT=10000

COPY package*.json ./
COPY packages/ ./packages/
COPY backend/package*.json ./backend/
COPY --from=builder /app/packages/ ./packages/
COPY --from=builder /app/backend/dist ./backend/dist

# Install production node_modules
RUN npm ci --omit=dev

EXPOSE 10000

CMD ["node", "backend/dist/index.js"]
