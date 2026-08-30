import request from 'supertest';
import { app } from '../src/index';

// Mock the database query function for isolated REST unit/integration tests
jest.mock('../src/database/db', () => ({
  query: jest.fn(),
  pool: {
    connect: jest.fn(),
    on: jest.fn(),
  },
}));

import { query } from '../src/database/db';

const mockedQuery = query as jest.MockedFunction<typeof query>;

describe('REST API v1 Endpoints', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('Health Checks', () => {
    it('GET /health returns healthy status', async () => {
      const res = await request(app).get('/health');
      expect(res.status).toBe(200);
      expect(res.body.status).toBe('HEALTHY');
      expect(res.body.service).toBe('kinetix-sentry-backend');
    });

    it('GET /api/v1/health returns healthy v1 status', async () => {
      const res = await request(app).get('/api/v1/health');
      expect(res.status).toBe(200);
      expect(res.body.status).toBe('HEALTHY');
      expect(res.body.version).toBe('v1');
    });
  });

  describe('Devices API (/api/v1/devices)', () => {
    it('POST /api/v1/devices/register rejects invalid device ID format', async () => {
      const res = await request(app)
        .post('/api/v1/devices/register')
        .send({
          deviceId: 'INVALID_ID',
          deviceName: 'Test Phone',
          platform: 'Android',
          osVersion: 'Android 15',
          appVersion: '1.0.0',
          publicKey: 'abcdefghijklmnopqrstuvwxyz012345678901234567890',
        });

      expect(res.status).toBe(422);
      expect(res.body.error.code).toBe('VALIDATION_ERROR');
    });

    it('POST /api/v1/devices/register registers a valid device', async () => {
      // 1. Mock existence check
      mockedQuery.mockResolvedValueOnce({
        rows: [],
        rowCount: 0,
        command: 'SELECT',
        oid: 0,
        fields: [],
      });

      const mockDevice = {
        id: '123e4567-e89b-12d3-a456-426614174000',
        deviceId: 'SN-7F42-K9P3',
        deviceName: 'Test Android',
        platform: 'Android',
        osVersion: 'Android 15',
        appVersion: '1.0.0',
        publicKey: 'abcdefghijklmnopqrstuvwxyz012345678901234567890',
        status: 'ONLINE',
        capabilities: {},
        createdAt: new Date(),
        lastSeenAt: new Date(),
      };

      mockedQuery.mockResolvedValueOnce({
        rows: [mockDevice],
        rowCount: 1,
        command: 'INSERT',
        oid: 0,
        fields: [],
      });

      const res = await request(app)
        .post('/api/v1/devices/register')
        .send({
          deviceId: 'SN-7F42-K9P3',
          deviceName: 'Test Android',
          platform: 'Android',
          osVersion: 'Android 15',
          appVersion: '1.0.0',
          publicKey: 'abcdefghijklmnopqrstuvwxyz012345678901234567890',
        });

      expect(res.status).toBe(201);
      expect(res.body.device.deviceId).toBe('SN-7F42-K9P3');
    });

    it('GET /api/v1/devices returns list of devices', async () => {
      mockedQuery.mockResolvedValueOnce({
        rows: [
          {
            id: '123',
            deviceId: 'SN-7F42-K9P3',
            deviceName: 'Test Android',
            platform: 'Android',
            osVersion: '15',
            appVersion: '1.0',
            publicKey: 'abc',
            status: 'ONLINE',
            capabilities: {},
            createdAt: new Date(),
            lastSeenAt: new Date(),
          },
        ],
        rowCount: 1,
        command: 'SELECT',
        oid: 0,
        fields: [],
      });

      const res = await request(app).get('/api/v1/devices');
      expect(res.status).toBe(200);
      expect(res.body.devices.length).toBe(1);
    });
  });

  describe('Pairing API (/api/v1/pairing)', () => {
    it('POST /api/v1/pairing/start validates both device IDs', async () => {
      mockedQuery.mockResolvedValueOnce({
        rows: [{ device_id: 'KX-1234-5678' }, { device_id: 'SN-7F42-K9P3' }],
        rowCount: 2,
        command: 'SELECT',
        oid: 0,
        fields: [],
      });

      mockedQuery.mockResolvedValueOnce({
        rows: [{ id: 'session-uuid-1234' }],
        rowCount: 1,
        command: 'INSERT',
        oid: 0,
        fields: [],
      });

      const res = await request(app)
        .post('/api/v1/pairing/start')
        .send({
          controllerDeviceId: 'KX-1234-5678',
          agentDeviceId: 'SN-7F42-K9P3',
        });

      expect(res.status).toBe(201);
      expect(res.body.session.sessionId).toBe('session-uuid-1234');
      expect(res.body.session.pairingCode).toMatch(/^\d{6}$/);
      expect(res.body.session.expiresInSeconds).toBe(300);
    });
  });
});
