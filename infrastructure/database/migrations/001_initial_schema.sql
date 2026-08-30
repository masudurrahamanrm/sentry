-- 001_initial_schema.sql
-- Kinetix + Sentry Core Database Schema

-- Enable UUID extension if available
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. Devices Table
CREATE TABLE IF NOT EXISTS devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id VARCHAR(32) UNIQUE NOT NULL,
    device_name VARCHAR(100) NOT NULL,
    platform VARCHAR(30) NOT NULL,
    os_version VARCHAR(50) NOT NULL,
    app_version VARCHAR(50) NOT NULL,
    public_key TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_devices_device_id ON devices(device_id);
CREATE INDEX IF NOT EXISTS idx_devices_status ON devices(status);

-- 2. Pairing Sessions Table
CREATE TABLE IF NOT EXISTS pairing_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_device_id VARCHAR(32) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    agent_device_id VARCHAR(32) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    pairing_code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    confirmed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_pairing_sessions_agent ON pairing_sessions(agent_device_id);
CREATE INDEX IF NOT EXISTS idx_pairing_sessions_controller ON pairing_sessions(controller_device_id);
CREATE INDEX IF NOT EXISTS idx_pairing_sessions_expires_at ON pairing_sessions(expires_at);
CREATE INDEX IF NOT EXISTS idx_pairing_sessions_status ON pairing_sessions(status);

-- 3. Pairings Table (Authenticated relationship)
CREATE TABLE IF NOT EXISTS pairings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_device_id VARCHAR(32) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    agent_device_id VARCHAR(32) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_pairings_controller_agent UNIQUE (controller_device_id, agent_device_id)
);

CREATE INDEX IF NOT EXISTS idx_pairings_controller ON pairings(controller_device_id);
CREATE INDEX IF NOT EXISTS idx_pairings_agent ON pairings(agent_device_id);
CREATE INDEX IF NOT EXISTS idx_pairings_status ON pairings(status);

-- 4. Commands Table
CREATE TABLE IF NOT EXISTS commands (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pairing_id UUID NOT NULL REFERENCES pairings(id) ON DELETE CASCADE,
    command_id VARCHAR(64) UNIQUE NOT NULL,
    command_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_commands_pairing_id ON commands(pairing_id);
CREATE INDEX IF NOT EXISTS idx_commands_command_id ON commands(command_id);
CREATE INDEX IF NOT EXISTS idx_commands_status ON commands(status);
CREATE INDEX IF NOT EXISTS idx_commands_created_at ON commands(created_at);

-- 5. Migration History Table
CREATE TABLE IF NOT EXISTS schema_migrations (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
