package com.aistock.research.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinancialNewsRepositoryTest {
    @Test
    @SuppressWarnings("unchecked")
    void restoresOriginalDatesSourceSuccessAnalysisAndIndependentSchedule() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new FinancialNewsRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        var now = Instant.parse("2026-11-05T02:00:00Z");
        var news = FinancialNewsServiceTest.item("id", "OFFICIAL", "发布", "官方", "https://example.gov.cn/item", "2026-11-05");
        var source = new NewsSourceStatus("gov", "官方", "OFFICIAL", "https://example.gov.cn", "FAILED", 0, now, now.minusSeconds(60), "失败");
        var analysis = new NewsAnalysis("id", "摘要", List.of("行业"), "推演", List.of(), List.of(), news.url(), now, List.of());
        var snapshot = new FinancialNewsSnapshot("PARTIAL", now, now, List.of(news), List.of(source), Map.of("id", analysis), List.of("来源失败"));
        var state = new FinancialNewsRepository.State(snapshot, now, now.minusSeconds(600));
        repository.save(state);
        var json = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("ON CONFLICT (id) DO UPDATE"), json.capture(), eq(Timestamp.from(now)), eq(Timestamp.from(now.minusSeconds(600))));
        var row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(json.getValue());
        when(row.getTimestamp(2)).thenReturn(Timestamp.from(now));
        when(row.getTimestamp(3)).thenReturn(Timestamp.from(now.minusSeconds(600)));
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call ->
                List.of(((RowMapper<FinancialNewsRepository.State>) call.getArgument(1)).mapRow(row, 0)));
        assertThat(repository.load()).contains(state);
    }
}
