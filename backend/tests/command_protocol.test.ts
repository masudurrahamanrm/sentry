import request from 'supertest';
import { app } from '../src/index';
import { generateNonce } from '@kinetix-sentry/crypto';

jest.mock('../src/database/db', () => ({
  query: jest.fn(),
  pool: {
    connect: jest.fn(),
    on: jest.fn(),
  },
}));

import { query } from '../src/database/db';

const mockedQuery = query as jest.MockedFunction<typeof query>;

describe('Phase 10: Command Protocol & Replay Protection', () => {
  const pairingId = 'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d';

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('successfully dispatches a valid command with fresh timestamp and unique nonce', async () => {
    // 1. Mock pairing query (active)
    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: pairingId, status: 'ACTIVE', controller_device_id: 'KX-1', agent_device_id: 'SN-1' }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    // 2. Mock capability query for agent device (grant battery capability)
    mockedQuery.mockResolvedValueOnce({
      rows: [{ capabilities: { battery: true } }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    // 3. Mock command insertion
    mockedQuery.mockResolvedValueOnce({
      rows: [
        {
          id: 'uuid-cmd-1',
          pairingId,
          commandId: 'cmd_123',
          commandType: 'GET_BATTERY',
          payload: {},
          status: 'SENT',
          result: null,
          createdAt: new Date(),
          completedAt: null,
        },
      ],
      rowCount: 1,
      command: 'INSERT',
      oid: 0,
      fields: [],
    });

    const res = await request(app)
      .post('/api/v1/commands')
      .send({
        pairingId,
        commandType: 'GET_BATTERY',
        payload: {},
        nonce: generateNonce(),
        timestamp: Date.now(),
      });

    expect(res.status).toBe(201);
    expect(res.body.command.commandType).toBe('GET_BATTERY');
    expect(res.body.command.status).toBe('SENT');
  });

  it('rejects command with stale or expired timestamp (> 60s)', async () => {
    const res = await request(app)
      .post('/api/v1/commands')
      .send({
        pairingId,
        commandType: 'GET_BATTERY',
        payload: {},
        nonce: generateNonce(),
        timestamp: Date.now() - 120000, // 2 minutes old
      });

    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('COMMAND_EXPIRED');
  });

  it('detects and rejects replay attack with identical nonce', async () => {
    const duplicateNonce = 'reused_crypto_nonce_12345678';

    // First dispatch
    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: pairingId, status: 'ACTIVE' }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: '1', commandId: 'cmd_1', status: 'SENT' }],
      rowCount: 1,
      command: 'INSERT',
      oid: 0,
      fields: [],
    });

    await request(app)
      .post('/api/v1/commands')
      .send({
        pairingId,
        commandType: 'DEVICE_INFO',
        payload: {},
        nonce: duplicateNonce,
        timestamp: Date.now(),
      });

    // Replay dispatch with same nonce
    const replayRes = await request(app)
      .post('/api/v1/commands')
      .send({
        pairingId,
        commandType: 'DEVICE_INFO',
        payload: {},
        nonce: duplicateNonce,
        timestamp: Date.now(),
      });

    expect(replayRes.status).toBe(409);
    expect(replayRes.body.error.code).toBe('REPLAY_ATTACK_DETECTED');
  });

  it('rejects command dispatch on a REVOKED pairing', async () => {
    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: pairingId, status: 'REVOKED' }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const res = await request(app)
      .post('/api/v1/commands')
      .send({
        pairingId,
        commandType: 'DEVICE_INFO',
        payload: {},
        nonce: generateNonce(),
        timestamp: Date.now(),
      });

    expect(res.status).toBe(403);
    expect(res.body.error.code).toBe('PAIRING_REVOKED');
  });
});
