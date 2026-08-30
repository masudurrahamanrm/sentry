import request from 'supertest';
import { app } from '../src/index';
import { generateKeyPair, signPayload, hashPairingCode, generateNonce } from '@kinetix-sentry/crypto';

jest.mock('../src/database/db', () => ({
  query: jest.fn(),
  pool: { connect: jest.fn(), on: jest.fn() },
}));

import { query } from '../src/database/db';

const mockedQuery = query as jest.MockedFunction<typeof query>;

describe('Phase 18: Security & Threat Vector Automated Testing', () => {
  const genuineDeviceKeys = generateKeyPair();
  const attackerKeys = generateKeyPair();
  const victimDeviceId = 'SN-8B3C-4D5E';
  const pairingId = 'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d';

  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('Threat Vector 1: Device Identity Spoofing / Impersonation', () => {
    it('blocks an attacker from taking over an existing device ID with their own public key', async () => {
      mockedQuery.mockResolvedValueOnce({
        rows: [{ public_key: genuineDeviceKeys.publicKey, capabilities: {} }],
        rowCount: 1,
        command: 'SELECT',
        oid: 0,
        fields: [],
      });

      const res = await request(app)
        .post('/api/v1/devices/register')
        .send({
          deviceId: victimDeviceId,
          deviceName: 'Attacker Impersonator',
          platform: 'Android',
          osVersion: 'Android 15',
          appVersion: '1.0.0',
          publicKey: attackerKeys.publicKey, // Wrong key!
        });

      expect(res.status).toBe(409);
      expect(res.body.error.code).toBe('DEVICE_IDENTITY_CONFLICT');
    });
  });

  describe('Threat Vector 2: Challenge-Response Signature Forgery', () => {
    it('rejects authentication when signature is forged using an attacker private key', async () => {
      mockedQuery.mockResolvedValueOnce({
        rows: [{ public_key: genuineDeviceKeys.publicKey }],
        rowCount: 1,
        command: 'SELECT',
        oid: 0,
        fields: [],
      });

      const challengeRes = await request(app)
        .post('/api/v1/auth/challenge')
        .send({ deviceId: victimDeviceId });

      const { challengeId, nonce } = challengeRes.body.challenge;
      const forgedSignature = signPayload(attackerKeys.privateKey, nonce);

      mockedQuery.mockResolvedValueOnce({
        rows: [{ public_key: genuineDeviceKeys.publicKey, device_name: 'Victim Phone', platform: 'Android' }],
        rowCount: 1,
        command: 'SELECT',
        oid: 0,
        fields: [],
      });

      const verifyRes = await request(app)
        .post('/api/v1/auth/verify')
        .send({
          challengeId,
          deviceId: victimDeviceId,
          signature: forgedSignature,
        });

      expect(verifyRes.status).toBe(401);
      expect(verifyRes.body.error.code).toBe('AUTHENTICATION_FAILED');
    });
  });

  describe('Threat Vector 3: Pairing Code Brute Force Attack', () => {
    it('locks out pairing session after 3 consecutive invalid code attempts', async () => {
      const sessionId = '11112222-3333-4444-5555-666677778888';
      const agentDeviceId = 'SN-1111-2222';
      const realCode = '987654';
      const validSignature = 'valid_test_signature_32_characters_long';

      const mockSession = {
        id: sessionId,
        controller_device_id: 'KX-1111-2222',
        agent_device_id: agentDeviceId,
        pairing_code_hash: hashPairingCode(realCode),
        expires_at: new Date(Date.now() + 200000),
        status: 'PENDING',
      };

      // Mock session queries
      mockedQuery.mockResolvedValue({
        rows: [mockSession],
        rowCount: 1,
        command: 'SELECT',
        oid: 0,
        fields: [],
      });

      // Attempt 1: Failed
      const res1 = await request(app)
        .post('/api/v1/pairing/confirm')
        .send({ sessionId, pairingCode: '000001', agentDeviceId, signature: validSignature });
      expect(res1.status).toBe(400);

      // Attempt 2: Failed
      const res2 = await request(app)
        .post('/api/v1/pairing/confirm')
        .send({ sessionId, pairingCode: '000002', agentDeviceId, signature: validSignature });
      expect(res2.status).toBe(400);

      // Attempt 3: Failed
      const res3 = await request(app)
        .post('/api/v1/pairing/confirm')
        .send({ sessionId, pairingCode: '000003', agentDeviceId, signature: validSignature });
      expect(res3.status).toBe(400);

      // Attempt 4: Should be completely rate-limited / locked out
      const res4 = await request(app)
        .post('/api/v1/pairing/confirm')
        .send({ sessionId, pairingCode: realCode, agentDeviceId, signature: validSignature });
      expect(res4.status).toBe(429);
      expect(res4.body.error.code).toBe('RATE_LIMIT_EXCEEDED');
    });
  });

  describe('Threat Vector 4: Unauthorized Privilege Escalation', () => {
    it('blocks TAKE_PHOTO command when Camera permission is not granted on agent', async () => {
      mockedQuery.mockResolvedValueOnce({
        rows: [{ id: pairingId, status: 'ACTIVE', controller_device_id: 'KX-1', agent_device_id: victimDeviceId }],
        rowCount: 1,
        command: 'SELECT',
        oid: 0,
        fields: [],
      });

      mockedQuery.mockResolvedValueOnce({
        rows: [{ capabilities: { camera: false, location: false, battery: true } }],
        rowCount: 1,
        command: 'SELECT',
        oid: 0,
        fields: [],
      });

      const res = await request(app)
        .post('/api/v1/commands')
        .send({
          pairingId,
          commandType: 'TAKE_PHOTO',
          payload: {},
          nonce: generateNonce(),
          timestamp: Date.now(),
        });

      expect(res.status).toBe(403);
      expect(res.body.error.code).toBe('PERMISSION_REQUIRED');
    });
  });
});
