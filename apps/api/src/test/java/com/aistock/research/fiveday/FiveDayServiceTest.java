package com.aistock.research.fiveday;

import com.aistock.research.firstboard.*;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.tradefeedback.RecommendationAttestationService;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aistock.research.fiveday.FiveDayModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FiveDayServiceTest {
    private static final LocalDate SIGNAL = LocalDate.parse("2026-09-07");
    private final FirstBoardBaselineService baseline = mock(FirstBoardBaselineService.class);
    private final FirstBoardDailyBarStore store = mock(FirstBoardDailyBarStore.class);
    private final EastMoneyClient market = mock(EastMoneyClient.class);
    private final FiveDayHistoryService history = mock(FiveDayHistoryService.class);
    private final TradingClockService clock = new TradingClockService(Clock.fixed(
            Instant.parse("2026-09-08T01:00:00Z"), ZoneId.of("Asia/Shanghai")));
    private final FiveDayService service = new FiveDayService(baseline, store, market, clock,
            new FiveDayEvaluator(clock), history, mock(RecommendationAttestationService.class));

    @BeforeEach
    void configureData() {
        when(history.latest(any(), anyBoolean(), anyBoolean())).thenReturn(Optional.empty());
        when(market.enrichDailyKLineTradingMetrics(anyString(), any(), any(), anyList()))
                .thenAnswer(call -> call.getArgument(3));
        when(market.fetchFundFlowHistory(anyString(), any(), any())).thenAnswer(call ->
                bars(call.getArgument(0), "10").subList(20, 25).stream().map(row -> flow(row.symbol(), row.tradeDate())).toList());
    }

    @AfterEach
    void closeService() { service.close(); }

    @Test
    void backfillsTheWholeWindowBeforePreselectionAndKeepsOneQfqSequence() {
        List<EastMoneyKLine> old = bars("600001", "10");
        scope(Map.of("600001", old));
        when(store.recentBars("600001", SIGNAL, 25)).thenReturn(withoutInternalDay(old));
        List<EastMoneyKLine> replacement = bars("600001", "5");
        when(market.fetchDailyKLines(eq("600001"), any(), eq(SIGNAL))).thenReturn(replacement);

        Snapshot result = scan();

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.signalClose()).isEqualByComparingTo("5");
            assertThat(candidate.bars()).isEqualTo(replacement);
        });
        verify(market).fetchDailyKLines("600001", replacement.get(0).tradeDate(), SIGNAL);
        verify(store, atLeastOnce()).upsertBars(eq(replacement), anyString());
    }

    @Test
    void unresolvedPreselectionGapsCannotBecomeAFrozenEmptySuccess() {
        List<EastMoneyKLine> full = bars("600001", "10");
        List<EastMoneyKLine> partial = withoutInternalDay(full);
        scope(Map.of("600001", partial));
        when(store.recentBars("600001", SIGNAL, 25)).thenReturn(partial);
        when(market.fetchDailyKLines(eq("600001"), any(), eq(SIGNAL))).thenReturn(partial);

        Snapshot result = scan();

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.candidates()).isEmpty();
        assertThat(result.coverage().missingDataCount()).isEqualTo(1);
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("连续") && gap.contains("25"));
        verify(history, never()).capture(any());
    }

    @Test
    void aCompleteStoredWindowIsReusedWithoutFetchingHistoricalPrices() {
        List<EastMoneyKLine> full = bars("600001", "10");
        scope(Map.of("600001", full));
        when(store.recentBars("600001", SIGNAL, 25)).thenReturn(full);

        Snapshot result = scan();

        assertThat(result.candidates()).hasSize(1);
        verify(market, never()).fetchDailyKLines(anyString(), any(), any());
    }

    @Test
    void preselectionUsesFreshStoredPricesEvenWhenTheLegacyBaselineIsMissingDays() {
        List<EastMoneyKLine> full = bars("600001", "10");
        scope(Map.of("600001", withoutInternalDay(full)));
        when(store.recentBars("600001", SIGNAL, 25)).thenReturn(full);

        Snapshot result = scan();

        assertThat(result.coverage().priceQualifiedCount()).isEqualTo(1);
        assertThat(result.candidates()).hasSize(1);
        verify(market, never()).fetchDailyKLines(anyString(), any(), any());
    }

    @Test
    void aCandidateCanBeSavedWhileOtherMissingHistoriesRemainDisclosed() {
        List<EastMoneyKLine> good = bars("600001", "10");
        List<EastMoneyKLine> missing = withoutInternalDay(bars("600002", "10"));
        scope(Map.of("600001", good, "600002", missing));
        when(store.recentBars("600001", SIGNAL, 25)).thenReturn(good);
        when(store.recentBars("600002", SIGNAL, 25)).thenReturn(missing);
        when(market.fetchDailyKLines(eq("600002"), any(), eq(SIGNAL))).thenReturn(missing);

        Snapshot result = scan();

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.coverage().missingDataCount()).isEqualTo(1);
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("连续") && gap.contains("25"));
        verify(history).capture(any());
    }

    @Test
    void priceRepairRunsAtMostEightRequestsAndReportsCoverageProgress() throws Exception {
        Map<String, List<EastMoneyKLine>> stocks = new LinkedHashMap<>();
        for (int i = 1; i <= 9; i++) stocks.put("60000" + i, withoutInternalDay(bars("60000" + i, "10")));
        scope(stocks);
        when(store.recentBars(anyString(), eq(SIGNAL), eq(25))).thenAnswer(call -> stocks.get(call.getArgument(0)));
        CountDownLatch entered = new CountDownLatch(8), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        when(market.fetchDailyKLines(anyString(), any(), eq(SIGNAL))).thenAnswer(call -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            entered.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return bars(call.getArgument(0), "10");
            } finally { active.decrementAndGet(); }
        });
        try {
            service.start(false, false);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(service.snapshot(false, false).message()).contains("补齐/检查连续日线", "0/9");
            assertThat(active.get()).isEqualTo(8);
        } finally { release.countDown(); }
        Snapshot result = completed();
        assertThat(result.candidates()).hasSize(9);
        assertThat(maximum.get()).isEqualTo(8);
    }

    private Snapshot scan() {
        service.start(false, false);
        return completed();
    }

    private Snapshot completed() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(service.snapshot(false, false).status()).isNotEqualTo("SCANNING"));
        return service.snapshot(false, false);
    }

    private void scope(Map<String, List<EastMoneyKLine>> rows) {
        Map<String, FirstBoardLeaderBaselineStock> stocks = new LinkedHashMap<>();
        rows.forEach((symbol, bars) -> stocks.put(symbol, new FirstBoardLeaderBaselineStock(
                symbol, "示例公司", "示例行业", FirstBoardBoard.MAIN, SIGNAL,
                null, null, null, null, null, null, 0, null, null, null, null, false,
                bars.stream().map(row -> new FirstBoardBar(row.tradeDate(), row.open(), row.close(),
                        row.high(), row.low(), row.volume())).toList())));
        when(baseline.leaderAccess(any())).thenReturn(new FirstBoardLeaderBaselineAccess(
                FirstBoardAlertStatus.READY, SIGNAL.plusDays(1), SIGNAL, Instant.parse("2026-09-07T08:00:00Z"),
                stocks, null, stocks.size(), stocks.size(), 0, 0, List.of()));
    }

    private List<EastMoneyKLine> bars(String symbol, String price) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = SIGNAL;
        for (int i = 0; i < 25; i++) { dates.add(0, cursor); cursor = clock.previousTradingDay(cursor); }
        List<EastMoneyKLine> rows = new ArrayList<>();
        BigDecimal close = new BigDecimal(price);
        for (int i = 0; i < 25; i++) rows.add(new EastMoneyKLine(symbol, dates.get(i), close, close,
                close.multiply(new BigDecimal("1.02")), close.multiply(new BigDecimal("0.98")),
                new BigDecimal("6000000"), new BigDecimal("60000000"), new BigDecimal(i < 20 ? "2" : "3")));
        return rows;
    }

    private List<EastMoneyKLine> withoutInternalDay(List<EastMoneyKLine> full) {
        List<EastMoneyKLine> partial = new ArrayList<>(full);
        partial.remove(22);
        return partial;
    }

    private EastMoneyFundFlowSnapshot flow(String symbol, LocalDate day) {
        return new EastMoneyFundFlowSnapshot(symbol, null, new BigDecimal("1000000"), null, null, null, null,
                null, null, null, null, null, "fixture", "fixture", null, day, null);
    }
}
