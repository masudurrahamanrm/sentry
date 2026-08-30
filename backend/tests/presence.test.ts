import request from 'supertest';
import { app } from '../src/index';
import { presenceService } from '../src/presence/presence.service';

jest.mock('../src/database/db', () => ({
  query: jest.fn().mockResolvedValue({
    rows: [{ status: 'ONLINE', last_seen_at: new Date() }],
    rowCount: 1,
  }),
  pool: {
    connect: jest.fn(),
    on: jest.fn(),
  },
}));

describe('Phase 9: Online/Offline Presence System', () => {
  const deviceId = 'SN-7F42-K9P3';

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('marks device online upon connection', async () => {
    await presenceService.markDeviceOnline(deviceId, true);
    const presence = await presenceService.getDevicePresence(deviceId);

    expect(presence.status).toBe('ONLINE');
    expect(presence.isSocketConnected).toBe(true);
  });

  it('records heartbeat and maintains ONLINE status', async () => {
    await presenceService.recordHeartbeat(deviceId);
    const presence = await presenceService.getDevicePresence(deviceId);

    expect(presence.status).toBe('ONLINE');
  });

  it('returns presence details through REST API endpoint', async () => {
    await presenceService.markDeviceOnline(deviceId, true);

    const res = await request(app).get(`/api/v1/presence/${deviceId}`);
    expect(res.status).toBe(200);
    expect(res.body.presence.deviceId).toBe(deviceId);
    expect(res.body.presence.status).toBe('ONLINE');
  });

  it('accepts REST heartbeat ping for network transitions', async () => {
    const res = await request(app)
      .post('/api/v1/presence/heartbeat')
      .send({ deviceId });

    expect(res.status).toBe(200);
    expect(res.body.status).toBe('OK');
  });
});
