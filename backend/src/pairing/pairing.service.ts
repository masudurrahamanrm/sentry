import { query } from '../database/db';
import { PairingSession, Pairing, PairingStatus } from '@kinetix-sentry/types';
import { generatePairingCode, hashPairingCode, verifySignature } from '@kinetix-sentry/crypto';
import { AppError } from '../middleware/errorHandler';

// In-memory attempt tracker for rate limiting pairing code guessing (max 3 failed attempts)
const failedAttemptsMap = new Map<string, number>();

export class PairingService {
  async startPairingSession(controllerDeviceId: string, agentDeviceId: string): Promise<{ sessionId: string; pairingCode: string; expiresInSeconds: number }> {
    // Verify both devices exist
    const devCheck = await query('SELECT device_id FROM devices WHERE device_id IN ($1, $2)', [controllerDeviceId, agentDeviceId]);
    if (devCheck.rows.length < 2) {
      throw new AppError('DEVICE_NOT_FOUND', 'One or both devices specified for pairing do not exist.', 404);
    }

    const pairingCode = generatePairingCode();
    const pairingCodeHash = hashPairingCode(pairingCode);
    const expiresInSeconds = 300; // 5 minutes

    const text = `
      INSERT INTO pairing_sessions (controller_device_id, agent_device_id, pairing_code_hash, expires_at, status)
      VALUES ($1, $2, $3, NOW() + INTERVAL '5 minutes', 'PENDING')
      RETURNING id;
    `;
    const res = await query<{ id: string }>(text, [controllerDeviceId, agentDeviceId, pairingCodeHash]);
    const sessionId = res.rows[0].id;
    failedAttemptsMap.set(sessionId, 0);

    return {
      sessionId,
      pairingCode,
      expiresInSeconds,
    };
  }

  async confirmPairingSession(
    sessionId: string,
    pairingCode: string,
    agentDeviceId: string,
    signature: string
  ): Promise<Pairing> {
    const sessionRes = await query<{
      id: string;
      controller_device_id: string;
      agent_device_id: string;
      pairing_code_hash: string;
      expires_at: Date;
      status: PairingStatus;
    }>(
      'SELECT id, controller_device_id, agent_device_id, pairing_code_hash, expires_at, status FROM pairing_sessions WHERE id = $1',
      [sessionId]
    );

    if (sessionRes.rows.length === 0) {
      throw new AppError('SESSION_NOT_FOUND', 'Pairing session not found.', 404);
    }

    const session = sessionRes.rows[0];

    if (session.status === 'CONFIRMED') {
      throw new AppError('PAIRING_CODE_REUSED', 'This pairing code has already been used.', 400);
    }

    if (session.status !== 'PENDING') {
      throw new AppError('INVALID_SESSION_STATUS', `Pairing session is ${session.status.toLowerCase()}.`, 400);
    }

    if (new Date() > new Date(session.expires_at)) {
      await query("UPDATE pairing_sessions SET status = 'EXPIRED' WHERE id = $1", [sessionId]);
      throw new AppError('PAIRING_CODE_EXPIRED', 'The pairing code has expired.', 400);
    }

    if (session.agent_device_id !== agentDeviceId) {
      throw new AppError('DEVICE_MISMATCH', 'Agent device ID does not match session request.', 403);
    }

    // Check rate limit attempts
    const attempts = failedAttemptsMap.get(sessionId) || 0;
    if (attempts >= 3) {
      await query("UPDATE pairing_sessions SET status = 'REJECTED' WHERE id = $1", [sessionId]);
      throw new AppError('RATE_LIMIT_EXCEEDED', 'Too many invalid pairing code attempts. Session is locked.', 429);
    }

    const providedHash = hashPairingCode(pairingCode);
    if (providedHash !== session.pairing_code_hash) {
      failedAttemptsMap.set(sessionId, attempts + 1);
      const remaining = 3 - (attempts + 1);
      throw new AppError(
        'INVALID_PAIRING_CODE',
        `The provided pairing code is invalid. ${remaining} attempt(s) remaining.`,
        400,
        { remainingAttempts: remaining }
      );
    }

    // Verify Agent Cryptographic Signature
    const agentRes = await query<{ public_key: string }>('SELECT public_key FROM devices WHERE device_id = $1', [
      agentDeviceId,
    ]);
    if (agentRes.rows.length === 0) {
      throw new AppError('DEVICE_NOT_FOUND', 'Agent device is not registered.', 404);
    }

    const agentPublicKey = agentRes.rows[0].public_key;
    const payloadToVerify = `${sessionId}:${pairingCode}:${agentDeviceId}`;
    const isValidSignature = verifySignature(agentPublicKey, payloadToVerify, signature);

    if (!isValidSignature) {
      throw new AppError('INVALID_SIGNATURE', 'Cryptographic signature verification failed for agent.', 401);
    }

    // Cleanup rate limiter map
    failedAttemptsMap.delete(sessionId);

    // Mark session confirmed
    await query("UPDATE pairing_sessions SET status = 'CONFIRMED', confirmed_at = NOW() WHERE id = $1", [sessionId]);

    // Upsert Pairing
    const pairText = `
      INSERT INTO pairings (controller_device_id, agent_device_id, status, last_used_at)
      VALUES ($1, $2, 'ACTIVE', NOW())
      ON CONFLICT (controller_device_id, agent_device_id) DO UPDATE SET
        status = 'ACTIVE',
        last_used_at = NOW(),
        revoked_at = NULL
      RETURNING id, controller_device_id as "controllerDeviceId", agent_device_id as "agentDeviceId",
                status, created_at as "createdAt", last_used_at as "lastUsedAt", revoked_at as "revokedAt";
    `;
    const pairRes = await query<Pairing>(pairText, [session.controller_device_id, session.agent_device_id]);
    return pairRes.rows[0];
  }

  async listPairingsForDevice(deviceId: string): Promise<Pairing[]> {
    const text = `
      SELECT id, controller_device_id as "controllerDeviceId", agent_device_id as "agentDeviceId",
             status, created_at as "createdAt", last_used_at as "lastUsedAt", revoked_at as "revokedAt"
      FROM pairings
      WHERE (controller_device_id = $1 OR agent_device_id = $1) AND status = 'ACTIVE'
      ORDER BY last_used_at DESC;
    `;
    const res = await query<Pairing>(text, [deviceId]);
    return res.rows;
  }

  async getPairingById(pairingId: string): Promise<Pairing> {
    const text = `
      SELECT id, controller_device_id as "controllerDeviceId", agent_device_id as "agentDeviceId",
             status, created_at as "createdAt", last_used_at as "lastUsedAt", revoked_at as "revokedAt"
      FROM pairings
      WHERE id = $1;
    `;
    const res = await query<Pairing>(text, [pairingId]);
    if (res.rows.length === 0) {
      throw new AppError('PAIRING_NOT_FOUND', 'Pairing relationship not found.', 404);
    }
    return res.rows[0];
  }

  async revokePairing(pairingId: string): Promise<Pairing> {
    const text = `
      UPDATE pairings
      SET status = 'REVOKED', revoked_at = NOW()
      WHERE id = $1
      RETURNING id, controller_device_id as "controllerDeviceId", agent_device_id as "agentDeviceId",
                status, created_at as "createdAt", last_used_at as "lastUsedAt", revoked_at as "revokedAt";
    `;
    const res = await query<Pairing>(text, [pairingId]);
    if (res.rows.length === 0) {
      throw new AppError('PAIRING_NOT_FOUND', 'Pairing relationship not found.', 404);
    }
    return res.rows[0];
  }
}

export const pairingService = new PairingService();
