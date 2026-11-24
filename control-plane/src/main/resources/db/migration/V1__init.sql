CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS server_settings (
    server_id TEXT PRIMARY KEY,
    server_name TEXT NOT NULL,
    timezone TEXT NOT NULL DEFAULT 'UTC',
    approval_timeout_seconds INTEGER NOT NULL DEFAULT 600,
    notification_settings_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS server_device (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    public_key TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    device_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_server_device_active
    ON server_device (created_at)
    WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS pairing_tokens (
    token_hash TEXT PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pairing_tokens_expires
    ON pairing_tokens (expires_at)
    WHERE used_at IS NULL;
