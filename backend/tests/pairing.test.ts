import request from 'supertest';
import { app } from '../src/index';
import { generateKeyPair, signPayload, hashPairingCode } from '@kinetix-sentry/crypto';

jest.mock('../src/database/db', () => ({
  query: jest.fn(),
  pool: {
    connect: jest.fn(),
    on: jest.fn(),
  },
}));

import { query } from '../src/database/db';

const mockedQuery = query as jest.MockedFunction<typeof query>;

describe('Phase 6: Secure Pairing-Code System', () => {
  const agentKeys = generateKeyPair();
  const controllerDeviceId = 'KX-1111-2222';
  const agentDeviceId = 'SN-3333-4444';
  const sessionId = 'd290f1ee-6c54-4b01-90e6-d701748f0851';
  const correctCode = '654321';

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('starts a pairing session and generates single-use 6-digit code', async () => {
    // 1. Check devices exist
    mockedQuery.mockResolvedValueOnce({
      rows: [{ device_id: controllerDeviceId }, { device_id: agentDeviceId }],
      rowCount: 2,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    // 2. Insert pairing session
    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: sessionId }],
      rowCount: 1,
      command: 'INSERT',
      oid: 0,
      fields: [],
    });

    const res = await request(app)
      .post('/api/v1/pairing/start')
      .send({ controllerDeviceId, agentDeviceId });

    expect(res.status).toBe(201);
    expect(res.body.session.sessionId).toBe(sessionId);
    expect(res.body.session.pairingCode).toMatch(/^\d{6}$/);
    expect(res.body.session.expiresInSeconds).toBe(300);
  });

  it('successfully confirms pairing with valid code and agent signature', async () => {
    const payloadToSign = `${sessionId}:${correctCode}:${agentDeviceId}`;
    const signature = signPayload(agentKeys.privateKey, payloadToSign);

    // 1. Session lookup
    mockedQuery.mockResolvedValueOnce({
      rows: [
        {
          id: sessionId,
          controller_device_id: controllerDeviceId,
          agent_device_id: agentDeviceId,
          pairing_code_hash: hashPairingCode(correctCode),
          expires_at: new Date(Date.now() + 200000),
          status: 'PENDING',
        },
      ],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    // 2. Agent public key lookup
    mockedQuery.mockResolvedValueOnce({
      rows: [{ public_key: agentKeys.publicKey }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    // 3. Mark session confirmed
    mockedQuery.mockResolvedValueOnce({
      rows: [],
      rowCount: 1,
      command: 'UPDATE',
      oid: 0,
      fields: [],
    });

    // 4. Upsert Pairing
    mockedQuery.mockResolvedValueOnce({
      rows: [
        {
          id: 'pairing-uuid-1',
          controllerDeviceId,
          agentDeviceId,
          status: 'ACTIVE',
          createdAt: new Date(),
          lastUsedAt: new Date(),
          revokedAt: null,
        },
      ],
      rowCount: 1,
      command: 'INSERT',
      oid: 0,
      fields: [],
    });

    const res = await request(app)
      .post('/api/v1/pairing/confirm')
      .send({
        sessionId,
        pairingCode: correctCode,
        agentDeviceId,
        signature,
      });

    expect(res.status).toBe(200);
    expect(res.body.pairing.status).toBe('ACTIVE');
    expect(res.body.pairing.controllerDeviceId).toBe(controllerDeviceId);
  });

  it('rejects pairing when code is wrong', async () => {
    const signature = signPayload(agentKeys.privateKey, 'some-payload');

    mockedQuery.mockResolvedValueOnce({
      rows: [
        {
          id: sessionId,
          controller_device_id: controllerDeviceId,
          agent_device_id: agentDeviceId,
          pairing_code_hash: hashPairingCode(correctCode),
          expires_at: new Date(Date.now() + 200000),
          status: 'PENDING',
        },
      ],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const res = await request(app)
      .post('/api/v1/pairing/confirm')
      .send({
        sessionId,
        pairingCode: '000000',
        agentDeviceId,
        signature,
      });

    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('INVALID_PAIRING_CODE');
  });

  it('rejects pairing when code has expired (> 5 minutes)', async () => {
    const signature = signPayload(agentKeys.privateKey, 'some-payload');

    mockedQuery.mockResolvedValueOnce({
      rows: [
        {
          id: sessionId,
          controller_device_id: controllerDeviceId,
          agent_device_id: agentDeviceId,
          pairing_code_hash: hashPairingCode(correctCode),
          expires_at: new Date(Date.now() - 10000), // Expired in past
          status: 'PENDING',
        },
      ],
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

    const res = await request(app)
      .post('/api/v1/pairing/confirm')
      .send({
        sessionId,
        pairingCode: correctCode,
        agentDeviceId,
        signature,
      });

    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('PAIRING_CODE_EXPIRED');
  });

  it('rejects pairing code reuse (single-use enforcement)', async () => {
    const signature = signPayload(agentKeys.privateKey, 'some-payload');

    mockedQuery.mockResolvedValueOnce({
      rows: [
        {
          id: sessionId,
          controller_device_id: controllerDeviceId,
          agent_device_id: agentDeviceId,
          pairing_code_hash: hashPairingCode(correctCode),
          expires_at: new Date(Date.now() + 200000),
          status: 'CONFIRMED', // Already confirmed!
        },
      ],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const res = await request(app)
      .post('/api/v1/pairing/confirm')
      .send({
        sessionId,
        pairingCode: correctCode,
        agentDeviceId,
        signature,
      });

    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('PAIRING_CODE_REUSED');
  });
});
