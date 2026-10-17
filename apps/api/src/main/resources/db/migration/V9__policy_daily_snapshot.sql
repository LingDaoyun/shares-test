CREATE TABLE policy_daily_snapshot (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    snapshot_json JSONB NOT NULL,
    last_automatic_attempt_date DATE,
    last_successful_date DATE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT policy_daily_snapshot_status CHECK (
        snapshot_json ->> 'status' IN ('IDLE', 'RUNNING', 'READY', 'PARTIAL', 'FAILED')
    )
);
