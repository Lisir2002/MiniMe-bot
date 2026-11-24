CREATE TABLE IF NOT EXISTS turn_checkpoints (
    turn_id UUID PRIMARY KEY REFERENCES turns (id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_turn_checkpoints_session
    ON turn_checkpoints (session_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS turn_leases (
    turn_id UUID PRIMARY KEY REFERENCES turns (id) ON DELETE CASCADE,
    lease_owner TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
