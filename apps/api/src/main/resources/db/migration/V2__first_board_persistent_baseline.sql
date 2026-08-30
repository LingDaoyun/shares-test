CREATE TABLE first_board_daily_bar (
  symbol VARCHAR(6) NOT NULL,
  trade_date DATE NOT NULL,
  open_price NUMERIC(20, 6) NOT NULL,
  close_price NUMERIC(20, 6) NOT NULL,
  high_price NUMERIC(20, 6) NOT NULL,
  low_price NUMERIC(20, 6) NOT NULL,
  volume NUMERIC(30, 4) NOT NULL,
  amount NUMERIC(30, 4),
  turnover_rate NUMERIC(12, 4),
  adjust_mode VARCHAR(16) NOT NULL,
  was_limit_up BOOLEAN NOT NULL DEFAULT FALSE,
  limit_up_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  source_name VARCHAR(128) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (symbol, trade_date),
  CONSTRAINT ck_first_board_bar_adjust CHECK (adjust_mode = 'QFQ'),
  CONSTRAINT ck_first_board_bar_prices CHECK (
    open_price > 0 AND close_price > 0 AND high_price >= low_price
    AND high_price >= open_price AND high_price >= close_price
    AND low_price <= open_price AND low_price <= close_price
  )
);

CREATE INDEX idx_first_board_bar_symbol_date
  ON first_board_daily_bar(symbol, trade_date DESC);
CREATE INDEX idx_first_board_bar_trade_date
  ON first_board_daily_bar(trade_date, symbol);

CREATE INDEX idx_kline_symbol_date_observed_desc
  ON market_kline_history(symbol, trade_date, observed_at DESC);
CREATE INDEX idx_kline_trade_date_brin
  ON market_kline_history USING BRIN(trade_date);

CREATE TABLE first_board_market_day (
  trade_date DATE PRIMARY KEY,
  source_trade_date DATE,
  status VARCHAR(16) NOT NULL,
  expected_symbol_count INTEGER NOT NULL DEFAULT 0,
  written_symbol_count INTEGER NOT NULL DEFAULT 0,
  failed_symbol_count INTEGER NOT NULL DEFAULT 0,
  limit_up_pool_complete BOOLEAN NOT NULL DEFAULT FALSE,
  bar_data_version VARCHAR(64),
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE,
  next_retry_at TIMESTAMP WITH TIME ZONE,
  error_summary VARCHAR(500),
  CONSTRAINT ck_first_board_market_day_status CHECK (status IN ('IMPORTING', 'READY', 'FAILED')),
  CONSTRAINT ck_first_board_market_day_counts CHECK (
    expected_symbol_count >= 0 AND written_symbol_count >= 0 AND failed_symbol_count >= 0
  )
);

CREATE TABLE first_board_baseline_run (
  run_id UUID PRIMARY KEY,
  scan_trade_date DATE NOT NULL,
  baseline_trade_date DATE NOT NULL,
  rule_version VARCHAR(64) NOT NULL,
  bar_data_version VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  universe_count INTEGER NOT NULL,
  supported_count INTEGER NOT NULL,
  excluded_name_count INTEGER NOT NULL,
  insufficient_history_count INTEGER NOT NULL,
  item_count INTEGER NOT NULL DEFAULT 0,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  published_at TIMESTAMP WITH TIME ZONE,
  error_summary VARCHAR(500),
  CONSTRAINT ck_first_board_run_status CHECK (status IN ('BUILDING', 'READY', 'FAILED'))
);

CREATE INDEX idx_first_board_run_lookup
  ON first_board_baseline_run(scan_trade_date, rule_version, published_at DESC);

CREATE TABLE first_board_baseline_item (
  run_id UUID NOT NULL,
  symbol VARCHAR(6) NOT NULL,
  company_name VARCHAR(128) NOT NULL,
  industry VARCHAR(128),
  board VARCHAR(16) NOT NULL,
  baseline_date DATE NOT NULL,
  previous_close NUMERIC(20, 6) NOT NULL,
  ma10 NUMERIC(20, 6) NOT NULL,
  ma20 NUMERIC(20, 6) NOT NULL,
  ma20_slope NUMERIC(20, 6) NOT NULL,
  rsi14 NUMERIC(20, 6) NOT NULL,
  macd_histogram NUMERIC(20, 6) NOT NULL,
  technical_pass_count INTEGER NOT NULL,
  previous_day_volume_ratio NUMERIC(20, 6) NOT NULL,
  recent_five_max_volume_ratio NUMERIC(20, 6) NOT NULL,
  upper_shadow_ratio NUMERIC(20, 6) NOT NULL,
  three_day_close_range NUMERIC(20, 6) NOT NULL,
  previous_limit_up BOOLEAN NOT NULL,
  PRIMARY KEY (run_id, symbol),
  CONSTRAINT fk_first_board_item_run FOREIGN KEY (run_id)
    REFERENCES first_board_baseline_run(run_id) ON DELETE CASCADE,
  CONSTRAINT ck_first_board_item_board CHECK (board IN ('MAIN', 'GROWTH', 'STAR'))
);

CREATE INDEX idx_first_board_item_symbol_date
  ON first_board_baseline_item(symbol, baseline_date DESC);

ALTER TABLE first_board_daily_bar SET (
  autovacuum_vacuum_scale_factor = 0.02,
  autovacuum_analyze_scale_factor = 0.01
);
ALTER TABLE first_board_baseline_item SET (
  autovacuum_vacuum_scale_factor = 0.02,
  autovacuum_analyze_scale_factor = 0.01
);
