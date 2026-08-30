import request from 'supertest';
import { app } from '../src/index';
import { generateKeyPair, signPayload } from '@kinetix-sentry/crypto';

jest.mock('../src/database/db', () => ({
  query: jest.fn(),
  pool: {
    connect: jest.fn(),
    on: jest.fn(),
  },
}));

import { query } from '../src/database/db';

const mockedQuery = query as jest.MockedFunction<typeof query>;

describe('Phase 7: Public-Key Challenge-Response Authentication', () => {
  const keyPair = generateKeyPair();
  const deviceId = 'SN-7F42-K9P3';

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('issues a cryptographic challenge with nonce for a registered device', async () => {
    mockedQuery.mockResolvedValueOnce({
      rows: [{ public_key: keyPair.publicKey }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const res = await request(app)
      .post('/api/v1/auth/challenge')
      .send({ deviceId });

    expect(res.status).toBe(200);
    expect(res.body.challenge.challengeId).toBeDefined();
    expect(res.body.challenge.nonce).toBeDefined();
    expect(res.body.challenge.expiresInSeconds).toBe(60);
  });

  it('verifies valid ECDSA signature on nonce and returns JWT session token', async () => {
    // 1. Get challenge
    mockedQuery.mockResolvedValueOnce({
      rows: [{ public_key: keyPair.publicKey }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const challengeRes = await request(app)
      .post('/api/v1/auth/challenge')
      .send({ deviceId });

    const { challengeId, nonce } = challengeRes.body.challenge;
    const signature = signPayload(keyPair.privateKey, nonce);

    // 2. Mock device verification queries
    mockedQuery.mockResolvedValueOnce({
      rows: [{ public_key: keyPair.publicKey, device_name: 'Test Android', platform: 'Android' }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    mockedQuery.mockResolvedValueOnce({
      rows: [],
      rowCount: 1,
      command: 'UPDATE',
      oid: 0,
      fields: [],
    });

    // 3. Verify challenge
    const verifyRes = await request(app)
      .post('/api/v1/auth/verify')
      .send({
        challengeId,
        deviceId,
        signature,
      });

    expect(verifyRes.status).toBe(200);
    expect(verifyRes.body.token).toBeDefined();
    expect(verifyRes.body.expiresIn).toBe(86400);
  });

  it('rejects verification if signature is invalid or signed with wrong key', async () => {
    const wrongKey = generateKeyPair();

    mockedQuery.mockResolvedValueOnce({
      rows: [{ public_key: keyPair.publicKey }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const challengeRes = await request(app)
      .post('/api/v1/auth/challenge')
      .send({ deviceId });

    const { challengeId, nonce } = challengeRes.body.challenge;
    const invalidSignature = signPayload(wrongKey.privateKey, nonce);

    mockedQuery.mockResolvedValueOnce({
      rows: [{ public_key: keyPair.publicKey, device_name: 'Test Android', platform: 'Android' }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const verifyRes = await request(app)
      .post('/api/v1/auth/verify')
      .send({
        challengeId,
        deviceId,
        signature: invalidSignature,
      });

    expect(verifyRes.status).toBe(401);
    expect(verifyRes.body.error.code).toBe('AUTHENTICATION_FAILED');
  });
});
