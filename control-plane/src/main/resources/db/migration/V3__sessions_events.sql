CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES container_profiles (id) ON DELETE CASCADE,
    workspace_id UUID REFERENCES workspaces (id) ON DELETE SET NULL,
    title TEXT NOT NULL,
    model_binding_id TEXT,
    last_seq BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sessions_profile
    ON sessions (profile_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS turns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    client_message_id TEXT,
    status TEXT NOT NULL CHECK (status IN (
        'QUEUED',
        'WAITING_RUNTIME',
        'RUNNING',
        'WAITING_APPROVAL',
        'COMPLETED',
        'FAILED',
        'INTERRUPTED',
        'CANCELLED'
    )),
    queue_order BIGSERIAL NOT NULL,
    created_seq BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_code TEXT,
    error_message TEXT,
    UNIQUE (session_id, client_message_id)
);

CREATE INDEX IF NOT EXISTS idx_turns_session_queue
    ON turns (session_id, queue_order);

CREATE INDEX IF NOT EXISTS idx_turns_status_queue
    ON turns (status, queue_order);

CREATE TABLE IF NOT EXISTS messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    turn_id UUID REFERENCES turns (id) ON DELETE SET NULL,
    role TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system', 'tool')),
    content TEXT NOT NULL,
    content_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_messages_session
    ON messages (session_id, created_at);

CREATE TABLE IF NOT EXISTS message_search (
    message_id UUID PRIMARY KEY REFERENCES messages (id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_message_search_tsv
    ON message_search USING GIN (tsv);

CREATE INDEX IF NOT EXISTS idx_message_search_content_trgm
    ON message_search USING GIN (content gin_trgm_ops);

CREATE TABLE IF NOT EXISTS events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    seq BIGINT NOT NULL,
    turn_id UUID REFERENCES turns (id) ON DELETE SET NULL,
    message_id UUID REFERENCES messages (id) ON DELETE SET NULL,
    type TEXT NOT NULL,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_events_session_seq
    ON events (session_id, seq);
