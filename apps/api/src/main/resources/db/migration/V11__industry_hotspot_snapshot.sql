CREATE TABLE industry_hotspot_snapshot (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    snapshot_json JSONB NOT NULL,
    analyzed_news_hash VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT industry_hotspot_snapshot_status CHECK (
        snapshot_json ->> 'status' IN ('IDLE', 'RUNNING', 'READY', 'PARTIAL', 'FAILED')
    )
);
