# Cryptographic Pairing & Authentication Protocol

The Kinetix Control + Sentry ecosystem operates entirely without passwords, emails, or social logins, replacing them with hardware-backed asymmetric cryptography and single-use pairing codes.

---

## 1. Asymmetric Cryptography & Identity

- **Algorithm**: ECDSA NIST P-256 (`prime256v1`) with SHA-256 hashing.
- **Hardware Backed**: Mobile private keys are generated inside the Android KeyStore (`AndroidKeyStore`), ensuring private keys never touch memory in plaintext and cannot be extracted.
- **Monospace Device IDs**:
  - `SN-XXXX-XXXX`: Sentry Agent installations
  - `KX-XXXX-XXXX`: Kinetix Controller installations
  - Uses random Base32 alphabet (`23456789ABCDEFGHJKLMNPQRSTUVWXYZ`) omitting ambiguous characters (0, O, 1, I).
  - Never collects or uses persistent hardware identifiers (IMEI, MAC, serial number, advertising IDs).

---

## 2. Pairing Protocol Flow

```text
Kinetix Control                   Backend Gateway                   Sentry Agent
       │                                 │                                 │
       │ 1. POST /pairing/start          │                                 │
       ├────────────────────────────────►│ (Generates 6-digit code,        │
       │                                 │  hashes with SHA-256,           │
       │                                 │  stores with 5-min TTL)         │
       │◄────────────────────────────────┤                                 │
       │    { sessionId, code }          │                                 │
       │                                 │                                 │
       │ (User sees code on Kinetix      │                                 │
       │  and enters on Sentry screen)   │                                 │
       │                                 │ 2. Sentry signs:                │
       │                                 │    "${sessionId}:${code}:${id}" │
       │                                 │ 3. POST /pairing/confirm        │
       │                                 │◄────────────────────────────────┤
       │                                 │ (Verifies code hash, checks     │
       │                                 │  expiration, verifies ECDSA     │
       │                                 │  signature using public key)    │
       │                                 │                                 │
       │                                 │ 4. Commits active relationship  │
       │                                 │    to database.                 │
```

---

## 3. Challenge-Response Device Authentication

To establish session tokens for REST/WebSocket access without passwords:

1. Client requests challenge: `POST /api/v1/auth/challenge { deviceId }`.
2. Backend generates random 32-byte cryptographic nonce with 60-second expiration.
3. Client signs the raw nonce using its hardware-backed private key: `sign(privateKey, nonce)`.
4. Client submits signature: `POST /api/v1/auth/verify { challengeId, deviceId, signature }`.
5. Backend verifies signature against device's registered public key, consumes challenge immediately, and issues 24-hour device session JWT.

---

## 4. Replay & Tamper Protection

- **Nonce Tracking**: Every command carries a unique cryptographic nonce. Backend checks a 10-minute sliding window cache; repeated nonces are rejected (`409 REPLAY_ATTACK_DETECTED`).
- **Timestamp Freshness**: Requests with timestamps drifting by more than 60 seconds from server clock are rejected (`400 COMMAND_EXPIRED`).
