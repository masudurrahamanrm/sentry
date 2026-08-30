import jwt from 'jsonwebtoken';
import { query } from '../database/db';
import { generateNonce, verifySignature } from '@kinetix-sentry/crypto';
import { AppError } from '../middleware/errorHandler';

const JWT_SECRET = process.env.JWT_SECRET || 'dev_jwt_secret_key_32_characters_minimum_len';
const CHALLENGE_TTL_MS = 60 * 1000; // 60 seconds

interface AuthChallenge {
  challengeId: string;
  deviceId: string;
  nonce: string;
  expiresAt: number;
}

const challengeMap = new Map<string, AuthChallenge>();

export interface DeviceTokenPayload {
  deviceId: string;
  deviceName: string;
  platform: string;
}

export class AuthService {
  async generateChallenge(deviceId: string): Promise<{ challengeId: string; nonce: string; expiresInSeconds: number }> {
    // 1. Verify device exists and has a registered public key
    const devRes = await query<{ public_key: string }>('SELECT public_key FROM devices WHERE device_id = $1', [deviceId]);
    if (devRes.rows.length === 0) {
      throw new AppError('DEVICE_NOT_FOUND', `Device with ID ${deviceId} is not registered.`, 404);
    }

    const challengeId = `chl_${Date.now()}_${generateNonce(8)}`;
    const nonce = generateNonce(32);
    const expiresAt = Date.now() + CHALLENGE_TTL_MS;

    challengeMap.set(challengeId, {
      challengeId,
      deviceId,
      nonce,
      expiresAt,
    });

    return {
      challengeId,
      nonce,
      expiresInSeconds: 60,
    };
  }

  async verifyChallenge(
    challengeId: string,
    deviceId: string,
    signature: string
  ): Promise<{ token: string; expiresIn: number }> {
    const challenge = challengeMap.get(challengeId);
    if (!challenge) {
      throw new AppError('CHALLENGE_NOT_FOUND', 'Authentication challenge not found or expired.', 401);
    }

    // Single-use: delete immediately
    challengeMap.delete(challengeId);

    if (Date.now() > challenge.expiresAt) {
      throw new AppError('CHALLENGE_EXPIRED', 'Authentication challenge has expired.', 401);
    }

    if (challenge.deviceId !== deviceId) {
      throw new AppError('DEVICE_MISMATCH', 'Device ID does not match challenge target.', 403);
    }

    // Lookup device registered public key
    const devRes = await query<{ public_key: string; device_name: string; platform: string }>(
      'SELECT public_key, device_name, platform FROM devices WHERE device_id = $1',
      [deviceId]
    );

    if (devRes.rows.length === 0) {
      throw new AppError('DEVICE_NOT_FOUND', 'Device is not registered.', 404);
    }

    const device = devRes.rows[0];
    const isValid = verifySignature(device.public_key, challenge.nonce, signature);

    if (!isValid) {
      throw new AppError('AUTHENTICATION_FAILED', 'Cryptographic signature verification failed.', 401);
    }

    // Issue JWT session token
    const tokenPayload: DeviceTokenPayload = {
      deviceId,
      deviceName: device.device_name,
      platform: device.platform,
    };

    const token = jwt.sign(tokenPayload, JWT_SECRET, { expiresIn: '24h' });

    // Update last_seen_at
    await query("UPDATE devices SET last_seen_at = NOW(), status = 'ONLINE' WHERE device_id = $1", [deviceId]);

    return {
      token,
      expiresIn: 86400,
    };
  }

  verifyToken(token: string): DeviceTokenPayload {
    try {
      const decoded = jwt.verify(token, JWT_SECRET) as DeviceTokenPayload;
      return decoded;
    } catch {
      throw new AppError('INVALID_TOKEN', 'Session token is invalid or expired.', 401);
    }
  }
}

export const authService = new AuthService();
