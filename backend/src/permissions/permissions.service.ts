import { query } from '../database/db';
import { DeviceCapabilities } from '@kinetix-sentry/types';
import { AppError } from '../middleware/errorHandler';

const COMMAND_CAPABILITY_MAP: Record<string, keyof DeviceCapabilities | null> = {
  DEVICE_INFO: null,
  PING: null,
  GET_BATTERY: 'battery',
  GET_LOCATION: 'location',
  TAKE_PHOTO: 'camera',
  RECORD_AUDIO: 'microphone',
  LIST_FILES: 'files',
  GET_NOTIFICATION_LOGS: 'notifications',
};

export class PermissionService {
  async checkCapabilityAuthorization(deviceId: string, commandType: string): Promise<boolean> {
    const requiredCapability = COMMAND_CAPABILITY_MAP[commandType];
    if (!requiredCapability) {
      return true; // No special capability required
    }

    const res = await query<{ capabilities: DeviceCapabilities }>(
      'SELECT capabilities FROM devices WHERE device_id = $1',
      [deviceId]
    );

    if (res.rows.length === 0) {
      throw new AppError('DEVICE_NOT_FOUND', `Device ${deviceId} not found.`, 404);
    }

    const capabilities = res.rows[0].capabilities || ({} as DeviceCapabilities);
    const isGranted = Boolean(capabilities[requiredCapability]);

    if (!isGranted) {
      throw new AppError(
        'PERMISSION_REQUIRED',
        `The '${commandType}' command requires '${requiredCapability}' capability, which is not granted on device ${deviceId}.`,
        403,
        { requiredCapability }
      );
    }

    return true;
  }

  async getDeviceCapabilities(deviceId: string): Promise<DeviceCapabilities> {
    const res = await query<{ capabilities: DeviceCapabilities }>(
      'SELECT capabilities FROM devices WHERE device_id = $1',
      [deviceId]
    );

    if (res.rows.length === 0) {
      throw new AppError('DEVICE_NOT_FOUND', `Device ${deviceId} not found.`, 404);
    }

    return res.rows[0].capabilities || {
      camera: false,
      location: false,
      notifications: false,
      files: false,
      microphone: false,
      battery: true,
    };
  }
}

export const permissionService = new PermissionService();
