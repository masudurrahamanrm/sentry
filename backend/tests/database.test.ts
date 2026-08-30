import fs from 'fs';
import path from 'path';

describe('Database Schema & Migration Validation', () => {
  it('should have valid SQL migration files', () => {
    const migrationsDir = path.resolve(__dirname, '../../infrastructure/database/migrations');
    expect(fs.existsSync(migrationsDir)).toBe(true);

    const files = fs.readdirSync(migrationsDir).filter(f => f.endsWith('.sql'));
    expect(files.length).toBeGreaterThan(0);
    expect(files).toContain('001_initial_schema.sql');

    const sqlContent = fs.readFileSync(path.join(migrationsDir, '001_initial_schema.sql'), 'utf-8');

    // Validate required tables exist in DDL
    expect(sqlContent).toContain('CREATE TABLE IF NOT EXISTS devices');
    expect(sqlContent).toContain('CREATE TABLE IF NOT EXISTS pairing_sessions');
    expect(sqlContent).toContain('CREATE TABLE IF NOT EXISTS pairings');
    expect(sqlContent).toContain('CREATE TABLE IF NOT EXISTS commands');
    expect(sqlContent).toContain('CREATE TABLE IF NOT EXISTS schema_migrations');

    // Validate required columns & constraints
    expect(sqlContent).toContain('device_id VARCHAR(32) UNIQUE NOT NULL');
    expect(sqlContent).toContain('pairing_code_hash VARCHAR(64) NOT NULL');
    expect(sqlContent).toContain('uq_pairings_controller_agent UNIQUE');
    expect(sqlContent).toContain('command_id VARCHAR(64) UNIQUE NOT NULL');
  });
});
