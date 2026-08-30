UPDATE first_board_market_day
SET status = 'FAILED',
    failed_symbol_count = GREATEST(
      failed_symbol_count,
      expected_symbol_count - written_symbol_count,
      1
    ),
    limit_up_pool_complete = FALSE,
    bar_data_version = NULL,
    completed_at = NULL,
    next_retry_at = COALESCE(next_retry_at, now()),
    error_summary = LEFT(COALESCE(
      NULLIF(error_summary, ''),
      'Flyway V3: historical READY row failed completeness validation'
    ), 500)
WHERE status = 'READY'
  AND NOT (
    expected_symbol_count > 0
    AND written_symbol_count = expected_symbol_count
    AND failed_symbol_count = 0
    AND limit_up_pool_complete
    AND bar_data_version IS NOT NULL
    AND LENGTH(TRIM(bar_data_version)) > 0
    AND completed_at IS NOT NULL
    AND next_retry_at IS NULL
    AND error_summary IS NULL
  );

UPDATE first_board_market_day
SET failed_symbol_count = GREATEST(failed_symbol_count, 1),
    limit_up_pool_complete = FALSE,
    bar_data_version = NULL,
    completed_at = NULL,
    next_retry_at = COALESCE(next_retry_at, now()),
    error_summary = LEFT(COALESCE(
      NULLIF(error_summary, ''),
      'Flyway V3: failed market day normalized for retry'
    ), 500)
WHERE status = 'FAILED';

UPDATE first_board_market_day
SET limit_up_pool_complete = FALSE,
    bar_data_version = NULL,
    completed_at = NULL,
    next_retry_at = NULL,
    error_summary = NULL
WHERE status = 'IMPORTING';

ALTER TABLE first_board_market_day
  ADD CONSTRAINT ck_first_board_market_day_state_integrity CHECK (
    (
      status = 'IMPORTING'
      AND NOT limit_up_pool_complete
      AND bar_data_version IS NULL
      AND completed_at IS NULL
      AND next_retry_at IS NULL
      AND error_summary IS NULL
    )
    OR
    (
      status = 'READY'
      AND expected_symbol_count > 0
      AND written_symbol_count = expected_symbol_count
      AND failed_symbol_count = 0
      AND limit_up_pool_complete
      AND bar_data_version IS NOT NULL
      AND LENGTH(TRIM(bar_data_version)) > 0
      AND completed_at IS NOT NULL
      AND next_retry_at IS NULL
      AND error_summary IS NULL
    )
    OR
    (
      status = 'FAILED'
      AND failed_symbol_count > 0
      AND NOT limit_up_pool_complete
      AND bar_data_version IS NULL
      AND completed_at IS NULL
      AND next_retry_at IS NOT NULL
      AND error_summary IS NOT NULL
      AND LENGTH(TRIM(error_summary)) > 0
    )
  );
