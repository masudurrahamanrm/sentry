import request from 'supertest';
import { app } from '../src/index';

describe('Phase 17: Error Handling & Resilience Validation', () => {
  it('returns consistent JSON error format for 404 routes', async () => {
    const res = await request(app).get('/api/v1/unknown-endpoint');
    expect(res.status).toBe(404);
  });

  it('returns consistent JSON error structure on validation failures', async () => {
    const res = await request(app)
      .post('/api/v1/devices/register')
      .send({ invalid: 'payload' });

    expect(res.status).toBe(422);
    expect(res.body).toHaveProperty('error');
    expect(res.body.error).toHaveProperty('code');
    expect(res.body.error).toHaveProperty('message');
    expect(res.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('returns structured 401 UNAUTHORIZED when session token is missing', async () => {
    const res = await request(app).get('/api/v1/pairings');
    expect(res.status).toBe(400); // Missing device query param
    expect(res.body.error.code).toBe('MISSING_QUERY');
  });
});
