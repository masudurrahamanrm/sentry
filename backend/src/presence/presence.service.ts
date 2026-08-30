import { query } from '../database/db';
import { DeviceStatus } from '@kinetix-sentry/types';
import { logger } from '../index';

interface DevicePresenceState {
  deviceId: string;
  status: DeviceStatus;
  lastSeenAt: number;
  isSocketConnected: boolean;
}

const HEARTBEAT_GRACE_PERIOD_MS = 90 * 1000; // 90 seconds (tolerant to single missed heartbeat)

export class PresenceService {
  private presenceMap = new Map<string, DevicePresenceState>();

  async markDeviceOnline(deviceId: string, isSocket: boolean = true): Promise<void> {
    const now = Date.now();
    this.presenceMap.set(deviceId, {
      deviceId,
      status: 'ONLINE',
      lastSeenAt: now,
      isSocketConnected: isSocket,
    });

    await query("UPDATE devices SET status = 'ONLINE', last_seen_at = NOW() WHERE device_id = $1", [deviceId]);
    logger.debug({ deviceId }, 'Device marked ONLINE');
  }

  async recordHeartbeat(deviceId: string): Promise<void> {
    const now = Date.now();
    const current = this.presenceMap.get(deviceId);
    if (current) {
      current.lastSeenAt = now;
      current.status = 'ONLINE';
    } else {
      this.presenceMap.set(deviceId, {
        deviceId,
        status: 'ONLINE',
        lastSeenAt: now,
        isSocketConnected: false,
      });
    }

    await query("UPDATE devices SET last_seen_at = NOW(), status = 'ONLINE' WHERE device_id = $1", [deviceId]);
    logger.debug({ deviceId }, 'Recorded device heartbeat');
  }

  async markDeviceOffline(deviceId: string): Promise<void> {
    const current = this.presenceMap.get(deviceId);
    if (current) {
      current.status = 'OFFLINE';
      current.isSocketConnected = false;
    }

    await query("UPDATE devices SET status = 'OFFLINE' WHERE device_id = $1", [deviceId]);
    logger.debug({ deviceId }, 'Device marked OFFLINE');
  }

  async getDevicePresence(deviceId: string): Promise<{ deviceId: string; status: DeviceStatus; isSocketConnected: boolean; lastSeenAt: Date }> {
    const state = this.presenceMap.get(deviceId);
    const now = Date.now();

    if (state) {
      // Apply grace window: If within grace period, remain ONLINE
      const isWithinGrace = now - state.lastSeenAt <= HEARTBEAT_GRACE_PERIOD_MS;
      const status: DeviceStatus = (state.isSocketConnected || isWithinGrace) ? 'ONLINE' : 'OFFLINE';

      return {
        deviceId,
        status,
        isSocketConnected: state.isSocketConnected,
        lastSeenAt: new Date(state.lastSeenAt),
      };
    }

    // Fallback to database
    const res = await query<{ status: DeviceStatus; last_seen_at: Date }>(
      'SELECT status, last_seen_at FROM devices WHERE device_id = $1',
      [deviceId]
    );

    if (res.rows.length === 0) {
      return {
        deviceId,
        status: 'OFFLINE',
        isSocketConnected: false,
        lastSeenAt: new Date(0),
      };
    }

    const row = res.rows[0];
    const isWithinDbGrace = now - new Date(row.last_seen_at).getTime() <= HEARTBEAT_GRACE_PERIOD_MS;

    return {
      deviceId,
      status: isWithinDbGrace ? 'ONLINE' : 'OFFLINE',
      isSocketConnected: false,
      lastSeenAt: row.last_seen_at,
    };
  }
}

export const presenceService = new PresenceService();
