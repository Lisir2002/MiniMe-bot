CREATE TABLE IF NOT EXISTS container_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind TEXT NOT NULL CHECK (kind IN ('LOCAL', 'CLOUD')),
    name TEXT NOT NULL,
    image_ref TEXT NOT NULL,
    image_digest TEXT,
    resource_policy_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    components_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    packages_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_container_profiles_kind
    ON container_profiles (kind, created_at);

CREATE TABLE IF NOT EXISTS container_instances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES container_profiles (id) ON DELETE CASCADE,
    container_id TEXT,
    phase TEXT NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT false,
    last_activity_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (profile_id)
);

CREATE INDEX IF NOT EXISTS idx_container_instances_phase
    ON container_instances (phase);

CREATE TABLE IF NOT EXISTS workspaces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES container_profiles (id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    root_path TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (profile_id, name)
);

CREATE TABLE IF NOT EXISTS deployment_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES container_profiles (id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    steps_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_deployment_jobs_status
    ON deployment_jobs (status, created_at);
