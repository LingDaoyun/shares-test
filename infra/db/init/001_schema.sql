CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS stock_company (
  id BIGSERIAL PRIMARY KEY,
  symbol VARCHAR(16) NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL,
  market VARCHAR(32) NOT NULL,
  industry VARCHAR(128),
  listed_at DATE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS policy_theme (
  id BIGSERIAL PRIMARY KEY,
  theme_code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL,
  policy_level VARCHAR(32) NOT NULL,
  time_horizon VARCHAR(64),
  strength_score NUMERIC(8, 2) NOT NULL,
  summary TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS document_source (
  id BIGSERIAL PRIMARY KEY,
  source_type VARCHAR(32) NOT NULL,
  source_title VARCHAR(256) NOT NULL,
  source_url TEXT,
  published_at DATE,
  object_key VARCHAR(512),
  checksum VARCHAR(128),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS document_chunk (
  id BIGSERIAL PRIMARY KEY,
  document_id BIGINT NOT NULL REFERENCES document_source(id),
  chunk_index INTEGER NOT NULL,
  content TEXT NOT NULL,
  embedding vector(1536),
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(document_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS factor_snapshot (
  id BIGSERIAL PRIMARY KEY,
  symbol VARCHAR(16) NOT NULL REFERENCES stock_company(symbol),
  trade_date DATE NOT NULL,
  factors JSONB NOT NULL,
  data_version VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(symbol, trade_date, data_version)
);

CREATE TABLE IF NOT EXISTS rule_set (
  id BIGSERIAL PRIMARY KEY,
  rule_code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL,
  description TEXT,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS rule_version (
  id BIGSERIAL PRIMARY KEY,
  rule_set_id BIGINT NOT NULL REFERENCES rule_set(id),
  version INTEGER NOT NULL,
  definition JSONB NOT NULL,
  published BOOLEAN NOT NULL DEFAULT FALSE,
  published_at TIMESTAMPTZ,
  created_by VARCHAR(128),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(rule_set_id, version)
);

CREATE TABLE IF NOT EXISTS rule_audit_log (
  id BIGSERIAL PRIMARY KEY,
  rule_set_id BIGINT REFERENCES rule_set(id),
  from_version INTEGER,
  to_version INTEGER,
  operation VARCHAR(32) NOT NULL,
  operator VARCHAR(128),
  reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS stock_score (
  id BIGSERIAL PRIMARY KEY,
  symbol VARCHAR(16) NOT NULL REFERENCES stock_company(symbol),
  trade_date DATE NOT NULL,
  rule_version_id BIGINT REFERENCES rule_version(id),
  total_score NUMERIC(8, 2) NOT NULL,
  decision VARCHAR(32) NOT NULL,
  evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS watchlist_entry (
  id BIGSERIAL PRIMARY KEY,
  symbol VARCHAR(16) NOT NULL REFERENCES stock_company(symbol),
  decision VARCHAR(32) NOT NULL,
  thesis TEXT,
  score NUMERIC(8, 2),
  rule_version VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS special_watchlist (
  symbol VARCHAR(6) PRIMARY KEY,
  company_name VARCHAR(128) NOT NULL,
  note VARCHAR(1000) NOT NULL DEFAULT '',
  last_action_label VARCHAR(64),
  last_decision_score NUMERIC(8, 2),
  last_analyzed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS market_kline_history (
  observation_id VARCHAR(64) PRIMARY KEY,
  symbol VARCHAR(6) NOT NULL,
  trade_date DATE NOT NULL,
  bar_type VARCHAR(32) NOT NULL,
  open_price NUMERIC(20, 6),
  close_price NUMERIC(20, 6),
  high_price NUMERIC(20, 6),
  low_price NUMERIC(20, 6),
  volume NUMERIC(30, 4),
  amount NUMERIC(30, 4),
  source_name VARCHAR(128) NOT NULL,
  observed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kline_symbol_date
  ON market_kline_history(symbol, trade_date, observed_at);

CREATE TABLE IF NOT EXISTS research_analysis_history (
  analysis_id VARCHAR(36) PRIMARY KEY,
  symbol VARCHAR(6) NOT NULL,
  company_name VARCHAR(128) NOT NULL,
  analysis_type VARCHAR(64) NOT NULL,
  status VARCHAR(64) NOT NULL,
  summary VARCHAR(2000) NOT NULL,
  ai_provider VARCHAR(64),
  ai_model VARCHAR(128),
  payload_json TEXT NOT NULL,
  data_as_of TIMESTAMPTZ NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_analysis_symbol_time
  ON research_analysis_history(symbol, recorded_at);

CREATE TABLE IF NOT EXISTS investment_decision_history (
  decision_id VARCHAR(36) PRIMARY KEY,
  analysis_id VARCHAR(36) NOT NULL REFERENCES research_analysis_history(analysis_id),
  symbol VARCHAR(6) NOT NULL,
  source_type VARCHAR(64) NOT NULL,
  action_stage VARCHAR(64) NOT NULL,
  action_label VARCHAR(64) NOT NULL,
  decision_score NUMERIC(8, 2),
  rule_version VARCHAR(64) NOT NULL,
  payload_json TEXT NOT NULL,
  data_as_of TIMESTAMPTZ NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_decision_symbol_time
  ON investment_decision_history(symbol, recorded_at);

CREATE TABLE IF NOT EXISTS trend_analysis_run (
  id BIGSERIAL PRIMARY KEY,
  analysis_date DATE NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL,
  document_title VARCHAR(255) NOT NULL,
  document_type VARCHAR(128) NOT NULL,
  source_organization VARCHAR(128),
  published_at VARCHAR(32),
  source_url TEXT,
  prompt_name VARCHAR(128) NOT NULL,
  prompt_version VARCHAR(32) NOT NULL,
  provider VARCHAR(64) NOT NULL,
  model VARCHAR(128) NOT NULL,
  response_id VARCHAR(128),
  request_payload TEXT NOT NULL,
  prompt_preview_payload TEXT NOT NULL,
  analysis_payload TEXT NOT NULL,
  usage_payload TEXT,
  analyzed_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (analysis_date, request_fingerprint)
);

CREATE TABLE IF NOT EXISTS strategy_trade_case (
  case_id VARCHAR(36) PRIMARY KEY,
  recommendation_fingerprint VARCHAR(64) NOT NULL UNIQUE,
  decision_id VARCHAR(36),
  symbol VARCHAR(6) NOT NULL,
  company_name VARCHAR(128) NOT NULL,
  source_module VARCHAR(64) NOT NULL,
  recommendation_action VARCHAR(64) NOT NULL,
  recommendation_score NUMERIC(8, 2),
  rule_version VARCHAR(64) NOT NULL,
  recommended_price NUMERIC(20, 6) NOT NULL,
  recommended_at TIMESTAMP WITH TIME ZONE NOT NULL,
  recommendation_payload_json TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_trade_case_decision FOREIGN KEY (decision_id)
    REFERENCES investment_decision_history(decision_id)
);

CREATE TABLE IF NOT EXISTS strategy_trade_fill (
  fill_id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  side VARCHAR(8) NOT NULL,
  executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  price NUMERIC(20, 6) NOT NULL,
  quantity BIGINT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_trade_fill_case FOREIGN KEY (case_id) REFERENCES strategy_trade_case(case_id),
  CONSTRAINT ck_trade_fill_side CHECK (side IN ('BUY', 'SELL')),
  CONSTRAINT ck_trade_fill_price CHECK (price > 0),
  CONSTRAINT ck_trade_fill_quantity CHECK (quantity > 0)
);

CREATE TABLE IF NOT EXISTS strategy_outcome_snapshot (
  snapshot_id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  baseline_type VARCHAR(32) NOT NULL,
  horizon VARCHAR(16) NOT NULL,
  baseline_price NUMERIC(20, 6),
  evaluation_price NUMERIC(20, 6),
  evaluation_date DATE,
  return_pct NUMERIC(12, 4),
  max_runup_pct NUMERIC(12, 4),
  max_drawdown_pct NUMERIC(12, 4),
  status VARCHAR(32) NOT NULL,
  source_name VARCHAR(128),
  market_timestamp TIMESTAMP WITH TIME ZONE,
  calculated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_trade_outcome_case FOREIGN KEY (case_id) REFERENCES strategy_trade_case(case_id),
  CONSTRAINT uk_trade_outcome_scope UNIQUE (case_id, baseline_type, horizon)
);

ALTER TABLE strategy_outcome_snapshot ADD COLUMN IF NOT EXISTS source_name VARCHAR(128);
ALTER TABLE strategy_outcome_snapshot ADD COLUMN IF NOT EXISTS market_timestamp TIMESTAMP WITH TIME ZONE;
