CREATE TABLE short_term_scan_result_cache (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    run_id TEXT NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload_json TEXT NOT NULL
);

INSERT INTO short_term_scan_result_cache(id,run_id,started_at,payload_json)
SELECT 1, run_id, started_at,
       jsonb_set(payload - 'runId', '{runId}', to_jsonb(run_id), TRUE)::text
FROM (
    SELECT started_at,
           COALESCE(NULLIF(BTRIM(payload_json::jsonb->>'runId'), ''),
               'daily:' || trade_date::text || ':' ||
               COALESCE(payload_json::jsonb->>'startedAt',
                   RTRIM(RTRIM(TO_CHAR(started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'), '0'), '.') || 'Z')) AS run_id,
           payload_json::jsonb #- '{report,tradeCaptureTokens}' AS payload
    FROM short_term_daily_snapshot
    WHERE status IN ('READY','PARTIAL') AND finished_at IS NOT NULL AND finished_at >= started_at
    ORDER BY started_at DESC, trade_date DESC
    LIMIT 1
) latest_completed;
