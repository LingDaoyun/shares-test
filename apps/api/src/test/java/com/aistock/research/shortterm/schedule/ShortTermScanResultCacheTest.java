package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermUpperShadowScan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ShortTermScanResultCacheTest {
    private static final Instant START = Instant.parse("2026-10-15T06:00:00Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ShortTermScanResultCache cache = new ShortTermScanResultCache(jdbc, mapper);

    @Test
    void savesBothBranchesInOnePayloadWithoutPersistingTradeCaptureTokens() throws Exception {
        AtomicReference<String> persisted = new AtomicReference<>();
        when(jdbc.update(anyString(), any(), any(), any())).thenAnswer(call -> {
            persisted.set(call.getArgument(3));
            return 1;
        });
        var snapshot = completed("READY", START, START.plusSeconds(60), "manual-job-1");

        assertThat(cache.save(snapshot)).isTrue();

        var payload = mapper.readTree(persisted.get());
        assertThat(payload.path("runId").asText()).isEqualTo("manual-job-1");
        assertThat(payload.path("report").path("scope").asText()).isEqualTo("right-side-fixture");
        assertThat(payload.path("upperShadow").path("source").asText()).isEqualTo("upper-fixture");
        assertThat(payload.path("report").has("tradeCaptureTokens")).isFalse();
        assertThat(snapshot.report().tradeCaptureTokens()).containsEntry("600001", "one-use-token");
        verify(jdbc, times(1)).update(anyString(), any(), any(), any());
    }

    @Test
    void incompleteFailedOrInvalidTimesCannotReplaceTheLastCompletedResult() {
        assertThat(cache.save(null)).isFalse();
        for (String status : List.of("RUNNING", "FAILED", "INTERRUPTED", "NOT_STARTED", "DATA_BLOCKED"))
            assertThat(cache.save(completed(status, START, START.plusSeconds(60), "job"))).isFalse();
        assertThat(cache.save(completed("READY", null, START, "job"))).isFalse();
        assertThat(cache.save(completed("READY", START, null, "job"))).isFalse();
        assertThat(cache.save(completed("PARTIAL", START, START.minusSeconds(1), "job"))).isFalse();
        verifyNoInteractions(jdbc);
    }

    @Test
    void aDatabaseRejectedLateResultIsReportedAsNotPublished() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
        assertThat(cache.save(completed("PARTIAL", START, START.plusSeconds(30), "older-job"))).isFalse();
    }

    @Test
    void legacyJsonWithoutRunIdLoadsWithAStableIdentityAndDoesNotNeedFreshMarketRequests() throws Exception {
        var original = completed("PARTIAL", START, START.plusSeconds(60), "manual-job");
        var json = mapper.valueToTree(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("runId");
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(mapper.writeValueAsString(json));
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call -> {
            RowMapper<ShortTermDailySnapshot> reader = call.getArgument(1);
            return List.of(reader.mapRow(row, 0));
        });

        var first = cache.latest().orElseThrow();
        var second = cache.latest().orElseThrow();

        assertThat(first.runId()).isEqualTo("daily:2026-10-15:2026-10-15T06:00:00Z");
        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(first.report().scope()).isEqualTo("right-side-fixture");
        assertThat(first.upperShadow().source()).isEqualTo("upper-fixture");
    }

    @Test
    void anEmptyCacheHasNoSyntheticCompletedResult() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        assertThat(cache.latest()).isEmpty();
    }

    private ShortTermDailySnapshot completed(String status, Instant started, Instant finished, String runId) {
        var report = new ShortTermReport("right-side-fixture", 1, 1, 1, 0, "fixture", null, List.of(), null, null,
                List.of(), List.of(), null, List.of(), Map.of("600001", "one-use-token"), START);
        var upper = new ShortTermUpperShadowScan(LocalDate.of(2026, 10, 15), START, "READY", "upper-fixture",
                1, 1, 0, 0, 0, 0, List.of(), List.of(), "fixture");
        return new ShortTermDailySnapshot(LocalDate.of(2026, 10, 15), START, started, finished, status,
                "fixture", report, upper, List.of(), runId);
    }
}
