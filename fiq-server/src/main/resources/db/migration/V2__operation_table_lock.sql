-- One table may have only one operation admitted to the execution queue. This database
-- invariant complements SKIP LOCKED claims and prevents two server replicas from submitting
-- concurrent maintenance for the same Delta table.
CREATE UNIQUE INDEX uq_operation_table_execution
    ON operation_runs(table_id)
    WHERE state IN ('QUEUED', 'RUNNING', 'CANCELLING');
