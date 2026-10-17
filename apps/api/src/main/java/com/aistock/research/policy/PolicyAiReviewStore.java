package com.aistock.research.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PolicyAiReviewStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PolicyAiReviewStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void append(PolicyAiReview review, String evidenceHash, String promptVersion) {
        if (review == null || review.reviewedAt() == null || review.trendPlan() == null
                || !review.trendPlan().isValid() || review.evidenceSnapshot().isEmpty()) {
            throw new IllegalArgumentException("仅保存具有来源快照的有效趋势研判");
        }
        int updated = jdbc.update("""
                INSERT INTO policy_ai_review_history
                    (id, theme_code, reviewed_at, evidence_hash, provider, model, prompt_version, review_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, UUID.randomUUID(), review.themeCode(), Timestamp.from(review.reviewedAt()), evidenceHash,
                review.provider(), review.model(), promptVersion, serialize(review));
        if (updated != 1) throw new IllegalStateException("政策研判未写入历史");
    }

    public List<PolicyAiReview> history(String themeCode) {
        return jdbc.query("""
                SELECT review_json FROM policy_ai_review_history
                WHERE theme_code = ? ORDER BY reviewed_at DESC, id DESC LIMIT 20
                """, (row, index) -> deserialize(row.getString(1)), themeCode);
    }

    public Optional<PolicyAiReview> latest(String themeCode, String evidenceHash, String provider, String model,
                                          String promptVersion, Instant since) {
        return jdbc.query("""
                SELECT review_json FROM policy_ai_review_history
                WHERE theme_code = ? AND evidence_hash = ? AND provider = ? AND model = ?
                    AND prompt_version = ? AND reviewed_at >= ?
                ORDER BY reviewed_at DESC, id DESC LIMIT 1
                """, (row, index) -> deserialize(row.getString(1)), themeCode, evidenceHash, provider, model,
                promptVersion, Timestamp.from(since)).stream().findFirst();
    }

    private String serialize(PolicyAiReview review) {
        try { return mapper.writeValueAsString(review); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("政策研判无法保存", exception); }
    }

    private PolicyAiReview deserialize(String json) {
        try { return mapper.readValue(json, PolicyAiReview.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("政策研判历史无法读取", exception); }
    }
}
