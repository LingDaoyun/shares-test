package com.aistock.research.shortterm;

import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShortTermDailyHistoryServiceTest {
    private static final LocalDate DATE = LocalDate.parse("2026-10-15");
    private final EastMoneyClient client = mock(EastMoneyClient.class);
    private final FirstBoardDailyBarStore store = mock(FirstBoardDailyBarStore.class);
    private final TradingClockService clock = new TradingClockService(Clock.fixed(
            Instant.parse("2026-10-15T06:00:00Z"), ZoneOffset.UTC));
    private final ShortTermDailyHistoryService service = new ShortTermDailyHistoryService(client, store, clock);

    @AfterEach
    void closePool() { service.shutdown(); }

    @Test
    void completeLocalWindowDoesNotRequestRemoteHistory() {
        when(store.recentBars("002815", DATE.minusDays(1), 20)).thenReturn(window("002815", "100"));
        assertThat(service.ensureWindow(List.of("002815"), DATE, 20)).isEmpty();
        verifyNoInteractions(client);
        verify(store, never()).upsertBars(anyList(), anyString());
    }

    @Test
    void moreThanFifteenOldBarsStillRepairsInteriorGapsWithAnEntireQfqWindow() {
        List<EastMoneyKLine> old = new ArrayList<>(window("002815", "100"));
        old.remove(18);
        List<EastMoneyKLine> rebased = window("002815", "50");
        when(store.recentBars("002815", DATE.minusDays(1), 20)).thenReturn(old, old, rebased);
        when(client.fetchDailyKLines("002815", LocalDate.parse("2026-09-09"), DATE.minusDays(1))).thenReturn(rebased);

        assertThat(service.ensureWindow(List.of("002815"), DATE, 20)).isEmpty();
        verify(client).fetchDailyKLines("002815", LocalDate.parse("2026-09-09"), DATE.minusDays(1));
        verify(store).upsertBars(eq(rebased), contains("整窗"));
        assertThat(rebased).hasSize(20).allSatisfy(row -> assertThat(row.close()).isEqualByComparingTo("50"));
    }

    @Test
    void remoteMissingDateDoesNotWritePartialHistoryAndRepeatedRefreshUsesFailureCooldown() {
        when(store.recentBars("002815", DATE.minusDays(1), 20)).thenReturn(List.of());
        List<EastMoneyKLine> missing = new ArrayList<>(window("002815", "50"));
        missing.remove(10);
        when(client.fetchDailyKLines(anyString(), any(), any())).thenReturn(missing);

        assertThat(service.ensureWindow(List.of("002815"), DATE, 20)).anyMatch(value -> value.contains("连续20"));
        assertThat(service.ensureWindow(List.of("002815"), DATE, 20)).anyMatch(value -> value.contains("冷却"));
        verify(client, times(1)).fetchDailyKLines(anyString(), any(), any());
        verify(store, never()).upsertBars(anyList(), anyString());
    }

    @Test
    void wrongIdentityDuplicateDateAndUnfinishedDayCannotBeWrittenAsExactHistory() {
        when(store.recentBars(anyString(), any(), anyInt())).thenReturn(List.of());
        List<EastMoneyKLine> wrongIdentity = window("600001", "50");
        when(client.fetchDailyKLines(eq("002815"), any(), any())).thenReturn(wrongIdentity);
        List<EastMoneyKLine> duplicate = new ArrayList<>(window("002816", "50"));
        duplicate.set(19, duplicate.get(18));
        when(client.fetchDailyKLines(eq("002816"), any(), any())).thenReturn(duplicate);
        List<EastMoneyKLine> unfinished = new ArrayList<>(window("002817", "50"));
        unfinished.set(19, bar("002817", DATE, "50"));
        when(client.fetchDailyKLines(eq("002817"), any(), any())).thenReturn(unfinished);

        assertThat(service.ensureWindow(List.of("002815", "002816", "002817"), DATE, 20))
                .singleElement().asString().contains("3只");
        verify(store, never()).upsertBars(anyList(), anyString());
    }

    @Test
    void repairIsNotSuccessfulUntilThePersistedWindowPassesTheSameExactValidation() {
        when(store.recentBars(anyString(), any(), anyInt())).thenReturn(List.of());
        when(client.fetchDailyKLines(anyString(), any(), any())).thenReturn(window("002815", "50"));
        assertThat(service.ensureWindow(List.of("002815"), DATE, 20)).anyMatch(value -> value.contains("写入后"));
        verify(store).upsertBars(anyList(), anyString());
    }

    @Test
    void preservesKnownTradingMetricsOnlyWhenTheFetchedQfqOhlcvMatchesTheStoredDay() {
        List<EastMoneyKLine> fetched = window("002815", "50");
        List<EastMoneyKLine> local = new ArrayList<>(fetched.subList(0, 19).stream().map(row ->
                new EastMoneyKLine(row.symbol(), row.tradeDate(), row.open(), row.close(), row.high(), row.low(),
                        row.volume(), new BigDecimal("1000"), new BigDecimal("2"))).toList());
        var changedBasis = local.get(0);
        local.set(0, new EastMoneyKLine(changedBasis.symbol(), changedBasis.tradeDate(), new BigDecimal("51"),
                new BigDecimal("51"), new BigDecimal("51"), new BigDecimal("51"), changedBasis.volume(),
                changedBasis.amount(), changedBasis.turnoverRate()));
        var changedVolume = local.get(1);
        local.set(1, new EastMoneyKLine(changedVolume.symbol(), changedVolume.tradeDate(), changedVolume.open(),
                changedVolume.close(), changedVolume.high(), changedVolume.low(), new BigDecimal("2"),
                changedVolume.amount(), changedVolume.turnoverRate()));
        when(store.recentBars("002815", DATE.minusDays(1), 20)).thenReturn(local, local, fetched);
        when(client.fetchDailyKLines(anyString(), any(), any())).thenReturn(fetched);
        org.mockito.ArgumentCaptor<List<EastMoneyKLine>> written = org.mockito.ArgumentCaptor.forClass(List.class);

        assertThat(service.ensureWindow(List.of("002815"), DATE, 20)).isEmpty();

        verify(store).upsertBars(written.capture(), anyString());
        List<EastMoneyKLine> rows = written.getValue();
        assertThat(rows).hasSize(20);
        assertThat(rows.get(0).amount()).isNull();
        assertThat(rows.get(1).turnoverRate()).isNull();
        assertThat(rows.get(2).amount()).isEqualByComparingTo("1000");
        assertThat(rows.get(2).turnoverRate()).isEqualByComparingTo("2");
        assertThat(rows.get(19).amount()).isNull();
        for (int i = 0; i < rows.size(); i++) {
            assertThat(rows.get(i)).usingRecursiveComparison().ignoringFields("amount", "turnoverRate")
                    .isEqualTo(fetched.get(i));
        }
    }

    @Test
    void usesNewSupplierTradingMetricsWhenTheyArePresent() {
        List<EastMoneyKLine> old = window("002815", "50").stream().map(row ->
                new EastMoneyKLine(row.symbol(), row.tradeDate(), row.open(), row.close(), row.high(), row.low(),
                        row.volume(), new BigDecimal("1000"), new BigDecimal("2"))).toList();
        List<EastMoneyKLine> fetched = old.stream().map(row ->
                new EastMoneyKLine(row.symbol(), row.tradeDate(), row.open(), row.close(), row.high(), row.low(),
                        row.volume(), new BigDecimal("2000"), new BigDecimal("3"))).toList();
        when(store.recentBars("002815", DATE.minusDays(1), 20)).thenReturn(old.subList(0, 19), old.subList(0, 19), fetched);
        when(client.fetchDailyKLines(anyString(), any(), any())).thenReturn(fetched);

        assertThat(service.ensureWindow(List.of("002815"), DATE, 20)).isEmpty();
        verify(store).upsertBars(eq(fetched), anyString());
    }

    @Test
    void repairsEveryUniqueSuppliedSymbolBeyondOneHundredAndRevalidatesThePersistedWindow() {
        List<String> symbols = IntStream.range(0, 125).mapToObj(index -> String.format("600%03d", index)).toList();
        Map<String, List<EastMoneyKLine>> persisted = new ConcurrentHashMap<>();
        when(store.recentBars(anyString(), any(), anyInt()))
                .thenAnswer(invocation -> persisted.getOrDefault(invocation.getArgument(0), List.of()));
        when(client.fetchDailyKLines(anyString(), any(), any()))
                .thenAnswer(invocation -> window(invocation.getArgument(0), "50"));
        doAnswer(invocation -> {
            List<EastMoneyKLine> rows = invocation.getArgument(0);
            persisted.put(rows.get(0).symbol(), rows);
            return null;
        }).when(store).upsertBars(anyList(), anyString());
        List<String> withDuplicates = new ArrayList<>(symbols);
        withDuplicates.addAll(symbols);

        assertThat(service.ensureWindow(withDuplicates, DATE, 20)).isEmpty();
        assertThat(persisted).hasSize(125);
        verify(client, times(125)).fetchDailyKLines(anyString(), any(), any());
        assertThat(service.ensureWindow(symbols, DATE, 20)).isEmpty();
        verify(client, times(125)).fetchDailyKLines(anyString(), any(), any());
    }

    @Test
    void aLargeRepairKeepsAtMostFourSupplierRequestsInFlight() throws Exception {
        List<String> symbols = IntStream.range(0, 125).mapToObj(index -> String.format("600%03d", index)).toList();
        CountDownLatch firstWorkers = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(store.recentBars(anyString(), any(), anyInt())).thenReturn(List.of());
        when(client.fetchDailyKLines(anyString(), any(), any())).thenAnswer(invocation -> {
            int concurrent = inFlight.incrementAndGet();
            maximum.accumulateAndGet(concurrent, Math::max);
            firstWorkers.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test worker timed out");
                throw new IllegalStateException("供应商缺口");
            } finally { inFlight.decrementAndGet(); }
        });
        CompletableFuture<List<String>> run = CompletableFuture.supplyAsync(() -> service.ensureWindow(symbols, DATE, 20));
        try {
            assertThat(firstWorkers.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(inFlight.get()).isEqualTo(4);
        } finally { release.countDown(); }
        assertThat(run.get(5, TimeUnit.SECONDS)).singleElement().asString().contains("125只");
        assertThat(maximum.get()).isEqualTo(4);
        verify(client, times(125)).fetchDailyKLines(anyString(), any(), any());
    }

    @Test
    void overlappingRefreshesCoalesceTheSameWindowIntoOneSupplierRequest() throws Exception {
        CountDownLatch remoteStarted = new CountDownLatch(1);
        CountDownLatch secondLocalRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger localReads = new AtomicInteger();
        when(store.recentBars(anyString(), any(), anyInt())).thenAnswer(invocation -> {
            if (localReads.incrementAndGet() >= 3) secondLocalRead.countDown();
            return List.of();
        });
        when(client.fetchDailyKLines(anyString(), any(), any())).thenAnswer(invocation -> {
            remoteStarted.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test worker timed out");
            throw new IllegalStateException("供应商缺口");
        });
        CompletableFuture<List<String>> first = CompletableFuture.supplyAsync(() -> service.ensureWindow(List.of("600001"), DATE, 20));
        assertThat(remoteStarted.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<List<String>> second = CompletableFuture.supplyAsync(() -> service.ensureWindow(List.of("600001"), DATE, 20));
        try { assertThat(secondLocalRead.await(5, TimeUnit.SECONDS)).isTrue(); }
        finally { release.countDown(); }
        assertThat(first.get(5, TimeUnit.SECONDS)).isNotEmpty();
        assertThat(second.get(5, TimeUnit.SECONDS)).isNotEmpty();
        verify(client, times(1)).fetchDailyKLines(anyString(), any(), any());
    }

    @Test
    void unverifiedObservationDayNeverFetchesHistory() {
        assertThat(service.ensureWindow(List.of("002815"), LocalDate.parse("2027-01-04"), 20)).isNotEmpty();
        assertThat(service.ensureWindow(List.of("002815"), LocalDate.parse("2026-10-01"), 20)).isNotEmpty();
        verifyNoInteractions(client, store);
    }

    private List<EastMoneyKLine> window(String symbol, String price) {
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate day = DATE;
        for (int index = 0; index < 20; index++) {
            day = clock.previousTradingDay(day);
            rows.add(bar(symbol, day, price));
        }
        Collections.reverse(rows);
        return rows;
    }

    private EastMoneyKLine bar(String symbol, LocalDate date, String price) {
        BigDecimal value = new BigDecimal(price);
        return new EastMoneyKLine(symbol, date, value, value, value, value, BigDecimal.ONE, null, null);
    }
}
