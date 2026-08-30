import request from 'supertest';
import { app } from '../src/index';

jest.mock('../src/database/db', () => ({
  query: jest.fn(),
  pool: {
    connect: jest.fn(),
    on: jest.fn(),
  },
}));

import { query } from '../src/database/db';

const mockedQuery = query as jest.MockedFunction<typeof query>;

describe('Phase 4: Device Registration & Identity Integrity', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('rejects registration with invalid device ID format', async () => {
    const res = await request(app)
      .post('/api/v1/devices/register')
      .send({
        deviceId: 'BAD-ID-12345',
        deviceName: 'Android Device',
        platform: 'Android',
        osVersion: 'Android 15',
        appVersion: '1.0.0',
        publicKey: 'valid_public_key_32_characters_minimum_length',
      });

    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('registers a brand new Sentry agent device with default capabilities', async () => {
    // 1. Initial existence check returns empty
    mockedQuery.mockResolvedValueOnce({
      rows: [],
      rowCount: 0,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    // 2. Insert returns newly created device
    const mockDevice = {
      id: 'uuid-1',
      deviceId: 'SN-4A2B-9C8D',
      deviceName: 'Pixel 9 Pro',
      platform: 'Android',
      osVersion: 'Android 15',
      appVersion: '1.0.0',
      publicKey: 'valid_public_key_32_characters_minimum_length',
      status: 'ONLINE',
      capabilities: { camera: false, location: false, notifications: false, files: false, microphone: false, battery: true },
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
        deviceId: 'SN-4A2B-9C8D',
        deviceName: 'Pixel 9 Pro',
        platform: 'Android',
        osVersion: 'Android 15',
        appVersion: '1.0.0',
        publicKey: 'valid_public_key_32_characters_minimum_length',
      });

    expect(res.status).toBe(201);
    expect(res.body.device.deviceId).toBe('SN-4A2B-9C8D');
    expect(res.body.device.status).toBe('ONLINE');
    expect(res.body.device.capabilities.battery).toBe(true);
  });

  it('rejects registration if device ID exists with a DIFFERENT public key (hijacking protection)', async () => {
    // Return existing device with a different public key
    mockedQuery.mockResolvedValueOnce({
      rows: [{ public_key: 'original_key_111111111111111111111111', capabilities: {} }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const res = await request(app)
      .post('/api/v1/devices/register')
      .send({
        deviceId: 'SN-4A2B-9C8D',
        deviceName: 'Imposter Phone',
        platform: 'Android',
        osVersion: 'Android 15',
        appVersion: '1.0.0',
        publicKey: 'attacker_key_222222222222222222222222',
      });

    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe('DEVICE_IDENTITY_CONFLICT');
  });

  it('updates metadata when registering an existing device with the SAME public key', async () => {
    const validKey = 'same_trusted_key_33333333333333333333';
    mockedQuery.mockResolvedValueOnce({
      rows: [{ public_key: validKey, capabilities: { camera: true } }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    mockedQuery.mockResolvedValueOnce({
      rows: [
        {
          id: 'uuid-1',
          deviceId: 'SN-4A2B-9C8D',
          deviceName: 'Pixel 9 Pro Updated',
          platform: 'Android',
          osVersion: 'Android 16',
          appVersion: '1.1.0',
          publicKey: validKey,
          status: 'ONLINE',
          capabilities: { camera: true },
          createdAt: new Date(),
          lastSeenAt: new Date(),
        },
      ],
      rowCount: 1,
      command: 'INSERT',
      oid: 0,
      fields: [],
    });

    const res = await request(app)
      .post('/api/v1/devices/register')
      .send({
        deviceId: 'SN-4A2B-9C8D',
        deviceName: 'Pixel 9 Pro Updated',
        platform: 'Android',
        osVersion: 'Android 16',
        appVersion: '1.1.0',
        publicKey: validKey,
      });

    expect(res.status).toBe(201);
    expect(res.body.device.appVersion).toBe('1.1.0');
    expect(res.body.device.osVersion).toBe('Android 16');
  });
});
