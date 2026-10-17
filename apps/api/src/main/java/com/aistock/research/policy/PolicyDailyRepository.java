package com.aistock.research.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;

@Repository
@DependsOnDatabaseInitialization
public class PolicyDailyRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PolicyDailyRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    Optional<PolicyDailyState> load() {
        return jdbc.query("""
                SELECT snapshot_json::text, last_automatic_attempt_date, last_successful_date
                FROM policy_daily_snapshot WHERE id = 1
                """, (rs, row) -> new PolicyDailyState(
                decode(rs.getString(1)), toLocalDate(rs.getDate(2)), toLocalDate(rs.getDate(3))))
                .stream().findFirst();
    }

    void save(PolicyDailyState state) {
        jdbc.update("""
                INSERT INTO policy_daily_snapshot
                    (id, snapshot_json, last_automatic_attempt_date, last_successful_date, updated_at)
                VALUES (1, CAST(? AS jsonb), ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO UPDATE SET
                    snapshot_json = EXCLUDED.snapshot_json,
                    last_automatic_attempt_date = EXCLUDED.last_automatic_attempt_date,
                    last_successful_date = EXCLUDED.last_successful_date,
                    updated_at = EXCLUDED.updated_at
                """, encode(state.snapshot()), toSqlDate(state.lastAutomaticAttemptDate()), toSqlDate(state.lastSuccessfulDate()));
    }

    private String encode(PolicyDailySnapshot snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("每日政策快照序列化失败", exception);
        }
    }

    private PolicyDailySnapshot decode(String json) {
        try {
            return mapper.readValue(json, PolicyDailySnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("每日政策快照读取失败", exception);
        }
    }

    private LocalDate toLocalDate(Date value) { return value == null ? null : value.toLocalDate(); }
    private Date toSqlDate(LocalDate value) { return value == null ? null : Date.valueOf(value); }
}
