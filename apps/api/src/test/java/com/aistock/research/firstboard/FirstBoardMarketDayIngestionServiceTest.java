package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirstBoardMarketDayIngestionServiceTest {

    private FirstBoardMarketDayIngestionService service;

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdown();
    }

    @Test
    void includesFetchedStockAfterItReachesFifteenStoredBars() {
        LocalDate scanDate = LocalDate.parse("2026-08-28");
        LocalDate baselineDate = LocalDate.parse("2026-08-27");
        FirstBoardMarketDataGateway gateway = mock(FirstBoardMarketDataGateway.class);
        FirstBoardDailyBarStore store = mock(FirstBoardDailyBarStore.class);
        TradingClockService clock = mock(TradingClockService.class);
        when(clock.previousTradingDay(scanDate)).thenReturn(baselineDate);
        when(store.marketDay(baselineDate)).thenReturn(Optional.empty());
        when(store.latestReadyMarketDay()).thenReturn(Optional.empty());
        when(gateway.fullMarketQuotes()).thenReturn(new AshareQuoteSnapshot(
                List.of(quote("600000"), quote("001234")), 2, 2, 2, 0,
                true, "test", Instant.parse("2026-08-28T07:00:00Z")));
        when(store.completedBarCounts(any(), eq(baselineDate), eq(15))).thenReturn(
                Map.of("600000", 15, "001234", 0),
                Map.of("600000", 15, "001234", 15));
        when(store.closingPrices(any(), eq(baselineDate))).thenReturn(
                Map.of("600000", BigDecimal.TEN),
                Map.of("600000", BigDecimal.TEN, "001234", BigDecimal.TEN));
        when(gateway.dailyBars(any(), any(), any())).thenReturn(List.of());
        when(gateway.limitUpPool(baselineDate)).thenReturn(List.of());
        when(store.calculateBarDataVersion(baselineDate)).thenReturn("bars-v1");
        service = new FirstBoardMarketDayIngestionService(gateway, store, clock);

        FirstBoardIngestionProgress progress = service.ensureBaselineDayReady(scanDate);

        assertThat(progress.status()).isEqualTo(FirstBoardMarketDayStatus.READY);
        assertThat(progress.completed()).isEqualTo(2);
        assertThat(progress.total()).isEqualTo(2);
        assertThat(progress.failed()).isZero();
        verify(store, times(2)).completedBarCounts(any(), eq(baselineDate), eq(15));
    }

    @Test
    void filtersStockBelowFifteenBarsWhenAnotherValidCurveExists() {
        LocalDate scanDate = LocalDate.parse("2026-08-28");
        LocalDate baselineDate = LocalDate.parse("2026-08-27");
        FirstBoardMarketDataGateway gateway = mock(FirstBoardMarketDataGateway.class);
        FirstBoardDailyBarStore store = mock(FirstBoardDailyBarStore.class);
        TradingClockService clock = mock(TradingClockService.class);
        when(clock.previousTradingDay(scanDate)).thenReturn(baselineDate);
        when(store.marketDay(baselineDate)).thenReturn(Optional.empty());
        when(store.latestReadyMarketDay()).thenReturn(Optional.empty());
        when(gateway.fullMarketQuotes()).thenReturn(new AshareQuoteSnapshot(
                List.of(quote("600000"), quote("001234")), 2, 2, 2, 0,
                true, "test", Instant.parse("2026-08-28T07:00:00Z")));
        when(store.completedBarCounts(any(), eq(baselineDate), eq(15))).thenReturn(
                Map.of("600000", 15, "001234", 0),
                Map.of("600000", 15, "001234", 14));
        when(store.closingPrices(any(), eq(baselineDate))).thenReturn(
                Map.of("600000", BigDecimal.TEN));
        when(gateway.dailyBars(any(), any(), any())).thenThrow(new IllegalStateException("provider limited"));
        when(gateway.limitUpPool(baselineDate)).thenReturn(List.of());
        when(store.calculateBarDataVersion(baselineDate)).thenReturn("bars-v1");
        service = new FirstBoardMarketDayIngestionService(gateway, store, clock);

        FirstBoardIngestionProgress progress = service.ensureBaselineDayReady(scanDate);

        assertThat(progress.status()).isEqualTo(FirstBoardMarketDayStatus.READY);
        assertThat(progress.completed()).isEqualTo(1);
        assertThat(progress.total()).isEqualTo(1);
        assertThat(progress.failed()).isZero();
        assertThat(progress.errorSummary()).isNull();
    }

    @Test
    void failsClosedWhenNoSymbolHasFifteenStoredBars() {
        LocalDate scanDate = LocalDate.parse("2026-08-28");
        LocalDate baselineDate = LocalDate.parse("2026-08-27");
        FirstBoardMarketDataGateway gateway = mock(FirstBoardMarketDataGateway.class);
        FirstBoardDailyBarStore store = mock(FirstBoardDailyBarStore.class);
        TradingClockService clock = mock(TradingClockService.class);
        when(clock.previousTradingDay(scanDate)).thenReturn(baselineDate);
        when(store.marketDay(baselineDate)).thenReturn(Optional.empty());
        when(store.latestReadyMarketDay()).thenReturn(Optional.empty());
        when(gateway.fullMarketQuotes()).thenReturn(new AshareQuoteSnapshot(
                List.of(quote("001234")), 1, 1, 1, 0,
                true, "test", Instant.parse("2026-08-28T07:00:00Z")));
        when(store.completedBarCounts(any(), eq(baselineDate), eq(15))).thenReturn(
                Map.of("001234", 0),
                Map.of("001234", 14));
        when(store.closingPrices(any(), eq(baselineDate))).thenReturn(Map.of());
        when(gateway.dailyBars(any(), any(), any())).thenReturn(List.of());
        service = new FirstBoardMarketDayIngestionService(gateway, store, clock);

        FirstBoardIngestionProgress progress = service.ensureBaselineDayReady(scanDate);

        assertThat(progress.status()).isEqualTo(FirstBoardMarketDayStatus.FAILED);
        assertThat(progress.completed()).isZero();
        assertThat(progress.total()).isZero();
        assertThat(progress.failed()).isEqualTo(1);
        assertThat(progress.nextRetryAt()).isNotNull();
        assertThat(progress.errorSummary()).contains("至少 15 根有效日线");
        verify(gateway, never()).limitUpPool(any());
    }

    @Test
    void repairsDeclaredReadyDayWhenItsExactDateBarIsMissing() {
        LocalDate scanDate = LocalDate.parse("2026-09-03");
        LocalDate baselineDate = LocalDate.parse("2026-09-02");
        FirstBoardMarketDataGateway gateway = mock(FirstBoardMarketDataGateway.class);
        FirstBoardDailyBarStore store = mock(FirstBoardDailyBarStore.class);
        TradingClockService clock = mock(TradingClockService.class);
        FirstBoardMarketDay falselyReady = new FirstBoardMarketDay(
                baselineDate, baselineDate, FirstBoardMarketDayStatus.READY,
                1, 1, 0, true, "stale-bars", Instant.parse("2026-09-02T07:20:00Z"),
                Instant.parse("2026-09-02T07:21:00Z"), null, null);
        when(clock.previousTradingDay(scanDate)).thenReturn(baselineDate);
        when(store.marketDay(baselineDate)).thenReturn(Optional.of(falselyReady));
        when(store.storedBarCount(baselineDate)).thenReturn(0);
        when(gateway.fullMarketQuotes()).thenReturn(new AshareQuoteSnapshot(
                List.of(quote("600000")), 1, 1, 1, 0,
                true, "test", Instant.parse("2026-09-03T03:00:00Z")));
        when(store.completedBarCounts(any(), eq(baselineDate), eq(15))).thenReturn(
                Map.of("600000", 15));
        when(store.closingPrices(any(), eq(baselineDate))).thenReturn(
                Map.of(), Map.of("600000", BigDecimal.TEN));
        when(gateway.dailyBars("600000", baselineDate, baselineDate)).thenReturn(List.of());
        when(gateway.limitUpPool(baselineDate)).thenReturn(List.of());
        when(store.calculateBarDataVersion(baselineDate)).thenReturn("repaired-bars");
        service = new FirstBoardMarketDayIngestionService(gateway, store, clock);

        FirstBoardIngestionProgress progress = service.ensureBaselineDayReady(scanDate);

        assertThat(progress.status()).isEqualTo(FirstBoardMarketDayStatus.READY);
        assertThat(progress.total()).isEqualTo(1);
        assertThat(progress.completed()).isEqualTo(1);
        verify(gateway).dailyBars("600000", baselineDate, baselineDate);
    }

    private EastMoneyQuote quote(String symbol) {
        return new EastMoneyQuote(
                symbol, "测试股份", "沪深", "测试行业",
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("1000"), new BigDecimal("10000"), null, null, null,
                "test", "https://example.test", Instant.parse("2026-08-28T07:00:00Z"));
    }
}
