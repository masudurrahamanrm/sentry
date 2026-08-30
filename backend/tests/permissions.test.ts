import { permissionService } from '../src/permissions/permissions.service';
import { query } from '../src/database/db';

jest.mock('../src/database/db', () => ({
  query: jest.fn(),
  pool: { connect: jest.fn(), on: jest.fn() },
}));

const mockedQuery = query as jest.MockedFunction<typeof query>;

describe('Phase 11: Capability & Permission Authorization System', () => {
  const deviceId = 'SN-7F42-K9P3';

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('allows commands that require no special capabilities (e.g. DEVICE_INFO, PING)', async () => {
    const isAllowed = await permissionService.checkCapabilityAuthorization(deviceId, 'DEVICE_INFO');
    expect(isAllowed).toBe(true);
  });

  it('allows command when device capability is explicitly granted', async () => {
    mockedQuery.mockResolvedValueOnce({
      rows: [{ capabilities: { camera: true, location: false } }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    const isAllowed = await permissionService.checkCapabilityAuthorization(deviceId, 'TAKE_PHOTO');
    expect(isAllowed).toBe(true);
  });

  it('rejects command with 403 PERMISSION_REQUIRED when capability is disabled', async () => {
    mockedQuery.mockResolvedValueOnce({
      rows: [{ capabilities: { camera: false, location: false } }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    });

    await expect(
      permissionService.checkCapabilityAuthorization(deviceId, 'TAKE_PHOTO')
    ).rejects.toMatchObject({
      code: 'PERMISSION_REQUIRED',
      statusCode: 403,
    });
  });
});
