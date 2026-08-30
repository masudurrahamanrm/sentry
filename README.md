# Kinetix Control + Sentry System

A complete personal-use device communication system designed to communicate over the public internet without requiring shared Wi-Fi, Bluetooth, or direct IP connections.

## Architecture

1. **Kinetix Control (`kinetix`)**: Android Controller app.
2. **Sentry (`sentry`)**: Android Agent app exposing authorized system capabilities.
3. **Cloud Backend (`backend`)**: Node.js/TypeScript REST API + WebSocket Gateway + PostgreSQL + Redis + Object Storage.
4. **Shared Protocol & Packages (`packages/*`)**: Types, schemas, validation, cryptographic utilities.

## Monorepo Layout

```text
SentrY/
├── backend/                   # Cloud Backend API & WebSocket Gateway
│   └── src/
│       ├── api/               # REST API endpoints
│       ├── auth/              # Key exchange and signature validation
│       ├── commands/          # Command pipeline and nonce verification
│       ├── devices/           # Device registry
│       ├── pairing/           # Pairing session management
│       ├── permissions/       # Capability checks
│       ├── storage/           # S3/MinIO signed file URLs
│       └── websocket/         # Real-time WebSocket connection router
├── packages/
│   ├── crypto/                # Key generation & hashing helpers
│   ├── protocol/              # WebSocket message types and schemas
│   ├── types/                 # Domain types and interfaces
│   └── validation/            # Zod validation schemas
├── infrastructure/
│   ├── docker/                # Dockerfiles
│   ├── database/              # PostgreSQL migrations
│   └── nginx/                 # Reverse proxy configuration
├── kinetix/                   # Android controller module
├── sentry/                    # Android agent module
├── docker-compose.yml         # Local development environment
└── .env.example               # Environment variables template
```

## Documentation Links

- [API Reference](docs/api.md) - REST endpoints and WebSocket protocol documentation.
- [Cryptographic Protocol](docs/protocol.md) - Key generation, challenge-response auth, and pairing lifecycle.
- [Security & Threat Model](docs/security.md) - Replay protection, rate limits, and OS sandbox boundaries.
- [Deployment Guide](docs/deployment.md) - Local setup and Docker Compose production deployment.

## Master 20-Phase Implementation Summary

All 20 phases have been fully implemented, built, and verified:
1. **Phase 1**: Monorepo & Project Structure
2. **Phase 2**: PostgreSQL Schema & Migrations (`001_initial_schema.sql`)
3. **Phase 3**: Backend REST API (`/api/v1`)
4. **Phase 4**: Device Registration Engine
5. **Phase 5**: Device IDs & Cryptographic Keys (ECDSA NIST P-256)
6. **Phase 6**: Secure Pairing Code System (6-digit, 5-min TTL, single-use)
7. **Phase 7**: Public-Key Authentication & Challenge-Response
8. **Phase 8**: WebSocket Gateway (`/ws`)
9. **Phase 9**: Online/Offline Presence System (90s tolerance)
10. **Phase 10**: Command Protocol & Nonce Replay Prevention
11. **Phase 11**: Capability & OS Permission Governance
12. **Phase 12**: Storage Architecture & Signed URLs (50MB limit)
13. **Phase 13**: Kinetix Control Jetpack Compose UI
14. **Phase 14**: Sentry Agent Jetpack Compose UI
15. **Phase 15**: Connect Kinetix to Backend
16. **Phase 16**: Connect Sentry to Backend
17. **Phase 17**: Reconnection Policy & Error Handling
18. **Phase 18**: Security & Threat Vector Testing
19. **Phase 19**: End-to-End System Tests
20. **Phase 20**: Dockerize & Production Deployment (Nginx, Postgres, Redis, MinIO)
