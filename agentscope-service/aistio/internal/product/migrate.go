// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

import (
	"context"
	"fmt"
	"log"
)

const migrationSQL = `
CREATE SCHEMA IF NOT EXISTS cp;
SET search_path TO cp;

CREATE TABLE IF NOT EXISTS memory_authority_fence (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    authority TEXT NOT NULL DEFAULT 'go' CHECK (authority IN ('go','frozen','rust')),
    data_sha256 TEXT,
    activated_at TIMESTAMPTZ,
    activated_by TEXT
);
INSERT INTO memory_authority_fence(singleton,authority)
VALUES(true,'go') ON CONFLICT(singleton) DO NOTHING;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='cp.memory_authority_fence'::regclass
        AND conname='memory_authority_fence_authority_check'
        AND pg_get_constraintdef(oid) NOT LIKE '%frozen%') THEN
        ALTER TABLE cp.memory_authority_fence DROP CONSTRAINT memory_authority_fence_authority_check;
        ALTER TABLE cp.memory_authority_fence ADD CONSTRAINT memory_authority_fence_authority_check
            CHECK (authority IN ('go','frozen','rust'));
    END IF;
END $$;
CREATE TABLE IF NOT EXISTS memory_authority_fence_history (
    id BIGSERIAL PRIMARY KEY,
    action TEXT NOT NULL CHECK (action IN ('freeze','activate','abort')),
    data_sha256 TEXT,
    actor TEXT NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE memory_authority_fence_history ALTER COLUMN data_sha256 DROP NOT NULL;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='cp.memory_authority_fence_history'::regclass
        AND conname='memory_authority_fence_history_action_check'
        AND pg_get_constraintdef(oid) NOT LIKE '%freeze%') THEN
        ALTER TABLE cp.memory_authority_fence_history DROP CONSTRAINT memory_authority_fence_history_action_check;
        ALTER TABLE cp.memory_authority_fence_history ADD CONSTRAINT memory_authority_fence_history_action_check
            CHECK (action IN ('freeze','activate','abort'));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS users (
    user_id       TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    roles_csv     TEXT NOT NULL DEFAULT 'user',
    created_at    BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS agents (
    row_id            BIGSERIAL PRIMARY KEY,
    owner_id          TEXT NOT NULL,
    agent_id          TEXT NOT NULL,
    workspace_path    TEXT,
    name              TEXT NOT NULL,
    description       TEXT,
    sys_prompt        TEXT,
    model             TEXT,
    max_iters         INT,
    tools_json        TEXT,
    mcp_servers_json  TEXT,
    skills_json       TEXT,
    multiagent_json   TEXT,
    head_version      INT NOT NULL DEFAULT 1,
    archived_at       BIGINT,
    created_at        BIGINT NOT NULL,
    updated_at        BIGINT NOT NULL,
    UNIQUE (owner_id, agent_id)
);

CREATE TABLE IF NOT EXISTS agent_versions (
    row_id         BIGSERIAL PRIMARY KEY,
    owner_id       TEXT NOT NULL,
    agent_id       TEXT NOT NULL,
    version        INT NOT NULL,
    snapshot_json  TEXT NOT NULL,
    created_at     BIGINT NOT NULL,
    UNIQUE (owner_id, agent_id, version)
);

CREATE TABLE IF NOT EXISTS environments (
    row_id         BIGSERIAL PRIMARY KEY,
    environment_id TEXT NOT NULL UNIQUE,
    owner_id       TEXT NOT NULL,
    name           TEXT NOT NULL,
    type           TEXT NOT NULL DEFAULT 'local',
    config_json    TEXT,
    api_key_hash   TEXT,
    archived_at    BIGINT,
    created_at     BIGINT NOT NULL,
    updated_at     BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    row_id                BIGSERIAL PRIMARY KEY,
    session_id            TEXT NOT NULL UNIQUE,
    owner_id              TEXT NOT NULL,
    agent_id              TEXT NOT NULL,
    agent_owner_id        TEXT,
    agent_version         INT,
    agent_ref_type        TEXT,
    agent_overrides_json  TEXT,
    environment_id        TEXT NOT NULL,
    external_key          TEXT,
    memory_store_ids_json TEXT,
    vault_ids_json        TEXT,
    resources_json        TEXT,
    status                TEXT NOT NULL DEFAULT 'idle',
    stop_reason_json      TEXT,
    version               INT NOT NULL DEFAULT 1,
    archived_at           BIGINT,
    created_at            BIGINT NOT NULL,
    updated_at            BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_owner ON sessions (owner_id);
CREATE INDEX IF NOT EXISTS idx_sessions_agent ON sessions (owner_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_sessions_external ON sessions (owner_id, agent_id, environment_id, external_key);

CREATE TABLE IF NOT EXISTS memory_stores (
    row_id      BIGSERIAL PRIMARY KEY,
    store_id    TEXT NOT NULL UNIQUE,
    owner_id    TEXT NOT NULL,
    name        TEXT NOT NULL,
    description TEXT,
    archived_at BIGINT,
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS memories (
    row_id       BIGSERIAL PRIMARY KEY,
    memory_id    TEXT NOT NULL UNIQUE,
    store_id     TEXT NOT NULL,
    path         TEXT NOT NULL,
    content      TEXT NOT NULL DEFAULT '',
    head_version INT NOT NULL DEFAULT 1,
    created_at   BIGINT NOT NULL,
    updated_at   BIGINT NOT NULL,
    UNIQUE (store_id, path)
);

CREATE TABLE IF NOT EXISTS memory_versions (
    row_id     BIGSERIAL PRIMARY KEY,
    memory_id  TEXT NOT NULL,
    version    INT NOT NULL,
    content    TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (memory_id, version)
);

CREATE TABLE IF NOT EXISTS vaults (
    row_id       BIGSERIAL PRIMARY KEY,
    vault_id     TEXT NOT NULL UNIQUE,
    owner_id     TEXT NOT NULL,
    display_name TEXT NOT NULL,
    metadata_json TEXT,
    archived_at  BIGINT,
    created_at   BIGINT NOT NULL,
    updated_at   BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS vault_credentials (
    row_id      BIGSERIAL PRIMARY KEY,
    credential_id TEXT NOT NULL UNIQUE,
    vault_id    TEXT NOT NULL,
    type        TEXT NOT NULL,
    label       TEXT NOT NULL,
    target      TEXT NOT NULL,
    ciphertext  BYTEA NOT NULL,
    created_at  BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS deployments (
    row_id           BIGSERIAL PRIMARY KEY,
    deployment_id    TEXT NOT NULL UNIQUE,
    owner_id         TEXT NOT NULL,
    name             TEXT NOT NULL,
    agent_id         TEXT NOT NULL,
    agent_version    INT,
    environment_id   TEXT NOT NULL,
    trigger_type     TEXT NOT NULL,
    cron_expression  TEXT,
    webhook_token    TEXT,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_at      BIGINT,
    last_session_id  TEXT,
    last_status      TEXT,
    last_hands_stats_json TEXT,
    archived_at      BIGINT,
    created_at       BIGINT NOT NULL,
    updated_at       BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_deployments_webhook ON deployments (webhook_token);

CREATE TABLE IF NOT EXISTS resource_shares (
    row_id          BIGSERIAL PRIMARY KEY,
    resource_type   TEXT NOT NULL,
    resource_id     TEXT NOT NULL,
    grantee_user_id TEXT NOT NULL,
    permission      TEXT NOT NULL DEFAULT 'RUN',
    created_at      BIGINT NOT NULL,
    created_by      TEXT,
    UNIQUE (resource_type, resource_id, grantee_user_id)
);

-- A row lock makes the cutover transaction wait for in-flight memory writes.
-- Once the fence commits, even a request that passed an earlier HTTP guard
-- cannot mutate the Go source tables. Non-memory shares remain usable.
CREATE OR REPLACE FUNCTION cp.require_go_memory_authority() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    current_authority TEXT;
BEGIN
    IF TG_TABLE_NAME = 'resource_shares' THEN
        IF TG_OP = 'INSERT' THEN
            IF NEW.resource_type NOT IN ('memory', 'memory_store') THEN
                RETURN NEW;
            END IF;
        ELSIF TG_OP = 'DELETE' THEN
            IF OLD.resource_type NOT IN ('memory', 'memory_store') THEN
                RETURN OLD;
            END IF;
        ELSE
            IF OLD.resource_type NOT IN ('memory', 'memory_store')
                AND NEW.resource_type NOT IN ('memory', 'memory_store') THEN
                RETURN NEW;
            END IF;
        END IF;
    END IF;
    SELECT authority INTO current_authority
        FROM cp.memory_authority_fence WHERE singleton=true FOR SHARE;
    IF current_authority IS DISTINCT FROM 'go' THEN
        RAISE EXCEPTION 'Go memory source is fenced for Rust authority' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS memory_authority_write_guard ON memory_stores;
CREATE TRIGGER memory_authority_write_guard BEFORE INSERT OR UPDATE OR DELETE ON memory_stores
    FOR EACH ROW EXECUTE FUNCTION cp.require_go_memory_authority();
DROP TRIGGER IF EXISTS memory_authority_write_guard ON memories;
CREATE TRIGGER memory_authority_write_guard BEFORE INSERT OR UPDATE OR DELETE ON memories
    FOR EACH ROW EXECUTE FUNCTION cp.require_go_memory_authority();
DROP TRIGGER IF EXISTS memory_authority_write_guard ON memory_versions;
CREATE TRIGGER memory_authority_write_guard BEFORE INSERT OR UPDATE OR DELETE ON memory_versions
    FOR EACH ROW EXECUTE FUNCTION cp.require_go_memory_authority();
DROP TRIGGER IF EXISTS memory_authority_write_guard ON resource_shares;
CREATE TRIGGER memory_authority_write_guard BEFORE INSERT OR UPDATE OR DELETE ON resource_shares
    FOR EACH ROW EXECUTE FUNCTION cp.require_go_memory_authority();

CREATE TABLE IF NOT EXISTS channels (
    channel_id       TEXT PRIMARY KEY,
    owner_id         TEXT NOT NULL,
    type             TEXT NOT NULL,
    dm_scope         TEXT,
    default_agent_id TEXT,
    disabled         BOOLEAN NOT NULL DEFAULT FALSE,
    properties_json  TEXT,
    bindings_json    TEXT,
    runtime_started  BOOLEAN NOT NULL DEFAULT FALSE,
    runtime_error    TEXT,
    runtime_updated_at BIGINT,
    created_at       BIGINT NOT NULL,
    updated_at       BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_channels_owner ON channels (owner_id);

CREATE TABLE IF NOT EXISTS agent_bindings (
    row_id         BIGSERIAL PRIMARY KEY,
    owner_id       TEXT NOT NULL,
    agent_id       TEXT NOT NULL,
    channel_id     TEXT NOT NULL,
    binding_index  INT NOT NULL,
    tier           TEXT NOT NULL,
    payload_json   TEXT,
    created_at     BIGINT NOT NULL,
    UNIQUE (owner_id, agent_id, channel_id, binding_index)
);
CREATE INDEX IF NOT EXISTS idx_agent_bindings_agent ON agent_bindings (owner_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_agent_bindings_channel ON agent_bindings (owner_id, channel_id);

CREATE TABLE IF NOT EXISTS files (
    row_id       BIGSERIAL PRIMARY KEY,
    file_id      TEXT NOT NULL UNIQUE,
    owner_id     TEXT NOT NULL,
    filename     TEXT NOT NULL,
    content_type TEXT NOT NULL DEFAULT 'text/plain',
    size_bytes   BIGINT NOT NULL DEFAULT 0,
    content      TEXT NOT NULL DEFAULT '',
    created_at   BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_files_owner ON files (owner_id);
`

const migrationAlterSQL = `
ALTER TABLE channels ADD COLUMN IF NOT EXISTS runtime_started BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE channels ADD COLUMN IF NOT EXISTS runtime_error TEXT;
ALTER TABLE channels ADD COLUMN IF NOT EXISTS runtime_updated_at BIGINT;

ALTER TABLE vault_credentials ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1;

ALTER TABLE agents ADD COLUMN IF NOT EXISTS workspace_id TEXT;

CREATE TABLE IF NOT EXISTS workspaces (
    row_id            BIGSERIAL PRIMARY KEY,
    owner_id          TEXT NOT NULL,
    workspace_id      TEXT NOT NULL,
    name              TEXT NOT NULL,
    description       TEXT,
    tools_json        TEXT,
    mcp_servers_json  TEXT,
    skills_json       TEXT,
    head_version      INT NOT NULL DEFAULT 1,
    archived_at       BIGINT,
    created_at        BIGINT NOT NULL,
    updated_at        BIGINT NOT NULL,
    UNIQUE (owner_id, workspace_id)
);
CREATE INDEX IF NOT EXISTS idx_workspaces_owner ON workspaces (owner_id);

CREATE TABLE IF NOT EXISTS workspace_files (
    row_id       BIGSERIAL PRIMARY KEY,
    owner_id     TEXT NOT NULL,
    scope_type   TEXT NOT NULL,
    scope_id     TEXT NOT NULL,
    path         TEXT NOT NULL,
    content      TEXT NOT NULL DEFAULT '',
    updated_at   BIGINT NOT NULL,
    UNIQUE (owner_id, scope_type, scope_id, path)
);
CREATE INDEX IF NOT EXISTS idx_workspace_files_scope ON workspace_files (owner_id, scope_type, scope_id);

CREATE TABLE IF NOT EXISTS marketplaces (
    row_id          BIGSERIAL PRIMARY KEY,
    owner_id        TEXT NOT NULL,
    marketplace_id  TEXT NOT NULL,
    name            TEXT NOT NULL,
    type            TEXT NOT NULL,
    config_json     TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL,
    UNIQUE (owner_id, marketplace_id)
);
CREATE INDEX IF NOT EXISTS idx_marketplaces_owner ON marketplaces (owner_id);

-- Agent session-default mounts (merged when creating sessions if caller omits them).
ALTER TABLE agents ADD COLUMN IF NOT EXISTS default_environment_id TEXT;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS default_vault_ids_json TEXT;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS default_memory_store_ids_json TEXT;

-- Last accepted physical Managed AgentTask scope for monotonic runtime status
-- projection. This lives with the product Session because the runtime Store may
-- use a different PostgreSQL database or the in-memory development driver.
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS runtime_agent_task_id TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS runtime_attempt_id TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS runtime_dispatch_generation BIGINT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS runtime_turn_id TEXT;

-- Deployment trigger retries must resolve to one durable product session.
-- Restrict uniqueness to the new trigger keys so historical user session keys
-- retain their existing contract.
CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_deployment_external_unique
    ON sessions (owner_id, agent_id, environment_id, external_key)
    WHERE archived_at IS NULL AND (external_key LIKE 'webhook|%' OR external_key LIKE 'cron|%');
CREATE INDEX IF NOT EXISTS idx_deployments_last_session
    ON deployments (last_session_id) WHERE last_session_id IS NOT NULL;
`

func migrate(ctx context.Context, db *DB) error {
	log.Printf("running cp schema migration")
	if _, err := db.Pool.Exec(ctx, migrationSQL+channelWorkMigrationSQL+oauthMigrationSQL+workspacePublicationMigration); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	if _, err := db.Pool.Exec(ctx, migrationAlterSQL); err != nil {
		return fmt.Errorf("migrate alter: %w", err)
	}
	if _, err := db.Pool.Exec(ctx, accountManagementSQL); err != nil {
		return fmt.Errorf("migrate accounts: %w", err)
	}
	log.Printf("cp schema migration complete")
	return nil
}
