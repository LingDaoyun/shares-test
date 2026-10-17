CREATE TABLE short_term_daily_snapshot (
    trade_date DATE PRIMARY KEY,
    status VARCHAR(24) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    payload_json TEXT NOT NULL
);
