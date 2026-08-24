CREATE TABLE assessment_dimensions (
    assessment_id UUID NOT NULL REFERENCES health_assessments(id) ON DELETE CASCADE,
    dimension VARCHAR(64) NOT NULL,
    completeness VARCHAR(32) NOT NULL,
    provenance VARCHAR(255) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    observed_version BIGINT NOT NULL,
    incomplete_reason TEXT,
    facts JSONB NOT NULL,
    PRIMARY KEY (assessment_id, dimension),
    CHECK (dimension IN (
      'FILE_LAYOUT', 'DELETION_VECTORS', 'TRANSACTION_LOG',
      'RETENTION', 'CLUSTERING', 'PROTOCOL')),
    CHECK (completeness IN ('COMPLETE', 'PARTIAL', 'STALE'))
);

CREATE INDEX idx_assessment_dimensions_latest
    ON assessment_dimensions(dimension, completeness, observed_at DESC);

CREATE TABLE file_size_distributions (
    assessment_id UUID PRIMARY KEY REFERENCES health_assessments(id) ON DELETE CASCADE,
    bucket_width_bytes BIGINT NOT NULL,
    bucket_counts JSONB NOT NULL,
    overflow_count BIGINT NOT NULL,
    median_value_bytes BIGINT NOT NULL,
    median_error_bound_bytes BIGINT NOT NULL,
    median_method VARCHAR(128) NOT NULL,
    CHECK (bucket_width_bytes = 1048576),
    CHECK (overflow_count >= 0)
);

CREATE TABLE tombstone_age_distributions (
    assessment_id UUID PRIMARY KEY REFERENCES health_assessments(id) ON DELETE CASCADE,
    bucket_upper_hours JSONB NOT NULL,
    bucket_counts JSONB NOT NULL
);

CREATE TABLE policy_evaluations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    policy_id UUID NOT NULL REFERENCES policies(id) ON DELETE CASCADE,
    table_id UUID NOT NULL REFERENCES delta_tables(id) ON DELETE CASCADE,
    assessment_id UUID NOT NULL REFERENCES health_assessments(id) ON DELETE CASCADE,
    operation_type VARCHAR(64) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    observations JSONB NOT NULL,
    policy_thresholds JSONB NOT NULL,
    conditions JSONB NOT NULL,
    blockers JSONB NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (decision IN ('ELIGIBLE', 'NOT_ELIGIBLE', 'BLOCKED', 'APPROVAL_REQUIRED'))
);

CREATE INDEX idx_policy_evaluation_table
    ON policy_evaluations(workspace_id, table_id, evaluated_at DESC);
