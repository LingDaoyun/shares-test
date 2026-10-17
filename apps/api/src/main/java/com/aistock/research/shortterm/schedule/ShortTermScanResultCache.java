package com.aistock.research.shortterm.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/** One completed scan envelope shared by scheduled and manual scans. */
@Repository
public class ShortTermScanResultCache {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ShortTermScanResultCache(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public boolean save(ShortTermDailySnapshot snapshot) {
        if (snapshot == null || !("READY".equals(snapshot.status()) || "PARTIAL".equals(snapshot.status()))
                || snapshot.tradeDate() == null || snapshot.startedAt() == null || snapshot.finishedAt() == null
                || snapshot.finishedAt().isBefore(snapshot.startedAt())) return false;
        return jdbc.update("""
                INSERT INTO short_term_scan_result_cache(id,run_id,started_at,payload_json)
                VALUES (1,?,?,?)
                ON CONFLICT(id) DO UPDATE SET run_id=EXCLUDED.run_id,started_at=EXCLUDED.started_at,payload_json=EXCLUDED.payload_json
                WHERE short_term_scan_result_cache.started_at < EXCLUDED.started_at
                   OR (short_term_scan_result_cache.started_at = EXCLUDED.started_at
                       AND short_term_scan_result_cache.run_id = EXCLUDED.run_id)
                """, snapshot.runId(), Timestamp.from(snapshot.startedAt()), serialize(snapshot)) == 1;
    }

    public Optional<ShortTermDailySnapshot> latest() {
        return jdbc.query("SELECT payload_json FROM short_term_scan_result_cache WHERE id=1",
                (row, index) -> deserialize(row.getString(1))).stream().findFirst();
    }

    private String serialize(ShortTermDailySnapshot snapshot) {
        try {
            ObjectNode payload = mapper.valueToTree(snapshot);
            if (payload.get("report") instanceof ObjectNode report) report.remove("tradeCaptureTokens");
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("扫描结果缓存无法保存", error);
        }
    }

    private ShortTermDailySnapshot deserialize(String json) {
        try { return mapper.readValue(json, ShortTermDailySnapshot.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("扫描结果缓存无法读取", error); }
    }
}
