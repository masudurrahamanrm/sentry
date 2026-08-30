import { query } from '../database/db';
import { Command, CommandStatus } from '@kinetix-sentry/types';
import { AppError } from '../middleware/errorHandler';
import { permissionService } from '../permissions/permissions.service';

export interface CreateCommandDto {
  pairingId: string;
  commandType: string;
  payload: Record<string, unknown>;
  nonce: string;
  timestamp: number;
}

// 10-minute sliding window nonce cache to prevent replay attacks
const seenNoncesMap = new Map<string, number>();
const NONCE_TTL_MS = 10 * 60 * 1000;
const MAX_TIMESTAMP_DRIFT_MS = 60 * 1000; // 60 seconds

export class CommandService {
  async dispatchCommand(dto: CreateCommandDto): Promise<Command> {
    const now = Date.now();

    // 1. Timestamp Freshness Check
    if (Math.abs(now - dto.timestamp) > MAX_TIMESTAMP_DRIFT_MS) {
      throw new AppError('COMMAND_EXPIRED', 'Command timestamp is stale or too far in the future.', 400);
    }

    // 2. Nonce Replay Prevention
    if (seenNoncesMap.has(dto.nonce)) {
      throw new AppError('REPLAY_ATTACK_DETECTED', 'This command nonce has already been processed.', 409);
    }

    seenNoncesMap.set(dto.nonce, now);

    // Periodic cleanup of stale nonces
    if (seenNoncesMap.size > 1000) {
      for (const [nonce, time] of seenNoncesMap.entries()) {
        if (now - time > NONCE_TTL_MS) {
          seenNoncesMap.delete(nonce);
        }
      }
    }

    // 3. Verify pairing exists and is ACTIVE
    const pairRes = await query<{ id: string; status: string; controller_device_id: string; agent_device_id: string }>(
      'SELECT id, status, controller_device_id, agent_device_id FROM pairings WHERE id = $1',
      [dto.pairingId]
    );

    if (pairRes.rows.length === 0) {
      throw new AppError('PAIRING_NOT_FOUND', 'Pairing relationship not found.', 404);
    }

    if (pairRes.rows[0].status !== 'ACTIVE') {
      throw new AppError('PAIRING_REVOKED', 'Cannot dispatch command to a revoked pairing.', 403);
    }

    const agentDeviceId = pairRes.rows[0].agent_device_id;

    // 4. Capability & Permission Verification
    await permissionService.checkCapabilityAuthorization(agentDeviceId, dto.commandType);

    // 5. Generate command ID
    const commandId = `cmd_${now}_${dto.nonce.slice(0, 8)}`;

    const text = `
      INSERT INTO commands (pairing_id, command_id, command_type, payload, status)
      VALUES ($1, $2, $3, $4, 'SENT')
      RETURNING id, pairing_id as "pairingId", command_id as "commandId", command_type as "commandType",
                payload, status, result, created_at as "createdAt", completed_at as "completedAt";
    `;
    const res = await query<Command>(text, [dto.pairingId, commandId, dto.commandType, JSON.stringify(dto.payload)]);
    return res.rows[0];
  }

  async getCommandById(commandId: string): Promise<Command> {
    const text = `
      SELECT id, pairing_id as "pairingId", command_id as "commandId", command_type as "commandType",
             payload, status, result, created_at as "createdAt", completed_at as "completedAt"
      FROM commands
      WHERE command_id = $1;
    `;
    const res = await query<Command>(text, [commandId]);
    if (res.rows.length === 0) {
      throw new AppError('COMMAND_NOT_FOUND', `Command with ID ${commandId} not found.`, 404);
    }
    return res.rows[0];
  }

  async recordCommandResponse(
    commandId: string,
    status: 'SUCCESS' | 'DENIED' | 'FAILED',
    result?: Record<string, unknown>
  ): Promise<Command> {
    const text = `
      UPDATE commands
      SET status = $2, result = $3::jsonb, completed_at = NOW()
      WHERE command_id = $1
      RETURNING id, pairing_id as "pairingId", command_id as "commandId", command_type as "commandType",
                payload, status, result, created_at as "createdAt", completed_at as "completedAt";
    `;
    const res = await query<Command>(text, [commandId, status, JSON.stringify(result || {})]);
    if (res.rows.length === 0) {
      throw new AppError('COMMAND_NOT_FOUND', `Command with ID ${commandId} not found.`, 404);
    }
    return res.rows[0];
  }
}

export const commandService = new CommandService();
