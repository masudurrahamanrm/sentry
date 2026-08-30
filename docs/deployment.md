# Local Development & Production Deployment Guide

## 1. Local Development Setup

### Prerequisites
- Node.js >= 20
- Docker & Docker Compose
- JDK 17+ & Android SDK

### Steps
1. **Clone and setup configuration**:
   ```bash
   cp .env.example .env
   ```
2. **Install monorepo dependencies**:
   ```bash
   npm install
   ```
3. **Build all packages and backend**:
   ```bash
   npm run build
   ```
4. **Start PostgreSQL, Redis, and MinIO in Docker**:
   ```bash
   docker compose up -d postgres redis minio
   ```
5. **Run database migrations**:
   ```bash
   npx tsx backend/src/database/migrate.ts
   ```
6. **Start development server**:
   ```bash
   npm run dev:backend
   ```
7. **Run backend test suites**:
   ```bash
   npm test --workspace=@kinetix-sentry/backend
   ```

---

## 2. Production Deployment via Docker Compose

To deploy the entire backend stack with reverse proxy, PostgreSQL, Redis, and Object Storage:

```bash
docker compose up -d --build
```

### Services Started:
- **`nginx`** on port `80` (Reverse Proxy for REST `/api/v1` and WebSocket `/ws`)
- **`backend`** on port `4000` (Node.js API & WebSocket Gateway)
- **`postgres`** on port `5432` (Relational Database)
- **`redis`** on port `6379` (Presence Cache & PubSub)
- **`minio`** on port `9000` (S3 Object Storage) & `9001` (Console)
