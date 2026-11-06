package com.aistock.research.news;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
@DependsOnDatabaseInitialization
public class FinancialNewsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public FinancialNewsRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    Optional<State> load() {
        return jdbc.query("""
                SELECT snapshot_json::text, media_last_attempt_at, official_last_attempt_at
                FROM financial_news_snapshot WHERE id = 1
                """, (row, index) -> new State(decode(row.getString(1)),
                instant(row.getTimestamp(2)), instant(row.getTimestamp(3)))).stream().findFirst();
    }

    void save(State state) {
        jdbc.update("""
                INSERT INTO financial_news_snapshot (id, snapshot_json, media_last_attempt_at, official_last_attempt_at, updated_at)
                VALUES (1, CAST(? AS jsonb), ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO UPDATE SET snapshot_json = EXCLUDED.snapshot_json,
                    media_last_attempt_at = EXCLUDED.media_last_attempt_at,
                    official_last_attempt_at = EXCLUDED.official_last_attempt_at, updated_at = EXCLUDED.updated_at
                """, encode(state.snapshot()), timestamp(state.mediaLastAttemptAt()), timestamp(state.officialLastAttemptAt()));
    }

    private String encode(FinancialNewsSnapshot snapshot) {
        try { return mapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("消息快照保存失败", exception); }
    }

    private FinancialNewsSnapshot decode(String value) {
        try { return mapper.readValue(value, FinancialNewsSnapshot.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("消息快照读取失败", exception); }
    }

    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    record State(FinancialNewsSnapshot snapshot, Instant mediaLastAttemptAt, Instant officialLastAttemptAt) { }
}
