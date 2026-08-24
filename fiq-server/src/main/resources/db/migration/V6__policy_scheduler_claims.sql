ALTER TABLE policy_schedule_state ADD COLUMN claim_owner VARCHAR(255);
ALTER TABLE policy_schedule_state ADD COLUMN claim_expires_at TIMESTAMPTZ;
ALTER TABLE policy_schedule_state ADD COLUMN error_message TEXT;

CREATE INDEX idx_policy_schedule_recovery
    ON policy_schedule_state(state, claim_expires_at);
