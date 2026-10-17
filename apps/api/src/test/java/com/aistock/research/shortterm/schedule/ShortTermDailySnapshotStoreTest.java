package com.aistock.research.shortterm.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ShortTermDailySnapshotStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ShortTermScanResultCache cache = mock(ShortTermScanResultCache.class);
    private final ShortTermDailySnapshotStore store = new ShortTermDailySnapshotStore(jdbc, new ObjectMapper().findAndRegisterModules());

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
