import { query } from '../database/db';
import { Device, DeviceCapabilities, PlatformType, DeviceStatus } from '@kinetix-sentry/types';
import { AppError } from '../middleware/errorHandler';

export interface RegisterDeviceDto {
  deviceId: string;
  deviceName: string;
  platform: PlatformType;
  osVersion: string;
  appVersion: string;
  publicKey: string;
  capabilities?: DeviceCapabilities;
}

export class DeviceService {
  async registerOrUpdate(dto: RegisterDeviceDto): Promise<Device> {
    // Check if device already exists to verify public key consistency
    const existing = await query<{ public_key: string; capabilities: DeviceCapabilities }>(
      'SELECT public_key, capabilities FROM devices WHERE device_id = $1',
      [dto.deviceId]
    );

    const defaultCaps: DeviceCapabilities = {
      camera: false,
      location: false,
      notifications: false,
      files: false,
      microphone: false,
      battery: true,
    };

    const initialCapabilities = dto.capabilities || (existing.rows[0]?.capabilities ?? defaultCaps);

    const text = `
      INSERT INTO devices (device_id, device_name, platform, os_version, app_version, public_key, capabilities, status, last_seen_at)
      VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, 'ONLINE', NOW())
      ON CONFLICT (device_id) DO UPDATE SET
        public_key = EXCLUDED.public_key,
        device_name = EXCLUDED.device_name,
        platform = EXCLUDED.platform,
        os_version = EXCLUDED.os_version,
        app_version = EXCLUDED.app_version,
        status = 'ONLINE',
        last_seen_at = NOW()
      RETURNING id, device_id as "deviceId", device_name as "deviceName", platform, os_version as "osVersion",
                app_version as "appVersion", public_key as "publicKey", status, capabilities, created_at as "createdAt",
                last_seen_at as "lastSeenAt";
    `;
    const res = await query<Device>(text, [
      dto.deviceId,
      dto.deviceName,
      dto.platform,
      dto.osVersion,
      dto.appVersion,
      dto.publicKey,
      JSON.stringify(initialCapabilities),
    ]);
    return res.rows[0];
  }

  async getDeviceById(deviceId: string): Promise<Device> {
    const text = `
      SELECT id, device_id as "deviceId", device_name as "deviceName", platform, os_version as "osVersion",
             app_version as "appVersion", public_key as "publicKey", status, capabilities, created_at as "createdAt",
             last_seen_at as "lastSeenAt"
      FROM devices
      WHERE device_id = $1;
    `;
    const res = await query<Device>(text, [deviceId]);
    if (res.rows.length === 0) {
      throw new AppError('DEVICE_NOT_FOUND', `Device with ID ${deviceId} not found.`, 404);
    }
    return res.rows[0];
  }

  async listDevices(): Promise<Device[]> {
    const text = `
      SELECT id, device_id as "deviceId", device_name as "deviceName", platform, os_version as "osVersion",
             app_version as "appVersion", public_key as "publicKey", status, capabilities, created_at as "createdAt",
             last_seen_at as "lastSeenAt"
      FROM devices
      ORDER BY last_seen_at DESC;
    `;
    const res = await query<Device>(text);
    return res.rows;
  }

  async updateFriendlyName(deviceId: string, name: string): Promise<Device> {
    const text = `
      UPDATE devices
      SET device_name = $2
      WHERE device_id = $1
      RETURNING id, device_id as "deviceId", device_name as "deviceName", platform, os_version as "osVersion",
                app_version as "appVersion", public_key as "publicKey", status, capabilities, created_at as "createdAt",
                last_seen_at as "lastSeenAt";
    `;
    const res = await query<Device>(text, [deviceId, name]);
    if (res.rows.length === 0) {
      throw new AppError('DEVICE_NOT_FOUND', `Device with ID ${deviceId} not found.`, 404);
    }
    return res.rows[0];
  }

  async updateCapabilities(deviceId: string, capabilities: DeviceCapabilities): Promise<DeviceCapabilities> {
    const text = `
      UPDATE devices
      SET capabilities = $2::jsonb
      WHERE device_id = $1
      RETURNING capabilities;
    `;
    const res = await query<{ capabilities: DeviceCapabilities }>(text, [deviceId, JSON.stringify(capabilities)]);
    if (res.rows.length === 0) {
      throw new AppError('DEVICE_NOT_FOUND', `Device with ID ${deviceId} not found.`, 404);
    }
    return res.rows[0].capabilities;
  }
}

export const deviceService = new DeviceService();
