import request from 'supertest';
import { app } from '../src/index';
import { generateKeyPair, signPayload, hashPairingCode, generateNonce } from '@kinetix-sentry/crypto';

jest.mock('../src/database/db', () => ({
  query: jest.fn(),
  pool: { connect: jest.fn(), on: jest.fn() },
}));

import { query } from '../src/database/db';

const mockedQuery = query as jest.MockedFunction<typeof query>;

describe('Phase 19: Full End-to-End System Integration Flow', () => {
  const controllerKeys = generateKeyPair();
  const agentKeys = generateKeyPair();
  const controllerId = 'KX-9999-8888';
  const agentId = 'SN-7777-6666';
  const pairingId = '11112222-3333-4444-5555-666677778888';
  const sessionId = '99998888-7777-6666-5555-444433332222';
  let pairingCode = '123456';

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('executes complete end-to-end flow from registration to command execution and revocation', async () => {
    // -------------------------------------------------------------
    // Step 1: Register Controller & Sentry Agent
    // -------------------------------------------------------------
    mockedQuery.mockResolvedValueOnce({ rows: [], rowCount: 0, command: 'SELECT', oid: 0, fields: [] }); // Controller check
    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: '1', deviceId: controllerId, status: 'ONLINE', capabilities: {} }],
      rowCount: 1,
      command: 'INSERT',
      oid: 0,
      fields: [],
    });

    const regController = await request(app)
      .post('/api/v1/devices/register')
      .send({
        deviceId: controllerId,
        deviceName: 'Pixel Tablet (Controller)',
        platform: 'Android',
        osVersion: 'Android 15',
        appVersion: '1.0.0',
        publicKey: controllerKeys.publicKey,
      });
    expect(regController.status).toBe(201);

    mockedQuery.mockResolvedValueOnce({ rows: [], rowCount: 0, command: 'SELECT', oid: 0, fields: [] }); // Agent check
    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: '2', deviceId: agentId, status: 'ONLINE', capabilities: { battery: true, camera: true } }],
      rowCount: 1,
      command: 'INSERT',
      oid: 0,
      fields: [],
    });

    const regAgent = await request(app)
      .post('/api/v1/devices/register')
      .send({
        deviceId: agentId,
        deviceName: 'Pixel 9 Pro (Sentry)',
        platform: 'Android',
        osVersion: 'Android 15',
        appVersion: '1.0.0',
        publicKey: agentKeys.publicKey,
        capabilities: { battery: true, camera: true },
      });
    expect(regAgent.status).toBe(201);

    // -------------------------------------------------------------
    // Step 2: Challenge-Response Authentication for Controller
    // -------------------------------------------------------------
    mockedQuery.mockResolvedValueOnce({
      rows: [{ public_key: controllerKeys.publicKey }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const challengeRes = await request(app)
      .post('/api/v1/auth/challenge')
      .send({ deviceId: controllerId });
    expect(challengeRes.status).toBe(200);

    const { challengeId, nonce } = challengeRes.body.challenge;
    const controllerSig = signPayload(controllerKeys.privateKey, nonce);

    mockedQuery.mockResolvedValueOnce({
      rows: [{ public_key: controllerKeys.publicKey, device_name: 'Controller', platform: 'Android' }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });
    mockedQuery.mockResolvedValueOnce({ rows: [], rowCount: 1, command: 'UPDATE', oid: 0, fields: [] });

    const authRes = await request(app)
      .post('/api/v1/auth/verify')
      .send({ challengeId, deviceId: controllerId, signature: controllerSig });
    expect(authRes.status).toBe(200);
    const controllerToken = authRes.body.token;
    expect(controllerToken).toBeDefined();

    // -------------------------------------------------------------
    // Step 3: Initiate Pairing Session
    // -------------------------------------------------------------
    mockedQuery.mockResolvedValueOnce({
      rows: [{ device_id: controllerId }, { device_id: agentId }],
      rowCount: 2,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });
    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: sessionId }],
      rowCount: 1,
      command: 'INSERT',
      oid: 0,
      fields: [],
    });

    const startPairingRes = await request(app)
      .post('/api/v1/pairing/start')
      .set('Authorization', `Bearer ${controllerToken}`)
      .send({ controllerDeviceId: controllerId, agentDeviceId: agentId });
    expect(startPairingRes.status).toBe(201);
    pairingCode = startPairingRes.body.session.pairingCode;

    // -------------------------------------------------------------
    // Step 4: Sentry Agent Confirms Pairing with Cryptographic Signature
    // -------------------------------------------------------------
    const agentConfirmSig = signPayload(agentKeys.privateKey, `${sessionId}:${pairingCode}:${agentId}`);

    mockedQuery.mockResolvedValueOnce({
      rows: [
        {
          id: sessionId,
          controller_device_id: controllerId,
          agent_device_id: agentId,
          pairing_code_hash: hashPairingCode(pairingCode),
          expires_at: new Date(Date.now() + 200000),
          status: 'PENDING',
        },
      ],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    mockedQuery.mockResolvedValueOnce({
      rows: [{ public_key: agentKeys.publicKey }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    mockedQuery.mockResolvedValueOnce({ rows: [], rowCount: 1, command: 'UPDATE', oid: 0, fields: [] });
    mockedQuery.mockResolvedValueOnce({
      rows: [
        {
          id: pairingId,
          controllerDeviceId: controllerId,
          agentDeviceId: agentId,
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

    const confirmPairingRes = await request(app)
      .post('/api/v1/pairing/confirm')
      .send({
        sessionId,
        pairingCode,
        agentDeviceId: agentId,
        signature: agentConfirmSig,
      });
    expect(confirmPairingRes.status).toBe(200);
    expect(confirmPairingRes.body.pairing.status).toBe('ACTIVE');

    // -------------------------------------------------------------
    // Step 5: Dispatch Authenticated Command (GET_BATTERY)
    // -------------------------------------------------------------
    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: pairingId, status: 'ACTIVE', controller_device_id: controllerId, agent_device_id: agentId }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });
    mockedQuery.mockResolvedValueOnce({
      rows: [{ capabilities: { battery: true } }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });
    mockedQuery.mockResolvedValueOnce({
      rows: [
        {
          id: 'cmd-uuid-1',
          pairingId,
          commandId: 'cmd_e2e_1',
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

    const cmdRes = await request(app)
      .post('/api/v1/commands')
      .set('Authorization', `Bearer ${controllerToken}`)
      .send({
        pairingId,
        commandType: 'GET_BATTERY',
        payload: {},
        nonce: generateNonce(),
        timestamp: Date.now(),
      });
    expect(cmdRes.status).toBe(201);
    expect(cmdRes.body.command.commandType).toBe('GET_BATTERY');

    // -------------------------------------------------------------
    // Step 6: Revoke Pairing Relationship
    // -------------------------------------------------------------
    mockedQuery.mockResolvedValueOnce({
      rows: [
        {
          id: pairingId,
          controllerDeviceId: controllerId,
          agentDeviceId: agentId,
          status: 'REVOKED',
          createdAt: new Date(),
          lastUsedAt: new Date(),
          revokedAt: new Date(),
        },
      ],
      rowCount: 1,
      command: 'UPDATE',
      oid: 0,
      fields: [],
    });

    const unpairRes = await request(app)
      .delete(`/api/v1/pairings/${pairingId}`)
      .set('Authorization', `Bearer ${controllerToken}`);
    expect(unpairRes.status).toBe(200);
    expect(unpairRes.body.pairing.status).toBe('REVOKED');
  });
});
