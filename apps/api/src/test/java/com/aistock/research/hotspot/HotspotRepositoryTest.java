package com.aistock.research.hotspot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import static com.aistock.research.hotspot.HotspotTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotspotRepositoryTest {
    @Test
    @SuppressWarnings("unchecked")
    void roundTripsEvidenceOriginalTimesFirstPriceAndReuseIdentity() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var repository = new HotspotRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        var time = Instant.parse("2026-10-19T01:30:00Z");
        var source = news("source-1", time);
        var saved = new HotspotRepository.State(snapshot(List.of(source),
                List.of(event("event-1", List.of(source), List.of(stock(source, time)), time)), time), "analyzed-content-hash");

        repository.save(saved);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("ON CONFLICT (id) DO UPDATE"), payload.capture(), eq("analyzed-content-hash"));
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(payload.getValue());
        when(row.getString(2)).thenReturn("analyzed-content-hash");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call ->
                List.of(((RowMapper<HotspotRepository.State>) call.getArgument(1)).mapRow(row, 0)));

        assertThat(repository.load()).contains(saved);
        assertThat(payload.getValue()).contains("firstSeenPrice", "firstSeenQuoteAsOf", "companyEvidence", "https://news.example/source-1");
    }
}
