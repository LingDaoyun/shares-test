package com.aistock.research.hotspot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@DependsOnDatabaseInitialization
public class HotspotRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public HotspotRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    Optional<State> load() {
        return jdbc.query("SELECT snapshot_json::text, analyzed_news_hash FROM industry_hotspot_snapshot WHERE id = 1",
                (rs, row) -> new State(decode(rs.getString(1)), rs.getString(2))).stream().findFirst();
    }

    void save(State state) {
        jdbc.update("""
                INSERT INTO industry_hotspot_snapshot (id, snapshot_json, analyzed_news_hash, updated_at)
                VALUES (1, CAST(? AS jsonb), ?, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO UPDATE SET snapshot_json = EXCLUDED.snapshot_json,
                    analyzed_news_hash = EXCLUDED.analyzed_news_hash, updated_at = EXCLUDED.updated_at
                """, encode(state.snapshot()), state.analyzedNewsHash());
    }

    private String encode(HotspotSnapshot snapshot) {
        try { return mapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("产业热点快照保存失败", exception); }
    }

    private HotspotSnapshot decode(String value) {
        try { return mapper.readValue(value, HotspotSnapshot.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("产业热点快照读取失败", exception); }
    }

    record State(HotspotSnapshot snapshot, String analyzedNewsHash) { }
}
