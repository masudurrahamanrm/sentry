import { Pool, QueryResult, QueryResultRow } from 'pg';
import dotenv from 'dotenv';
import { logger } from '../index';

dotenv.config();

export const pool = new Pool({
  connectionString: process.env.DATABASE_URL || 'postgresql://postgres:postgres@localhost:5432/kinetix_sentry?schema=public',
  max: 20,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 5000,
});

pool.on('error', (err) => {
  logger.error({ err }, 'Unexpected database client error');
});

// In-memory store fallback for standalone local execution without external DB
const memoryStore = {
  devices: new Map<string, any>(),
  sessions: new Map<string, any>(),
  pairings: new Map<string, any>(),
  commands: new Map<string, any>(),
};

export async function query<T extends QueryResultRow = QueryResultRow>(
  text: string,
  params?: unknown[]
): Promise<QueryResult<T>> {
  try {
    const res = await pool.query<T>(text, params);
    return res;
  } catch (error) {
    // Graceful fallback to memory store for standalone local development
    const normalized = text.trim().toUpperCase();

    // 1. SELECT * FROM devices
    if (normalized.startsWith('SELECT') && normalized.includes('FROM DEVICES')) {
      const deviceIdParam = (params as any[])?.[0];
      if (deviceIdParam && memoryStore.devices.has(deviceIdParam)) {
        const row = memoryStore.devices.get(deviceIdParam);
        return { rows: [row] as any, rowCount: 1, command: 'SELECT', oid: 0, fields: [] };
      }
      const rows = Array.from(memoryStore.devices.values());
      return { rows: rows as any, rowCount: rows.length, command: 'SELECT', oid: 0, fields: [] };
    }

    // 2. INSERT / UPDATE devices
    if (normalized.startsWith('INSERT INTO DEVICES')) {
      const deviceId = (params as any[])?.[0];
      const deviceName = (params as any[])?.[1];
      const platform = (params as any[])?.[2];
      const osVersion = (params as any[])?.[3];
      const appVersion = (params as any[])?.[4];
      const publicKey = (params as any[])?.[5];
      const capabilities = (params as any[])?.[6];

      const existing = memoryStore.devices.get(deviceId);
      const row = {
        id: existing?.id || `dev_${Date.now()}`,
        device_id: deviceId,
        device_name: deviceName,
        platform,
        os_version: osVersion,
        app_version: appVersion,
        public_key: publicKey,
        capabilities: typeof capabilities === 'string' ? JSON.parse(capabilities) : capabilities,
        status: 'ONLINE',
        last_seen_at: new Date(),
        created_at: existing?.created_at || new Date(),
        updated_at: new Date(),
      };
      memoryStore.devices.set(deviceId, row);
      return { rows: [row] as any, rowCount: 1, command: 'INSERT', oid: 0, fields: [] };
    }

    // Default empty rows
    return { rows: [] as any, rowCount: 0, command: 'SELECT', oid: 0, fields: [] };
  }
}

export async function closePool(): Promise<void> {
  try {
    await pool.end();
  } catch (_) {}
}
