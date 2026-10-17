package com.aistock.research.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PolicyAiReviewStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PolicyAiReviewStore store = new PolicyAiReviewStore(jdbc, mapper);

    @Test
    void insertsImmutableSnapshotAndRoundTripsAllFields() throws Exception {
        PolicyAiReview review = review();
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        store.append(review, "a".repeat(64), "v2");
        var sql = ArgumentCaptor.forClass(String.class);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(), eq("DIGITAL_INFRA"), eq(Timestamp.from(review.reviewedAt())),
                eq("a".repeat(64)), eq("fixture"), eq("model"), eq("v2"), payload.capture());
        assertThat(sql.getValue()).contains("INSERT INTO policy_ai_review_history", "CAST(? AS jsonb)").doesNotContain("UPDATE", "DELETE");
        assertThat(mapper.readValue(payload.getValue(), PolicyAiReview.class)).isEqualTo(review);
    }

    @Test
    void failureAndEmptyEvidenceAreNeverInserted() {
        assertThatThrownBy(() -> store.append(PolicyAiReview.unavailable("x", "x", "failed"), "hash", "v2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.append(review().withEvidence(List.of()), "hash", "v2"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsAtMostTwentyNewestReviewsForOnlyRequestedTheme() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(mapper.writeValueAsString(review()));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("DIGITAL_INFRA"))).thenAnswer(invocation -> {
            RowMapper<PolicyAiReview> rowMapper = invocation.getArgument(1);
            return List.of(rowMapper.mapRow(row, 0));
        });
        assertThat(store.history("DIGITAL_INFRA")).containsExactly(review());
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("DIGITAL_INFRA"));
        assertThat(sql.getValue()).contains("WHERE theme_code = ?", "ORDER BY reviewed_at DESC", "LIMIT 20");
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistentCacheFiltersEvidenceConfigurationVersionAndThirtyMinuteCutoff() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(mapper.writeValueAsString(review()));
        Instant since = Instant.parse("2026-10-17T01:00:00Z");
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    RowMapper<PolicyAiReview> rowMapper = invocation.getArgument(1);
                    return List.of(rowMapper.mapRow(row, 0));
                });
        assertThat(store.latest("DIGITAL_INFRA", "hash", "fixture", "model", "v2", since)).contains(review());
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("DIGITAL_INFRA"), eq("hash"), eq("fixture"),
                eq("model"), eq("v2"), eq(Timestamp.from(since)));
        assertThat(sql.getValue()).contains("evidence_hash = ?", "provider = ?", "model = ?", "prompt_version = ?",
                "reviewed_at >= ?", "LIMIT 1");
    }

    private PolicyAiReview review() {
        return new PolicyAiReview("DIGITAL_INFRA", "算力", "fixture", "model", 75, "POSITIVE", "政策利好",
                "EARLY", "发酵初期", "待核实的条件推演", List.of(), List.of(), List.of(), List.of(), 70,
                Instant.parse("2026-10-17T01:01:00Z"), List.of(),
                new PolicyTrendPlan("观察建设落地", "增量待核实", List.of("建设到设备"), List.of(), List.of(),
                        List.of("预算落地"), List.of("计划延期"), "不应保留模型的行情确认"),
                List.of(new PolicySignal("政府网", "规划", "标题摘要", 75, "https://example.invalid", "2026-10-17")));
    }
}
