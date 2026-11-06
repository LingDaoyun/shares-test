CREATE TABLE financial_news_snapshot (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    snapshot_json JSONB NOT NULL,
    media_last_attempt_at TIMESTAMP WITH TIME ZONE,
    official_last_attempt_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT financial_news_snapshot_status CHECK (
        snapshot_json ->> 'status' IN ('IDLE', 'RUNNING', 'READY', 'PARTIAL', 'FAILED')
    )
);
