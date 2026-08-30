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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FirstBoardMarketDayIngestionServiceTest {

    private FirstBoardMarketDayIngestionService service;

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdown();
    }

    @Test
    void treatsSuccessfullyFetchedNewStockWithFewerThanSixtyBarsAsCovered() {
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
        when(gateway.dailyBars(any(), any(), any())).thenReturn(List.of());
        when(gateway.limitUpPool(baselineDate)).thenReturn(List.of());
        when(store.completedBarCounts(any(), any(), anyInt())).thenReturn(
                Map.of("600000", 60, "001234", 0),
                Map.of("001234", 20),
                Map.of("600000", 60, "001234", 20));
        when(store.calculateBarDataVersion(baselineDate)).thenReturn("bars-v1");
        service = new FirstBoardMarketDayIngestionService(gateway, store, clock);

        FirstBoardIngestionProgress progress = service.ensureBaselineDayReady(scanDate);

        assertThat(progress.status()).isEqualTo(FirstBoardMarketDayStatus.READY);
        assertThat(progress.completed()).isEqualTo(2);
        assertThat(progress.total()).isEqualTo(2);
        assertThat(progress.failed()).isZero();
    }

    @Test
    void failsClosedWhenOneStockHistoryProviderFails() {
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
        when(gateway.dailyBars(any(), any(), any())).thenThrow(new IllegalStateException("provider limited"));
        when(gateway.limitUpPool(baselineDate)).thenReturn(List.of());
        when(store.completedBarCounts(any(), any(), anyInt()))
                .thenReturn(Map.of("600000", 60, "001234", 0));
        when(store.calculateBarDataVersion(baselineDate)).thenReturn("bars-v1");
        service = new FirstBoardMarketDayIngestionService(gateway, store, clock);

        FirstBoardIngestionProgress progress = service.ensureBaselineDayReady(scanDate);

        assertThat(progress.status()).isEqualTo(FirstBoardMarketDayStatus.FAILED);
        assertThat(progress.completed()).isEqualTo(1);
        assertThat(progress.total()).isEqualTo(2);
        assertThat(progress.failed()).isEqualTo(1);
        assertThat(progress.nextRetryAt()).isNotNull();
        assertThat(progress.errorSummary()).contains("历史日线补齐不完整");
    }

    private EastMoneyQuote quote(String symbol) {
        return new EastMoneyQuote(
                symbol, "测试股份", "沪深", "测试行业",
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("1000"), new BigDecimal("10000"), null, null, null,
                "test", "https://example.test", Instant.parse("2026-08-28T07:00:00Z"));
    }
}
