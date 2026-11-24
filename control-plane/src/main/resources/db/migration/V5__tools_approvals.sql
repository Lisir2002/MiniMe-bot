CREATE TABLE IF NOT EXISTS approvals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    turn_id UUID NOT NULL REFERENCES turns (id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'DENIED', 'EXPIRED', 'CANCELLED')),
    request_json JSONB NOT NULL,
    decision TEXT,
    reason TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_approvals_pending
    ON approvals (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_approvals_turn
    ON approvals (turn_id, created_at DESC);
