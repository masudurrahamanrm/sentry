# Security & Threat Model

Kinetix Control + Sentry is designed with personal device security, user consent, and platform privacy at its core.

---

## 1. Core Security Principles

1. **No Sensitive Hardware Identifiers**:
   - The system NEVER collects or uses IMEI, SIM numbers, Wi-Fi/Bluetooth MAC addresses, hardware serial numbers, or advertising IDs.
2. **OS Sandbox & Permission Boundaries**:
   - Sentry operates strictly through official Android OS APIs (`ContextCompat.checkSelfPermission`).
   - Sentry never attempts to bypass OS permission dialogs, read private databases of other applications, secretly capture background telemetry, or defeat Android platform protections.
3. **Zero Plaintext Private Keys**:
   - Cryptographic keys are generated inside the Android KeyStore Hardware Security Module (TEE/SE) with non-exportable private key attributes.

---

## 2. Threat Vector Mitigations

| Threat Vector | Mitigation Strategy | Tested In |
|---|---|---|
| **Identity Theft / Key Hijacking** | Device ID registration rejects attempts to overwrite an existing device ID with a different public key (`409 DEVICE_IDENTITY_CONFLICT`). | `registration.test.ts`, `security.test.ts` |
| **Replay Attacks** | Every command and authentication challenge requires fresh timestamps (< 60s) and unique nonces validated against a sliding window cache. | `command_protocol.test.ts`, `security.test.ts` |
| **Brute Force Pairing Code Guessing** | 5-minute single-use pairing codes; 3 consecutive incorrect attempts permanently locks the session (`429 RATE_LIMIT_EXCEEDED`). | `pairing.test.ts`, `security.test.ts` |
| **Unauthorized Capability Execution** | Pre-execution checks verify that target capability is enabled before dispatching commands (`403 PERMISSION_REQUIRED`). | `permissions.test.ts`, `security.test.ts` |
| **Tampered Signatures** | All cryptographic challenges verify ECDSA P-256 signatures against registered public keys. | `auth.test.ts`, `crypto.test.ts` |
| **Oversized Payloads & Storage Abuse** | 50MB hard limit per file, short-lived signed S3/MinIO URLs (15 min TTL), and pairing-bound download authorization. | `storage.test.ts` |
