package com.aistock.research.shortterm.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class ShortTermDailySnapshotStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ShortTermDailySnapshotStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public boolean claim(ShortTermDailySnapshot snapshot) {
        return jdbc.update("""
                INSERT INTO short_term_daily_snapshot(trade_date,status,started_at,payload_json)
                VALUES (?,'RUNNING',?,?) ON CONFLICT(trade_date) DO NOTHING
                """, snapshot.tradeDate(), Timestamp.from(snapshot.startedAt()), serialize(snapshot)) == 1;
    }
    public boolean claimRetry(ShortTermDailySnapshot snapshot) {
        return jdbc.update("""
                UPDATE short_term_daily_snapshot SET status='RUNNING',started_at=?,finished_at=NULL,payload_json=?
                WHERE trade_date=? AND (status IN ('FAILED','PARTIAL') OR
                    (status='RUNNING' AND started_at<=?))
                """, Timestamp.from(snapshot.startedAt()), serialize(snapshot), snapshot.tradeDate(),
                Timestamp.from(snapshot.startedAt().minusSeconds(1800))) == 1;
    }
    public boolean finish(ShortTermDailySnapshot snapshot) {
        return jdbc.update("""
                UPDATE short_term_daily_snapshot SET status=?,finished_at=?,payload_json=?
                WHERE trade_date=? AND status='RUNNING' AND started_at=?
                """, snapshot.status(), Timestamp.from(snapshot.finishedAt()), serialize(snapshot), snapshot.tradeDate(),
                Timestamp.from(snapshot.startedAt())) == 1;
    }
    @Transactional
    public boolean finishAndPublish(ShortTermDailySnapshot snapshot, ShortTermScanResultCache resultCache) {
        if (!finish(snapshot)) return false;
        if ("READY".equals(snapshot.status()) || "PARTIAL".equals(snapshot.status())) resultCache.save(snapshot);
        return true;
    }
    public Optional<ShortTermDailySnapshot> latest() {
        return jdbc.query("SELECT payload_json FROM short_term_daily_snapshot ORDER BY trade_date DESC LIMIT 1",
                (row, index) -> deserialize(row.getString(1))).stream().findFirst();
    }
    private String serialize(ShortTermDailySnapshot snapshot) {
        try { return mapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException error) { throw new IllegalStateException("定时扫描结果无法保存", error); }
    }
    private ShortTermDailySnapshot deserialize(String json) {
        try { return mapper.readValue(json, ShortTermDailySnapshot.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("定时扫描结果无法读取", error); }
    }
}
