CREATE TABLE IF NOT EXISTS provider_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope TEXT NOT NULL CHECK (scope IN ('LOCAL_APP', 'CLOUD_SERVER')),
    provider TEXT NOT NULL,
    base_url TEXT NOT NULL,
    encrypted_secret BYTEA NOT NULL,
    secret_nonce BYTEA NOT NULL,
    key_version INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL CHECK (status IN ('UNASSIGNED', 'ACTIVE', 'INVALID', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_provider_credentials_scope
    ON provider_credentials (scope, provider, status);

CREATE TABLE IF NOT EXISTS model_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    display_name TEXT NOT NULL,
    capabilities_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    context_window INTEGER NOT NULL DEFAULT 32768,
    input_price NUMERIC(12, 6) NOT NULL DEFAULT 0,
    output_price NUMERIC(12, 6) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_model_definitions_provider
    ON model_definitions (provider, model);

CREATE TABLE IF NOT EXISTS model_bindings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES container_profiles (id) ON DELETE CASCADE,
    model_definition_id UUID NOT NULL REFERENCES model_definitions (id) ON DELETE CASCADE,
    credential_id UUID NOT NULL REFERENCES provider_credentials (id) ON DELETE RESTRICT,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'ERROR')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_model_bindings_profile
    ON model_bindings (profile_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_model_bindings_credential
    ON model_bindings (credential_id);

CREATE TABLE IF NOT EXISTS model_calls (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES sessions (id) ON DELETE SET NULL,
    turn_id UUID REFERENCES turns (id) ON DELETE SET NULL,
    binding_id UUID REFERENCES model_bindings (id) ON DELETE SET NULL,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    latency_ms BIGINT,
    status TEXT NOT NULL,
    error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_model_calls_session
    ON model_calls (session_id, created_at DESC);
