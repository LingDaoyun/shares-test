package com.aistock.research.shortterm.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ShortTermDailySnapshotStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
    private final ShortTermDailySnapshotStore store = new ShortTermDailySnapshotStore(jdbc, new ObjectMapper().findAndRegisterModules());

    @Test
    void postgresClaimsEachSlotOnceAndRecoversAnOrphanedRunAtTheNextSlot() throws Exception {
        String url = System.getenv("AISTOCK_TEST_JDBC_URL");
        String username = System.getenv("AISTOCK_TEST_DB_USERNAME");
        String password = System.getenv("AISTOCK_TEST_DB_PASSWORD");
        assumeTrue(url != null && !url.isBlank() && username != null && password != null,
                "requires an isolated PostgreSQL test database");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            var actualJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            actualJdbc.execute("""
                    CREATE TEMP TABLE short_term_daily_snapshot (
                        trade_date DATE PRIMARY KEY, status VARCHAR(32) NOT NULL,
                        started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ, payload_json TEXT NOT NULL
                    )
                    """);
            var actualStore = new ShortTermDailySnapshotStore(actualJdbc, new ObjectMapper().findAndRegisterModules());
            var first = running("2026-11-04T16:00:00Z", "2026-11-04T16:00:05Z");
            assertThat(actualStore.claim(first)).isTrue();
            assertThat(actualStore.claim(running("2026-11-04T16:00:00Z", "2026-11-04T16:15:00Z"))).isFalse();
            assertThat(actualStore.finish(new ShortTermDailySnapshot(first.tradeDate(), first.scheduledAt(), first.startedAt(),
                    first.startedAt().plusSeconds(30), "READY", "completed", null, null, List.of(), first.runId()))).isTrue();
            assertThat(actualStore.claim(running("2026-11-04T16:00:00Z", "2026-11-04T17:00:00Z"))).isFalse();
            var second = running("2026-11-04T18:00:00Z", "2026-11-04T18:00:05Z");
            assertThat(actualStore.claim(second)).isTrue();
            assertThat(actualStore.latest().orElseThrow()).isEqualTo(second);
            assertThat(actualJdbc.queryForObject("SELECT finished_at FROM short_term_daily_snapshot", java.sql.Timestamp.class)).isNull();
            assertThat(actualStore.claim(running("2026-11-04T18:00:00Z", "2026-11-04T18:30:00Z"))).isFalse();
            var recovered = running("2026-11-04T20:00:00Z", "2026-11-04T20:00:05Z");
            assertThat(actualStore.claim(recovered)).as("a new slot can replace persisted RUNNING after a service restart").isTrue();
            assertThat(actualStore.finish(new ShortTermDailySnapshot(second.tradeDate(), second.scheduledAt(), second.startedAt(),
                    recovered.startedAt(), "READY", "obsolete", null, null, List.of(), second.runId()))).isFalse();
            assertThat(actualStore.latest().orElseThrow()).isEqualTo(recovered);
        }
    }

    private ShortTermDailySnapshot running(String slot, String started) {
        Instant start = Instant.parse(started);
        return new ShortTermDailySnapshot(start.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate(),
                Instant.parse(slot), start, null, "RUNNING", "fixture", null, null, List.of());
    }

    @Test
    void supersededDailyRunDoesNotPublishAResult() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);
        assertThat(store.finishAndPublish(completed("READY"), cache)).isFalse();
        verifyNoInteractions(cache);
    }

    @Test
    void matchingCompletedRunPublishesAfterItsConditionalFinish() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        var snapshot = completed("PARTIAL");
        assertThat(store.finishAndPublish(snapshot, cache)).isTrue();
        var order = inOrder(jdbc, cache);
        order.verify(jdbc).update(anyString(), any(), any(), any(), any(), any());
        order.verify(cache).save(snapshot);
    }

    @Test
    void failedRunNeverReplacesThePreviousCache() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        assertThat(store.finishAndPublish(completed("FAILED"), cache)).isTrue();
        verifyNoInteractions(cache);
    }

    @Test
    void anAlreadyNewerCacheDoesNotInvalidateTheCompletedDailyRun() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        when(cache.save(any())).thenReturn(false);
        assertThat(store.finishAndPublish(completed("READY"), cache)).isTrue();
    }

    private ShortTermDailySnapshot completed(String status) {
        Instant start = Instant.parse("2026-10-15T06:00:00Z");
        return new ShortTermDailySnapshot(LocalDate.of(2026, 10, 15), start, start, start.plusSeconds(60),
                status, "fixture", null, null, List.of());
    }
}
