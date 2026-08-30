import request from 'supertest';
import { app } from '../src/index';

jest.mock('../src/database/db', () => ({
  query: jest.fn(),
  pool: { connect: jest.fn(), on: jest.fn() },
}));

import { query } from '../src/database/db';

const mockedQuery = query as jest.MockedFunction<typeof query>;

describe('Phase 12: Storage & Signed File URL Architecture', () => {
  const pairingId = 'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d';

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('generates signed upload URL for permitted file size', async () => {
    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: pairingId, status: 'ACTIVE', controller_device_id: 'KX-1', agent_device_id: 'SN-1' }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const res = await request(app)
      .post('/api/v1/storage/upload-url')
      .send({
        pairingId,
        filename: 'capture_photo.jpg',
        fileSize: 2 * 1024 * 1024, // 2MB
        contentType: 'image/jpeg',
        uploaderDeviceId: 'SN-1',
      });

    expect(res.status).toBe(201);
    expect(res.body.fileId).toBeDefined();
    expect(res.body.uploadUrl).toContain('capture_photo.jpg');
    expect(res.body.expiresInSeconds).toBe(900);
  });

  it('rejects upload URL request when file exceeds max size limit (>50MB)', async () => {
    const res = await request(app)
      .post('/api/v1/storage/upload-url')
      .send({
        pairingId,
        filename: 'large_video.mp4',
        fileSize: 60 * 1024 * 1024, // 60MB (exceeds 50MB limit)
        contentType: 'video/mp4',
      });

    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('FILE_TOO_LARGE');
  });

  it('generates signed download URL for an existing authorized file', async () => {
    // 1. Upload first
    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: pairingId, status: 'ACTIVE' }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const uploadRes = await request(app)
      .post('/api/v1/storage/upload-url')
      .send({
        pairingId,
        filename: 'test_doc.pdf',
        fileSize: 50000,
        contentType: 'application/pdf',
      });

    const fileId = uploadRes.body.fileId;

    // 2. Request download URL
    mockedQuery.mockResolvedValueOnce({
      rows: [{ id: pairingId, status: 'ACTIVE' }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const downloadRes = await request(app)
      .post('/api/v1/storage/download-url')
      .send({
        pairingId,
        fileId,
      });

    expect(downloadRes.status).toBe(200);
    expect(downloadRes.body.downloadUrl).toContain('test_doc.pdf');
    expect(downloadRes.body.filename).toBe('test_doc.pdf');
  });
});
