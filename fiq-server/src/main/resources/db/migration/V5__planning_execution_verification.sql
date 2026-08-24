ALTER TABLE policy_evaluations ADD COLUMN operation_id UUID
    REFERENCES operation_runs(id) ON DELETE CASCADE;
CREATE UNIQUE INDEX uq_policy_evaluation_operation
    ON policy_evaluations(operation_id) WHERE operation_id IS NOT NULL;

ALTER TABLE operation_runs ADD COLUMN execution_target_snapshot JSONB;
UPDATE operation_runs o
SET execution_target_snapshot=t.execution_target
FROM delta_tables t
WHERE t.id=o.table_id;
ALTER TABLE operation_runs ALTER COLUMN execution_target_snapshot SET NOT NULL;

ALTER TABLE operation_runs ADD COLUMN planning_evidence JSONB NOT NULL DEFAULT '{}';
ALTER TABLE operation_runs ADD COLUMN preflight_evidence JSONB NOT NULL DEFAULT '{}';
ALTER TABLE operation_runs ADD COLUMN approval_evidence_hash VARCHAR(64);
ALTER TABLE operation_runs ADD COLUMN structured_result_uri VARCHAR(4096);
ALTER TABLE operation_runs ADD COLUMN structured_result_checksum VARCHAR(64);
ALTER TABLE operation_runs ADD COLUMN maintenance_applied BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE operation_runs ADD COLUMN verification_evidence JSONB NOT NULL DEFAULT '{}';
ALTER TABLE operation_runs ADD COLUMN replacement_operation_id UUID REFERENCES operation_runs(id);

ALTER TABLE approvals ADD COLUMN evidence_hash VARCHAR(64);
ALTER TABLE approvals ADD COLUMN evidence JSONB NOT NULL DEFAULT '{}';

CREATE TABLE policy_schedule_state (
    policy_id UUID NOT NULL REFERENCES policies(id) ON DELETE CASCADE,
    table_id UUID NOT NULL REFERENCES delta_tables(id) ON DELETE CASCADE,
    operation_type VARCHAR(64) NOT NULL,
    scheduled_time_utc TIMESTAMPTZ NOT NULL,
    fire_key VARCHAR(255) NOT NULL,
    state VARCHAR(32) NOT NULL,
    operation_id UUID REFERENCES operation_runs(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (policy_id, table_id, operation_type, scheduled_time_utc),
    UNIQUE (fire_key)
);
