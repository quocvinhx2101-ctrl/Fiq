ALTER TABLE delta_tables ADD COLUMN source_type VARCHAR(16);
ALTER TABLE delta_tables ADD COLUMN execution_target_type VARCHAR(16);
ALTER TABLE delta_tables ADD COLUMN execution_target JSONB;
ALTER TABLE delta_tables ADD COLUMN execution_target_fingerprint VARCHAR(64);
ALTER TABLE delta_tables ADD COLUMN display_identity VARCHAR(1024);
ALTER TABLE delta_tables ADD COLUMN discovery_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE delta_tables ADD COLUMN sample BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE delta_tables ADD COLUMN last_seen_at TIMESTAMPTZ;
ALTER TABLE delta_tables ADD COLUMN missing_since TIMESTAMPTZ;

UPDATE delta_tables t
SET source_type = CASE WHEN t.location_uri IS NOT NULL THEN 'PATH' ELSE COALESCE(c.catalog_type, 'HMS') END,
    execution_target_type = CASE WHEN t.location_uri IS NOT NULL THEN 'PATH' ELSE 'CATALOG' END,
    execution_target = CASE
      WHEN t.location_uri IS NOT NULL THEN jsonb_build_object('type', 'PATH', 'uri', t.location_uri)
      ELSE jsonb_build_object(
        'type', 'CATALOG', 'catalog', t.catalog_name,
        'namespace', t.namespace_parts, 'table', t.table_name)
    END,
    display_identity = t.qualified_name,
    last_seen_at = t.discovered_at
FROM connections c
WHERE c.id=t.connection_id;

UPDATE delta_tables
SET execution_target_fingerprint = encode(digest(execution_target::text, 'sha256'), 'hex');

ALTER TABLE delta_tables ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE delta_tables ALTER COLUMN execution_target_type SET NOT NULL;
ALTER TABLE delta_tables ALTER COLUMN execution_target SET NOT NULL;
ALTER TABLE delta_tables ALTER COLUMN execution_target_fingerprint SET NOT NULL;
ALTER TABLE delta_tables ALTER COLUMN display_identity SET NOT NULL;
ALTER TABLE delta_tables ADD CONSTRAINT chk_delta_source_type CHECK (source_type IN ('PATH','HMS','GLUE'));
ALTER TABLE delta_tables ADD CONSTRAINT chk_delta_target_type CHECK (execution_target_type IN ('PATH','CATALOG'));
CREATE UNIQUE INDEX uq_delta_target ON delta_tables(workspace_id, connection_id, execution_target_fingerprint);
CREATE INDEX idx_delta_discovery_state ON delta_tables(workspace_id, connection_id, discovery_status);

CREATE TABLE discovery_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    connection_id UUID NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
    state VARCHAR(32) NOT NULL,
    root_uri VARCHAR(4096),
    max_depth INTEGER NOT NULL DEFAULT 4,
    max_tables INTEGER NOT NULL DEFAULT 10000,
    timeout_seconds INTEGER NOT NULL DEFAULT 1800,
    tables_found INTEGER NOT NULL DEFAULT 0,
    tables_missing INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(128),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CHECK (max_depth BETWEEN 0 AND 32),
    CHECK (max_tables BETWEEN 1 AND 100000),
    CHECK (timeout_seconds BETWEEN 1 AND 86400)
);
CREATE INDEX idx_discovery_runs_scope ON discovery_runs(workspace_id, started_at DESC);

ALTER TABLE health_issues ADD COLUMN severity_rank SMALLINT
    GENERATED ALWAYS AS (
      CASE severity
        WHEN 'CRITICAL' THEN 3
        WHEN 'WARNING' THEN 2
        WHEN 'INFO' THEN 1
        WHEN 'HEALTHY' THEN 0
        ELSE -1
      END
    ) STORED;
ALTER TABLE health_issues ADD CONSTRAINT chk_health_issue_severity
    CHECK (severity IN ('HEALTHY','INFO','WARNING','CRITICAL'));
CREATE INDEX idx_health_issue_rank ON health_issues(assessment_id, severity_rank DESC);
