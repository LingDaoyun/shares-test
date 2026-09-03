CREATE TABLE leader_daily_snapshot (
  id UUID PRIMARY KEY,
  trade_date DATE NOT NULL,
  rule_version VARCHAR(64) NOT NULL,
  quote_trade_date DATE NOT NULL,
  quote_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
  dragon_tiger_date DATE,
  market_summary_json JSONB NOT NULL,
  directions_json JSONB NOT NULL,
  potential_candidates_json JSONB NOT NULL,
  competition_candidates_json JSONB NOT NULL,
  source_status_json JSONB NOT NULL,
  data_gaps_json JSONB NOT NULL,
  snapshot_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  UNIQUE (trade_date, rule_version)
);

CREATE TABLE leader_outcome (
  snapshot_id UUID NOT NULL REFERENCES leader_daily_snapshot(id) ON DELETE CASCADE,
  lane VARCHAR(16) NOT NULL,
  symbol VARCHAR(6) NOT NULL,
  trade_date DATE NOT NULL,
  rule_version VARCHAR(64) NOT NULL,
  grade VARCHAR(1) NOT NULL,
  direction_heat VARCHAR(8) NOT NULL,
  status VARCHAR(16) NOT NULL,
  success BOOLEAN,
  return_t1 NUMERIC(12,6),
  return_t3 NUMERIC(12,6),
  return_t5 NUMERIC(12,6),
  max_drawdown NUMERIC(12,6),
  closed_limit_up_count INTEGER,
  max_board_count INTEGER,
  evaluated_through_date DATE,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (snapshot_id, lane, symbol)
);

CREATE TABLE leader_ai_review (
  id UUID PRIMARY KEY,
  trade_date DATE NOT NULL,
  lane VARCHAR(16) NOT NULL,
  symbol VARCHAR(6) NOT NULL,
  rule_version VARCHAR(64) NOT NULL,
  snapshot_hash VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  verdict VARCHAR(16),
  phase VARCHAR(16),
  advantages_json JSONB NOT NULL,
  counter_evidence_json JSONB NOT NULL,
  data_gaps_json JSONB NOT NULL,
  must_verify_json JSONB NOT NULL,
  confidence NUMERIC(6,5),
  provider VARCHAR(64),
  model VARCHAR(128),
  prompt_version VARCHAR(64) NOT NULL,
  error_summary VARCHAR(500),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_leader_outcome_cohort_status
  ON leader_outcome (lane, grade, direction_heat, status, success);

CREATE INDEX idx_leader_outcome_status
  ON leader_outcome (status, trade_date);

CREATE INDEX idx_leader_ai_review_lookup
  ON leader_ai_review (trade_date, lane, symbol, created_at DESC);
