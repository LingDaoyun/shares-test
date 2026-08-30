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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirstBoardBaselineServiceTest {

    private FirstBoardBaselineService service;

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdown();
    }

    @Test
    void excludesCurvesBelowFifteenBarsButKeepsFifteenToFiftyNineAsInsufficient() throws Exception {
        LocalDate scanDate = LocalDate.parse("2026-08-28");
        LocalDate baselineDate = LocalDate.parse("2026-08-27");
        FirstBoardMarketDataGateway gateway = mock(FirstBoardMarketDataGateway.class);
        FirstBoardMarketDayIngestionService ingestionService = mock(FirstBoardMarketDayIngestionService.class);
        FirstBoardDailyBarStore dailyBarStore = mock(FirstBoardDailyBarStore.class);
        FirstBoardBaselineStore baselineStore = mock(FirstBoardBaselineStore.class);
        FirstBoardTechnicalEvaluator evaluator = mock(FirstBoardTechnicalEvaluator.class);
        TradingClockService clock = mock(TradingClockService.class);
        FirstBoardMarketDay readyDay = new FirstBoardMarketDay(
                baselineDate, baselineDate, FirstBoardMarketDayStatus.READY,
                2, 2, 0, true, "bars-v1", Instant.now(), Instant.now(), null, null);
        FirstBoardBaselineRun run = new FirstBoardBaselineRun(
                UUID.randomUUID(), scanDate, baselineDate, FirstBoardBaselineService.BASELINE_RULE_VERSION,
                "bars-v1", FirstBoardBaselineRunStatus.BUILDING, 3, 2, 0, 1,
                0, Instant.now(), null, null);
        CountDownLatch published = new CountDownLatch(1);

        when(clock.isMarketClosedDay(scanDate)).thenReturn(false);
        when(clock.previousTradingDay(scanDate)).thenReturn(baselineDate);
        when(dailyBarStore.marketDay(baselineDate)).thenReturn(Optional.of(readyDay));
        when(baselineStore.findReady(scanDate, baselineDate,
                FirstBoardBaselineService.BASELINE_RULE_VERSION, "bars-v1")).thenReturn(Optional.empty());
        when(gateway.fullMarketQuotes()).thenReturn(new AshareQuoteSnapshot(
                List.of(quote("600000"), quote("001234"), quote("000003")),
                3, 3, 3, 0, true, "test", Instant.now()));
        when(dailyBarStore.completedBarCounts(any(), eq(baselineDate), eq(15))).thenReturn(
                Map.of("600000", 60, "001234", 15, "000003", 14));
        when(dailyBarStore.confirmedLimitUpSymbols(baselineDate)).thenReturn(java.util.Set.of());
        when(dailyBarStore.recentBars(anyString(), eq(baselineDate), eq(260))).thenReturn(List.of());
        when(evaluator.evaluate(eq("600000"), any(), any(), eq(FirstBoardBoard.MAIN),
                anyList(), eq(baselineDate), anyBoolean()))
                .thenReturn(Optional.of(baseline("600000", baselineDate)));
        when(evaluator.evaluate(eq("001234"), any(), any(), eq(FirstBoardBoard.MAIN),
                anyList(), eq(baselineDate), anyBoolean())).thenReturn(Optional.empty());
        when(baselineStore.startRun(any(), any(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(run);
        when(baselineStore.publish(eq(run), any())).thenAnswer(invocation -> {
            published.countDown();
            return new FirstBoardBaselineSnapshot(
                    scanDate, baselineDate, Instant.now(), Map.of("600000", baseline("600000", baselineDate)),
                    3, 2, 0, 1);
        });
        service = new FirstBoardBaselineService(
                gateway, ingestionService, dailyBarStore, baselineStore, evaluator, clock);

        FirstBoardBaselineAccess access = service.access(scanDate);

        assertThat(access.status()).isEqualTo(FirstBoardAlertStatus.UPDATING);
        assertThat(published.await(3, TimeUnit.SECONDS)).isTrue();
        verify(baselineStore).startRun(
                scanDate, baselineDate, FirstBoardBaselineService.BASELINE_RULE_VERSION,
                "bars-v1", 3, 2, 0, 1);
        verify(evaluator, never()).evaluate(eq("000003"), any(), any(), any(FirstBoardBoard.class),
                anyList(), eq(baselineDate), anyBoolean());
    }

    @Test
    void doesNotReuseLegacyV1BaselineAfterCurvePolicyChange() {
        LocalDate scanDate = LocalDate.parse("2026-08-28");
        LocalDate baselineDate = LocalDate.parse("2026-08-27");
        FirstBoardMarketDataGateway gateway = mock(FirstBoardMarketDataGateway.class);
        FirstBoardMarketDayIngestionService ingestionService = mock(FirstBoardMarketDayIngestionService.class);
        FirstBoardDailyBarStore dailyBarStore = mock(FirstBoardDailyBarStore.class);
        FirstBoardBaselineStore baselineStore = mock(FirstBoardBaselineStore.class);
        FirstBoardTechnicalEvaluator evaluator = mock(FirstBoardTechnicalEvaluator.class);
        TradingClockService clock = mock(TradingClockService.class);
        FirstBoardMarketDay readyDay = new FirstBoardMarketDay(
                baselineDate, baselineDate, FirstBoardMarketDayStatus.READY,
                4951, 4951, 0, true, "bars-v1", Instant.now(), Instant.now(), null, null);
        FirstBoardBaselineSnapshot legacy = new FirstBoardBaselineSnapshot(
                scanDate, baselineDate, Instant.now(), Map.of(), 5905, 5553, 472, 137);

        when(clock.isMarketClosedDay(scanDate)).thenReturn(false);
        when(clock.previousTradingDay(scanDate)).thenReturn(baselineDate);
        when(dailyBarStore.marketDay(baselineDate)).thenReturn(Optional.of(readyDay));
        when(baselineStore.findReady(
                scanDate, baselineDate, "first-board-baseline-v1", "bars-v1"))
                .thenReturn(Optional.of(legacy));
        when(baselineStore.findReady(
                scanDate, baselineDate, "first-board-baseline-v2", "bars-v1"))
                .thenReturn(Optional.empty());
        service = new FirstBoardBaselineService(
                gateway, ingestionService, dailyBarStore, baselineStore, evaluator, clock);

        FirstBoardBaselineAccess access = service.access(scanDate);

        assertThat(access.status()).isEqualTo(FirstBoardAlertStatus.UPDATING);
        verify(baselineStore, atLeastOnce()).findReady(
                scanDate, baselineDate, "first-board-baseline-v2", "bars-v1");
    }

    private EastMoneyQuote quote(String symbol) {
        return new EastMoneyQuote(
                symbol, "测试股份", "沪深", "测试行业",
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("1000"), new BigDecimal("10000"), null, null, null,
                "test", "https://example.test", Instant.parse("2026-08-28T07:00:00Z"));
    }

    private FirstBoardBaseline baseline(String symbol, LocalDate baselineDate) {
        return new FirstBoardBaseline(
                symbol, "测试股份", "测试行业", FirstBoardBoard.MAIN, baselineDate,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE,
                new BigDecimal("55"), BigDecimal.ONE, 5, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, List.of());
    }
}
