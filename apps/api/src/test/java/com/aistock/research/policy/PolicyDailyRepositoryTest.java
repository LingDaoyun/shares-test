package com.aistock.research.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PolicyDailyRepositoryTest {
    @Test
    @SuppressWarnings("unchecked")
    void serializesEvidenceAndReadsTheSameStateFromDatabase() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PolicyDailyRepository repository = new PolicyDailyRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        PolicyTheme theme = new PolicyTheme("DIGITAL", "算力", "国家级", "2-12周", BigDecimal.valueOf(80), List.of("光模块"),
                List.of(new PolicySignal("政府网", "规划", "发展规划", 80, "https://www.gov.cn/policy/example", "2026-10-16")), List.of("风险"), List.of());
        PolicyDailySnapshot snapshot = new PolicyDailySnapshot("PARTIAL", PolicyDailySnapshot.SCHEDULED_TIME,
                Instant.parse("2026-10-17T00:30:00Z"), Instant.parse("2026-10-17T00:31:00Z"),
                List.of(theme), List.of("DIGITAL"), List.of("标题级证据"));
        PolicyDailyState state = new PolicyDailyState(snapshot, LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 17));
        repository.save(state);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("CAST(? AS jsonb)"), payload.capture(), eq(Date.valueOf("2026-10-17")), eq(Date.valueOf("2026-10-17")));
        assertThat(payload.getValue()).contains("发展规划", "https://www.gov.cn/policy/example", "companyPool");

        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(payload.getValue());
        when(row.getDate(2)).thenReturn(Date.valueOf("2026-10-17"));
        when(row.getDate(3)).thenReturn(Date.valueOf("2026-10-17"));
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call ->
                List.of(((RowMapper<PolicyDailyState>) call.getArgument(1)).mapRow(row, 0)));

        assertThat(repository.load()).contains(state);
    }
}
