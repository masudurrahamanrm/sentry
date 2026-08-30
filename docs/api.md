# REST & WebSocket API Documentation

The Kinetix Control + Sentry backend exposes versioned REST endpoints under `/api/v1` and an authenticated real-time WebSocket Gateway at `/ws`.

---

## Base URLs
- **REST API**: `http://<host>:<port>/api/v1`
- **WebSocket**: `ws://<host>:<port>/ws?token=<device-jwt>`

---

## Authentication (`/api/v1/auth`)

### 1. Request Challenge Nonce
- **Endpoint**: `POST /api/v1/auth/challenge`
- **Body**:
  ```json
  {
    "deviceId": "KX-1234-5678"
  }
  ```
- **Response** (`200 OK`):
  ```json
  {
    "challenge": {
      "challengeId": "chl_1780000000_a1b2c3d4",
      "nonce": "hex_crypto_nonce_32_bytes",
      "expiresInSeconds": 60
    }
  }
  ```

### 2. Verify Signature & Obtain Session Token
- **Endpoint**: `POST /api/v1/auth/verify`
- **Body**:
  ```json
  {
    "challengeId": "chl_1780000000_a1b2c3d4",
    "deviceId": "KX-1234-5678",
    "signature": "base64_ecdsa_signature_of_nonce"
  }
  ```
- **Response** (`200 OK`):
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsIn...",
    "expiresIn": 86400
  }
  ```

---

## Devices (`/api/v1/devices`)

### 1. Register or Update Device
- **Endpoint**: `POST /api/v1/devices/register`
- **Body**:
  ```json
  {
    "deviceId": "SN-7F42-K9P3",
    "deviceName": "My Android",
    "platform": "Android",
    "osVersion": "Android 15",
    "appVersion": "1.0.0",
    "publicKey": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----",
    "capabilities": {
      "camera": true,
      "location": false,
      "notifications": true,
      "files": true,
      "microphone": false,
      "battery": true
    }
  }
  ```
- **Response** (`201 Created`):
  ```json
  {
    "device": {
      "id": "uuid",
      "deviceId": "SN-7F42-K9P3",
      "deviceName": "My Android",
      "status": "ONLINE",
      "capabilities": { ... }
    }
  }
  ```

### 2. List Available Devices for Discovery
- **Endpoint**: `GET /api/v1/devices`

---

## Pairing (`/api/v1/pairing` and `/api/v1/pairings`)

### 1. Start Pairing Session
- **Endpoint**: `POST /api/v1/pairing/start`
- **Body**:
  ```json
  {
    "controllerDeviceId": "KX-1234-5678",
    "agentDeviceId": "SN-7F42-K9P3"
  }
  ```
- **Response** (`201 Created`):
  ```json
  {
    "session": {
      "sessionId": "session-uuid",
      "pairingCode": "482913",
      "expiresInSeconds": 300
    }
  }
  ```

### 2. Confirm Pairing Session
- **Endpoint**: `POST /api/v1/pairing/confirm`
- **Body**:
  ```json
  {
    "sessionId": "session-uuid",
    "pairingCode": "482913",
    "agentDeviceId": "SN-7F42-K9P3",
    "signature": "base64_signature_of_session_code_device"
  }
  ```

### 3. List Active Pairings
- **Endpoint**: `GET /api/v1/pairings?deviceId=KX-1234-5678`

### 4. Revoke / Unpair
- **Endpoint**: `DELETE /api/v1/pairings/:id`

---

## Commands (`/api/v1/commands`)

### 1. Dispatch Command
- **Endpoint**: `POST /api/v1/commands`
- **Body**:
  ```json
  {
    "pairingId": "pairing-uuid",
    "commandType": "GET_BATTERY",
    "payload": {},
    "nonce": "random_nonce_hex",
    "timestamp": 1780000000000
  }
  ```

---

## Storage & Signed URLs (`/api/v1/storage`)

### 1. Request Upload Signed URL
- **Endpoint**: `POST /api/v1/storage/upload-url`
- **Body**:
  ```json
  {
    "pairingId": "pairing-uuid",
    "filename": "photo.jpg",
    "fileSize": 2048576,
    "contentType": "image/jpeg"
  }
  ```
- **Response** (`201 Created`):
  ```json
  {
    "fileId": "file_1780000000_1234",
    "uploadUrl": "http://localhost:9000/sentry-files/...",
    "expiresInSeconds": 900
  }
  ```

---

## WebSocket Gateway Protocol (`/ws`)

Connect with: `ws://<host>:<port>/ws?token=<device-jwt>`

### Message Types:
- `HEARTBEAT` & `HEARTBEAT_ACK`
- `DEVICE_ONLINE` & `DEVICE_OFFLINE`
- `COMMAND_REQUEST` & `COMMAND_RESPONSE`
- `ERROR`
