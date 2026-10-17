CREATE TABLE five_day_scan (
  scan_id VARCHAR(36) PRIMARY KEY,
  signal_date DATE NOT NULL,
  rule_version VARCHAR(80) NOT NULL,
  include_growth BOOLEAN NOT NULL,
  include_star BOOLEAN NOT NULL,
  forward_eligible BOOLEAN NOT NULL,
  snapshot_json JSONB NOT NULL,
  outcomes_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
  outcomes_as_of DATE,
  outcomes_updated_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT uk_five_day_scan_scope UNIQUE (signal_date, rule_version, include_growth, include_star)
);

CREATE INDEX idx_five_day_scan_history
  ON five_day_scan (rule_version, signal_date DESC, captured_at DESC, scan_id DESC);
