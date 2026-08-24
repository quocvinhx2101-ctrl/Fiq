CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE environments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    production BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workspace_id, name)
);

CREATE TABLE connections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    environment_id UUID NOT NULL REFERENCES environments(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    catalog_type VARCHAR(64) NOT NULL,
    catalog_uri VARCHAR(2048),
    warehouse_uri VARCHAR(2048),
    engine_type VARCHAR(64) NOT NULL DEFAULT 'LIVY',
    engine_uri VARCHAR(2048),
    secret_ref VARCHAR(512),
    options JSONB NOT NULL DEFAULT '{}',
    enabled BOOLEAN NOT NULL DEFAULT true,
    last_tested_at TIMESTAMPTZ,
    last_test_status VARCHAR(32),
    last_test_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workspace_id, name),
    CHECK (secret_ref IS NULL OR secret_ref !~* '(password|token|secret)=')
);

CREATE TABLE delta_tables (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    environment_id UUID NOT NULL REFERENCES environments(id) ON DELETE CASCADE,
    connection_id UUID NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
    catalog_name VARCHAR(255) NOT NULL,
    namespace_parts JSONB NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    qualified_name VARCHAR(1024) NOT NULL,
    location_uri VARCHAR(4096),
    access_mode VARCHAR(32) NOT NULL,
    current_version BIGINT NOT NULL DEFAULT -1,
    min_reader_version INTEGER NOT NULL DEFAULT 1,
    min_writer_version INTEGER NOT NULL DEFAULT 2,
    table_features JSONB NOT NULL DEFAULT '[]',
    partition_columns JSONB NOT NULL DEFAULT '[]',
    clustering_columns JSONB NOT NULL DEFAULT '[]',
    properties JSONB NOT NULL DEFAULT '{}',
    tags JSONB NOT NULL DEFAULT '{}',
    catalog_maintenance_allowed BOOLEAN NOT NULL DEFAULT false,
    filesystem_visible_state_current BOOLEAN NOT NULL DEFAULT true,
    read_only_reason TEXT,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workspace_id, connection_id, qualified_name)
);

CREATE INDEX idx_delta_tables_scope ON delta_tables(workspace_id, environment_id, connection_id);
CREATE INDEX idx_delta_tables_name ON delta_tables(workspace_id, qualified_name);
CREATE INDEX idx_delta_tables_features ON delta_tables USING GIN(table_features);
CREATE INDEX idx_delta_tables_tags ON delta_tables USING GIN(tags);

CREATE TABLE health_assessments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    table_id UUID NOT NULL REFERENCES delta_tables(id) ON DELETE CASCADE,
    observed_version BIGINT NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    provenance VARCHAR(255) NOT NULL,
    completeness VARCHAR(32) NOT NULL,
    stale_reason TEXT,
    file_layout JSONB NOT NULL,
    deletion_vectors JSONB NOT NULL,
    transaction_log JSONB NOT NULL,
    storage_retention JSONB NOT NULL,
    clustering JSONB NOT NULL,
    protocol JSONB NOT NULL,
    debt_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_health_latest ON health_assessments(table_id, assessed_at DESC);
CREATE INDEX idx_health_workspace ON health_assessments(workspace_id, assessed_at DESC);

CREATE TABLE health_issues (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id UUID NOT NULL REFERENCES health_assessments(id) ON DELETE CASCADE,
    code VARCHAR(128) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    dimension VARCHAR(64) NOT NULL,
    summary TEXT NOT NULL,
    recommendation TEXT,
    UNIQUE (assessment_id, code)
);

CREATE TABLE policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT true,
    selector JSONB NOT NULL,
    cron_expression VARCHAR(128) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    maintenance_window JSONB NOT NULL,
    enabled_operations JSONB NOT NULL,
    operation_configs JSONB NOT NULL,
    max_bytes_per_run BIGINT NOT NULL DEFAULT 0,
    max_concurrent_operations INTEGER NOT NULL DEFAULT 1,
    require_approval_above_budget BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workspace_id, name)
);

CREATE TABLE operation_runs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    parent_run_id UUID REFERENCES operation_runs(id),
    table_id UUID NOT NULL REFERENCES delta_tables(id),
    policy_id UUID REFERENCES policies(id),
    operation_type VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    based_on_version BIGINT NOT NULL,
    estimated_bytes BIGINT NOT NULL DEFAULT 0,
    command_preview TEXT NOT NULL,
    reasons JSONB NOT NULL DEFAULT '[]',
    warnings JSONB NOT NULL DEFAULT '[]',
    approval_required BOOLEAN NOT NULL DEFAULT false,
    approved_by VARCHAR(255),
    approved_at TIMESTAMPTZ,
    idempotency_key VARCHAR(255) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMPTZ,
    lease_owner VARCHAR(255),
    lease_expires_at TIMESTAMPTZ,
    external_job_id VARCHAR(255),
    result JSONB,
    error_code VARCHAR(128),
    error_message TEXT,
    planned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    queued_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workspace_id, idempotency_key)
);

CREATE INDEX idx_operations_queue ON operation_runs(state, next_attempt_at, planned_at);
CREATE INDEX idx_operations_table ON operation_runs(table_id, planned_at DESC);
CREATE INDEX idx_operations_workspace ON operation_runs(workspace_id, planned_at DESC);

CREATE TABLE operation_steps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_id UUID NOT NULL REFERENCES operation_runs(id) ON DELETE CASCADE,
    step_order INTEGER NOT NULL,
    name VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (operation_id, step_order)
);

CREATE TABLE approvals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL REFERENCES operation_runs(id) ON DELETE CASCADE,
    decision VARCHAR(32) NOT NULL,
    principal VARCHAR(255) NOT NULL,
    comment TEXT,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE schedule_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    policy_id UUID NOT NULL REFERENCES policies(id) ON DELETE CASCADE,
    table_id UUID NOT NULL REFERENCES delta_tables(id) ON DELETE CASCADE,
    operation_type VARCHAR(64) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    claim_owner VARCHAR(255),
    attempts INTEGER NOT NULL DEFAULT 0,
    UNIQUE (policy_id, table_id, operation_type, due_at)
);

CREATE INDEX idx_schedule_due ON schedule_queue(due_at) WHERE claimed_at IS NULL;

CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID REFERENCES workspaces(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    key_prefix VARCHAR(24) NOT NULL,
    key_hash VARCHAR(128) NOT NULL UNIQUE,
    roles JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workspace_id, name)
);

CREATE TABLE webhooks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    target_uri VARCHAR(2048) NOT NULL,
    secret_ref VARCHAR(512) NOT NULL,
    event_types JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workspace_id, name)
);

CREATE TABLE audit_events (
    id BIGSERIAL PRIMARY KEY,
    workspace_id UUID REFERENCES workspaces(id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    event_type VARCHAR(128) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    principal VARCHAR(255),
    resource_type VARCHAR(64),
    resource_id VARCHAR(255),
    request_id VARCHAR(128),
    remote_address VARCHAR(128),
    details JSONB NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_audit_scope ON audit_events(workspace_id, occurred_at DESC);
CREATE INDEX idx_audit_type ON audit_events(event_type, occurred_at DESC);

CREATE FUNCTION reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_no_update
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

INSERT INTO workspaces(id, name, timezone)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default workspace', 'UTC');

INSERT INTO environments(id, workspace_id, name, production)
VALUES (
    '00000000-0000-0000-0000-000000000010',
    '00000000-0000-0000-0000-000000000001',
    'development',
    false
);

