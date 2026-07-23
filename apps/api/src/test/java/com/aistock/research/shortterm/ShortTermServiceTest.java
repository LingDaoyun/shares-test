package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.quality.EvidenceCompleteness;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.trading.QuoteFreshnessSnapshot;
import com.aistock.research.trading.QuoteFreshnessService;
import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.trading.TradingClockService;
import com.aistock.research.valuation.ValuationContextState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortTermServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-07T06:59:00Z"), SHANGHAI);
    private final StubEastMoneyClient eastMoneyClient = new StubEastMoneyClient();
    private final TradingClockService tradingClockService = new TradingClockService(TEST_CLOCK);
    private final ShortTermService service = new ShortTermService(
            eastMoneyClient,
            new EvidenceCompletenessService(),
            tradingClockService,
            new QuoteFreshnessService(tradingClockService, TEST_CLOCK)
    );

    @Test
    void shouldDefaultToFullMarketQuotePoolWithoutStaticStockWhitelist() {
        ShortTermReport report = service.report(8, null, null, null, null, null, null, null, null, null);

        assertThat(eastMoneyClient.requestedQuoteLimit).isEqualTo(6000);
        assertThat(report.ruleSet().scanLimit()).isEqualTo(6000);
        assertThat(report.ruleSet().klineLimit()).isEqualTo(60);
    }

    @Test
    void finalReportRestrictsExpensiveReviewToPreselectedSymbols() {
        eastMoneyClient.quotes = List.of(
                quote("600795", "国电电力", "4.90", "1.03", "12.95", "1.49", "900000000"),
                quote("002128", "电投能源", "26.40", "1.42", "14.08", "1.60", "800000000"),
                quote("601918", "新集能源", "9.86", "-3.52", "11.87", "1.46", "700000000")
        );
        eastMoneyClient.quotes.forEach(quote -> {
            eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), quote.latestPrice().toPlainString(), "180000"));
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        });

        ShortTermReport report = service.finalReport(
                new ShortTermScanRequest(3, 100, 10, null, null, null, null, null, null, null),
                Set.of("600795", "002128")
        );

        assertThat(report.reviewedSymbols()).containsExactlyInAnyOrder("600795", "002128");
        assertThat(eastMoneyClient.requestedKlineSymbols).containsExactlyInAnyOrder("600795", "002128");
        assertThat(eastMoneyClient.requestedKlineSymbols).doesNotContain("601918");
        assertThat(eastMoneyClient.requestedFinancialSymbols).containsExactlyInAnyOrder("600795", "002128");
        assertThat(eastMoneyClient.requestedFinancialSymbols).doesNotContain("601918");
        assertThat(report.universeCount()).isEqualTo(3);
    }

    @Test
    void finalReportRejectsEmptyPreselection() {
        assertThatThrownBy(() -> service.finalReport(ShortTermScanRequest.empty(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预选股票为空");
    }

    @Test
    void shouldExposeCoverageAndKeepAbsentCompatibilityCoverageUnreliable() {
        eastMoneyClient.quotes = IntStream.range(0, 9)
                .mapToObj(index -> quote(
                        "6001" + index,
                        "覆盖样本" + index,
                        "10.62",
                        "1.20",
                        "18",
                        "1.60",
                        "600000000"
                ))
                .toList();
        eastMoneyClient.snapshotExpectedCount = 10;
        eastMoneyClient.snapshotComplete = false;
        eastMoneyClient.quotes.forEach(quote ->
                eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "180000")));

        ShortTermReport report = service.report(
                new ShortTermScanRequest(3, 100, 9, null, null, null, null, null, null, null)
        );

        assertThat(report.coverage().expectedCount()).isEqualTo(10);
        assertThat(report.coverage().fetchedCount()).isEqualTo(9);
        assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("0.9000");
        assertThat(report.coverage().executionReliable()).isTrue();
        assertThat(report.reviewedSymbols()).containsExactlyInAnyOrderElementsOf(eastMoneyClient.requestedKlineSymbols);
        assertThat(report.dataCutoffAt()).isEqualTo(Instant.parse("2026-07-07T06:58:00Z"));

        ShortTermReport compatible = new ShortTermReport(
                "旧报告", 1, 1, 1, 0, "旧格式", null,
                List.of(), null, null, List.of(), List.of(), null, List.of(), Instant.EPOCH);

        assertThat(compatible.coverage().executionReliable()).isFalse();
        assertThat(compatible.reviewedSymbols()).isEmpty();
        assertThat(compatible.dataCutoffAt()).isNull();
    }

    @Test
    void oldStoredJsonDefaultsCoverageToExplicitlyUnreliable() throws Exception {
        ShortTermReport report = new ObjectMapper()
                .findAndRegisterModules()
                .readValue("{\"scope\":\"旧报告\"}", ShortTermReport.class);

        assertThat(report.coverage()).isEqualTo(ShortTermCoverageSnapshot.unreliable());
        assertThat(report.reviewedSymbols()).isEmpty();
        assertThat(report.dataCutoffAt()).isNull();
    }

    @Test
    void staleCoverageSnapshotNeverBecomesExecutionReliable() {
        eastMoneyClient.quotes = List.of(
                quote("600001", "过期覆盖样本", "10.62", "1.20", "18", "1.60", "600000000")
        );
        eastMoneyClient.snapshotFetchedAt = Instant.parse("2026-07-07T06:50:00Z");
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));

        ShortTermReport report = service.report(ShortTermScanRequest.empty());

        assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("1.0000");
        assertThat(report.coverage().executionReliable()).isFalse();
        assertThat(report.marketSentiment().phase()).isEqualTo("行情覆盖不足");
    }

    @Test
    void actionableTailUsesOnlyMinutesFrom1445InclusiveTo1457Exclusive() {
        eastMoneyClient.quotes = List.of(
                quote("600001", "尾盘边界", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.intraday.put("600001", List.of(
                intraday("600001", "2026-07-07T14:44", "10.00", "10.00", "10.01", "9.99", "10000", "10000000", "10.00"),
                intraday("600001", "2026-07-07T14:45", "10.05", "10.10", "10.11", "10.04", "12000", "12000000", "10.05"),
                intraday("600001", "2026-07-07T14:52", "10.15", "10.20", "10.21", "10.14", "14000", "14000000", "10.10"),
                intraday("600001", "2026-07-07T14:57", "8.00", "8.00", "8.01", "7.99", "90000", "90000000", "9.80")
        ));

        ShortTermCandidate candidate = find(
                service.report(1, 100, 5, null, null, null, null, null, null, null),
                "600001"
        );

        assertThat(candidate.tailSignal().latestMinute()).isEqualTo("14:52");
        assertThat(candidate.tailSignal().tailStartPrice()).isEqualByComparingTo("10.10");
        assertThat(candidate.tailSignal().changeFromActionableTailPercent()).isEqualByComparingTo("0.99");
        assertThat(candidate.tailSignal().actionableTailWindow()).isTrue();
        assertThat(candidate.tailSignal().reasons()).anySatisfy(reason ->
                assertThat(reason).contains("14:57", "未参与"));
    }

    @Test
    void actionableTailNeverUsesMinuteAfterDecisionTime() {
        eastMoneyClient.quotes = List.of(
                quote("600001", "点时边界", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.intraday.put("600001", List.of(
                intraday("600001", "2026-07-07T14:45", "10.05", "10.10", "10.11", "10.04", "12000", "12000000", "10.05"),
                intraday("600001", "2026-07-07T14:52", "10.15", "10.20", "10.21", "10.14", "14000", "14000000", "10.10")
        ));
        Clock decisionClock = Clock.fixed(Instant.parse("2026-07-07T06:50:00Z"), SHANGHAI);
        TradingClockService decisionTradingClock = new TradingClockService(decisionClock);
        ShortTermService pointInTimeService = new ShortTermService(
                eastMoneyClient,
                new EvidenceCompletenessService(),
                decisionTradingClock,
                new QuoteFreshnessService(decisionTradingClock, decisionClock)
        );

        ShortTermCandidate candidate = find(
                pointInTimeService.report(1, 100, 5, null, null, null, null, null, null, null),
                "600001"
        );

        assertThat(candidate.tailSignal().latestMinute()).isEqualTo("14:45");
        assertThat(candidate.tailSignal().latestPrice()).isEqualByComparingTo("10.10");
    }

    @Test
    void shouldDefaultToThreeShortTermRecommendations() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry("600101", "光通信甲", "光通信", "10.62", "1.60", "35", "4.2", "900000000"),
                quoteWithIndustry("600102", "光通信乙", "光通信", "10.62", "1.50", "42", "4.6", "860000000"),
                quoteWithIndustry("600103", "光通信丙", "光通信", "10.62", "1.40", "48", "5.1", "820000000"),
                quoteWithIndustry("600104", "光通信丁", "光通信", "10.62", "1.30", "55", "5.5", "780000000"),
                quoteWithIndustry("600105", "光通信戊", "光通信", "10.62", "1.20", "60", "5.9", "740000000")
        );
        for (EastMoneyQuote quote : eastMoneyClient.quotes) {
            eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "180000"));
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        }

        ShortTermReport report = service.report(null, 100, 10, null, null, null, null, null, null, null);

        assertThat(report.candidates()).hasSize(3);
        assertThat(report.methodology()).anySatisfy(item ->
                assertThat(item).contains("只输出前三个", "热门方向", "分歧低吸"));
    }

    @Test
    void shouldComputeAtrAndSupportFromCompletedDailyBars() {
        eastMoneyClient.quotes = List.of(
                quote("600107", "波动样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600107", rightEarlyKLines("600107", "10.62", "180000"));
        eastMoneyClient.financials.put("600107", goodFinancial("600107"));

        ShortTermCandidate candidate = find(
                service.report(1, 100, 5, null, null, null, null, null, null, null),
                "600107"
        );

        assertThat(candidate.technical().atr14Percent()).isPositive();
        assertThat(candidate.technical().recentSupportPrice()).isPositive();
        assertThat(candidate.technical().recentSupportPrice()).isLessThan(candidate.latestPrice());
    }

    @Test
    void shouldAttachActionablePlanOnlyAfterFreshnessEvidenceAndActionGatesPass() {
        eastMoneyClient.quotes = List.of(
                quote("600108", "计划样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600108", rightEarlyKLines("600108", "10.62", "180000"));
        eastMoneyClient.financials.put("600108", goodFinancial("600108"));

        ShortTermCandidate blocked = find(
                service.report(1, 100, 5, null, null, null, null, null, null, null),
                "600108"
        );

        assertThat(blocked.tradePlan().status()).isEqualTo("BLOCKED");

        ShortTermCandidate executable = copyWithExecutionState(
                blocked,
                new TradingAdvice("LIGHT_TRIAL", "轻仓试错", 72, "测试可执行动作", List.of(), List.of()),
                new QuoteFreshnessSnapshot(
                        "REALTIME",
                        "实时",
                        true,
                        false,
                        LocalDate.of(2026, 7, 7),
                        Instant.parse("2026-07-07T06:54:00Z"),
                        60L,
                        "测试实时行情"
                ),
                new EvidenceCompleteness(
                        100,
                        "COMPLETE",
                        "证据完整",
                        true,
                        List.of("实时行情", "K线", "财报", "尾盘分时"),
                        List.of(),
                        List.of()
                )
        );

        ShortTermCandidate planned = service.attachTradePlan(executable, overnightRules());

        assertThat(planned.tradePlan().status()).isEqualTo("ACTIONABLE");
        assertThat(planned.tradePlan().referenceEntryPrice()).isEqualByComparingTo(executable.latestPrice());
        assertThat(planned.tradePlan().firstTargetPrice()).isNotNull();
        assertThat(planned.tradePlan().normalExitDate()).isAfter(executable.technical().tradeDate());

        ShortTermCandidate stale = copyWithExecutionState(
                executable,
                executable.todayAdvice(),
                new QuoteFreshnessSnapshot(
                        "STALE",
                        "过期",
                        true,
                        true,
                        LocalDate.of(2026, 7, 7),
                        Instant.parse("2026-07-07T06:40:00Z"),
                        900L,
                        "行情已过期"
                ),
                executable.evidenceCompleteness()
        );

        assertThat(service.attachTradePlan(stale, overnightRules()).tradePlan().status()).isEqualTo("BLOCKED");
    }

    @Test
    void shouldRankReviewedGoldenCrossTiersAndKeepWatchStatesNonExecutable() {
        eastMoneyClient.quotes = List.of(
                quote("600501", "确认金叉", "10.50", "1.60", "18", "1.6", "600000000"),
                quote("600502", "临界金叉", "10.30", "1.20", "18", "1.6", "600000000"),
                quote("600503", "延续金叉", "10.70", "1.00", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600501", confirmedRightEarlyKLines("600501", "10.50", "180000"));
        eastMoneyClient.klines.put("600502", approachingGoldenCrossKLines("600502"));
        eastMoneyClient.klines.put("600503", establishedRightSideKLines("600503"));
        eastMoneyClient.quotes.forEach(quote -> eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol())));

        ShortTermReport report = service.report(3, 100, 10, null, null, null, null, null, null, null);

        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600501", "600503", "600502");
        assertThat(find(report, "600501").technical().goldenCross().state()).isEqualTo("CONFIRMED");
        assertThat(find(report, "600502").technical().goldenCross().state()).isEqualTo("APPROACHING");
        assertThat(find(report, "600502").todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
        assertThat(report.quoteNote()).contains(
                "金叉K线复核 " + report.klineReviewedCount() + "/" + report.reviewedCount());
    }

    @Test
    void shouldRankRightSideConfirmationBeforeHigherScoringObservation() {
        eastMoneyClient.quotes = List.of(
                quote("600601", "确认样本", "10.62", "1.20", "90", "12", "600000000"),
                quote("600602", "观察样本", "10.62", "1.20", "12", "1.2", "600000000")
        );
        eastMoneyClient.klines.put("600601", rightEarlyKLines("600601", "10.62", "180000"));
        eastMoneyClient.klines.put("600602", rightEarlyKLines("600602", "10.62", "105000"));
        eastMoneyClient.financials.put("600601", acceptableFinancial("600601"));
        eastMoneyClient.financials.put("600602", goodFinancial("600602"));

        ShortTermReport report = service.report(2, 100, 10, null, null, null, null, null, null, null);

        ShortTermCandidate confirmed = find(report, "600601");
        ShortTermCandidate observed = find(report, "600602");
        assertThat(confirmed.technical().rightSideSignal()).isEqualTo("右侧早期确认");
        assertThat(observed.technical().rightSideSignal()).isEqualTo("右侧早期观察");
        assertThat(confirmed.action()).isEqualTo(observed.action()).isEqualTo("WATCH_RIGHT_SIDE");
        assertThat(confirmed.technical().goldenCross().priorityTier())
                .isEqualTo(observed.technical().goldenCross().priorityTier())
                .isEqualTo(1);
        assertThat(confirmed.score().finalScore()).isEqualByComparingTo("83.45");
        assertThat(observed.score().finalScore()).isEqualByComparingTo("89.00");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600601", "600602");
    }

    @Test
    void shouldRankEligibleObservationBeforeDataBlockedConfirmation() {
        eastMoneyClient.quotes = List.of(
                quote("600603", "待复核确认样本", "10.62", "1.20", "18", "1.6", "600000000"),
                quote("600604", "可执行观察样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600603", rightEarlyKLines("600603", "10.62", "180000"));
        eastMoneyClient.klines.put("600604", rightEarlyKLines("600604", "10.62", "105000"));
        eastMoneyClient.financials.put("600604", goodFinancial("600604"));

        ShortTermReport report = service.report(2, 100, 10, null, null, null, null, null, null, null);

        ShortTermCandidate blocked = find(report, "600603");
        ShortTermCandidate eligible = find(report, "600604");
        assertThat(blocked.technical().rightSideSignal()).isEqualTo("右侧早期确认");
        assertThat(blocked.action()).isEqualTo("DATA_REVIEW");
        assertThat(eligible.technical().rightSideSignal()).isEqualTo("右侧早期观察");
        assertThat(eligible.action()).isEqualTo("WATCH_RIGHT_SIDE");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600604", "600603");
    }

    @Test
    void shouldRankActionPriorityBeforeRightSideMaturity() {
        eastMoneyClient.quotes = List.of(
                quote("600605", "等回踩确认样本", "10.62", "5.20", "18", "1.6", "600000000"),
                quote("600606", "右侧观察样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600605", rightEarlyKLines("600605", "10.62", "180000"));
        eastMoneyClient.klines.put("600606", rightEarlyKLines("600606", "10.62", "105000"));
        eastMoneyClient.quotes.forEach(quote ->
                eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol())));

        ShortTermReport report = service.report(2, 100, 10, null, null, null, null, null, null, null);

        ShortTermCandidate pullback = find(report, "600605");
        ShortTermCandidate observation = find(report, "600606");
        assertThat(pullback.technical().rightSideSignal()).isEqualTo("右侧早期确认");
        assertThat(pullback.action()).isEqualTo("WAIT_PULLBACK");
        assertThat(observation.technical().rightSideSignal()).isEqualTo("右侧早期观察");
        assertThat(observation.action()).isEqualTo("WATCH_RIGHT_SIDE");
        assertThat(pullback.technical().goldenCross().priorityTier())
                .isEqualTo(observation.technical().goldenCross().priorityTier())
                .isEqualTo(1);
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600606", "600605");
    }

    @Test
    void shouldNotExecuteOverextendedConfirmedGoldenCross() {
        eastMoneyClient.quotes = List.of(
                quote("600504", "过度拉开金叉", "12.00", "1.60", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600504", recentGoldenCrossKLines("600504", 2));
        eastMoneyClient.financials.put("600504", goodFinancial("600504"));

        ShortTermCandidate overextendedConfirmed = find(
                service.report(3, 100, 10, null, null, null, null, null, null, null),
                "600504"
        );

        assertThat(overextendedConfirmed.technical().goldenCross().state()).isEqualTo("CONFIRMED");
        assertThat(overextendedConfirmed.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
    }

    @Test
    void shouldKeepSameDayUnfinishedGoldenCrossNonExecutableUntilFundamentalsArePresent() {
        eastMoneyClient.quotes = List.of(
                quote("600505", "未完成金叉", "10.50", "1.60", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600505", unfinishedFormingGoldenCrossKLines("600505"));

        ShortTermCandidate candidate = find(
                service.report(3, 100, 10, null, null, null, null, null, null, null),
                "600505"
        );

        assertThat(tradingClockService.isCompletedDailyBar(candidate.technical().tradeDate())).isFalse();
        assertThat(candidate.technical().goldenCross().state()).isEqualTo("FORMING");
        assertThat(candidate.action()).isEqualTo("DATA_REVIEW");
        assertThat(candidate.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
    }

    @Test
    void shouldRequireConstructiveVolumeBeforeFormingGoldenCrossWatchAdvice() {
        eastMoneyClient.quotes = List.of(
                quote("600506", "低量未完成金叉", "10.50", "1.60", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600506", unfinishedFormingGoldenCrossKLinesWithLowVolume("600506"));
        eastMoneyClient.financials.put("600506", goodFinancial("600506"));

        ShortTermCandidate candidate = find(
                service.report(3, 100, 10, null, null, null, null, null, null, null),
                "600506"
        );

        assertThat(candidate.technical().goldenCross().state()).isEqualTo("FORMING");
        assertThat(candidate.action()).isEqualTo("WAIT_CONFIRM");
        assertThat(candidate.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
    }

    @Test
    void shouldDiscoverPreviouslyUnknownHotIndustryFromCurrentMarketQuotes() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry("600101", "量子甲", "量子通信", "10.62", "3.20", "80", "8", "600000000"),
                quoteWithIndustry("600102", "量子乙", "量子通信", "10.62", "2.80", "90", "9", "500000000"),
                quoteWithIndustry("600103", "量子丙", "量子通信", "10.62", "2.40", "100", "10", "400000000"),
                quoteWithIndustry("600104", "冷门样本", "纸制品", "10.62", "0.10", "15", "1", "200000000")
        );
        for (EastMoneyQuote quote : eastMoneyClient.quotes) {
            eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "180000"));
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        }

        ShortTermReport report = service.report(4, 50, 10, null, null, null, null, null, null, null);

        assertThat(report.hotDirections()).extracting(ShortTermHotDirection::label).contains("量子通信");
        assertThat(report.hotDirections()).filteredOn(direction -> "量子通信".equals(direction.label()))
                .singleElement()
                .satisfies(direction -> assertThat(direction.heatScore()).isLessThan(new BigDecimal("100")));
        ShortTermCandidate hotCandidate = find(report, "600101");
        ShortTermCandidate coldCandidate = find(report, "600104");
        assertThat(hotCandidate.score().marketHeatScore()).isGreaterThan(coldCandidate.score().marketHeatScore());
    }

    @Test
    void shouldNotTurnIcePointMarketIntoBuyAfterTailConfirmation() {
        eastMoneyClient.quotes = List.of(
                quote("600201", "右侧候选", "10.62", "1.60", "18", "1.6", "600000000"),
                quote("600202", "下跌甲", "10.00", "-2.10", "18", "1.6", "300000000"),
                quote("600203", "下跌乙", "10.00", "-1.80", "18", "1.6", "300000000"),
                quote("600204", "下跌丙", "10.00", "-1.50", "18", "1.6", "300000000"),
                quote("600205", "下跌丁", "10.00", "-1.20", "18", "1.6", "300000000")
        );
        eastMoneyClient.klines.put("600201", rightEarlyKLines("600201", "10.62", "180000"));
        eastMoneyClient.financials.put("600201", goodFinancial("600201"));
        eastMoneyClient.intraday.put("600201", confirmedTail("600201"));

        ShortTermReport report = service.report(5, 50, 10, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600201");
        assertThat(report.marketSentiment().phase()).isEqualTo("冰点/混沌");
        assertThat(candidate.action()).isEqualTo("MARKET_RISK_WAIT");
        assertThat(candidate.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
    }

    @Test
    void shouldTreatNarrowHotSectorAsStructuralMarketInsteadOfIcePointBlock() {
        List<EastMoneyQuote> quotes = new ArrayList<>();
        quotes.add(quoteWithIndustry("600401", "算力甲", "算力设备", "10.62", "2.80", "48", "5.2", "900000000"));
        quotes.add(quoteWithIndustry("600402", "算力乙", "算力设备", "10.62", "2.50", "50", "5.4", "820000000"));
        quotes.add(quoteWithIndustry("600403", "算力丙", "算力设备", "10.62", "2.20", "52", "5.6", "760000000"));
        quotes.add(quoteWithIndustry("600404", "算力丁", "算力设备", "10.62", "1.90", "55", "5.8", "700000000"));
        for (int index = 0; index < 8; index++) {
            quotes.add(quote(String.format("6005%02d", index), "弱势样本" + index,
                    "10.00", "-1.40", "18", "1.6", "260000000"));
        }
        eastMoneyClient.quotes = quotes;
        eastMoneyClient.klines.put("600401", rightEarlyKLines("600401", "10.62", "180000"));
        eastMoneyClient.financials.put("600401", goodFinancial("600401"));
        eastMoneyClient.intraday.put("600401", confirmedTail("600401"));

        ShortTermReport report = service.report(3, 50, 12, null, null, null, null, null, null, null);

        assertThat(report.marketSentiment().phase()).isEqualTo("结构性行情");
        ShortTermCandidate candidate = find(report, "600401");
        assertThat(candidate.action()).isNotEqualTo("MARKET_RISK_WAIT");
        assertThat(candidate.score().marketHeatScore()).isGreaterThan(new BigDecimal("70"));
        assertThat(candidate.todayAdvice().action()).isIn("ADD", "LIGHT_TRIAL", "NEXT_WATCH", "WAIT");
        assertThat(report.marketSentiment().explanation()).contains("热点簇");
    }

    @Test
    void shouldRankRightSideStructureAheadOfWaitingShapeWhenMarketRiskOff() {
        List<EastMoneyQuote> quotes = new ArrayList<>();
        quotes.add(quote("600211", "右侧甲", "10.62", "1.10", "55", "5.0", "90000000"));
        quotes.add(quote("600212", "右侧乙", "10.62", "1.20", "58", "5.2", "90000000"));
        quotes.add(quote("600213", "右侧丙", "10.62", "1.30", "60", "5.5", "90000000"));
        quotes.add(quoteWithIndustry("600399", "高分未右侧", "机器人", "9.60", "0.80", "18", "1.6", "3600000000"));
        for (int index = 0; index < 8; index++) {
            quotes.add(quote("6007" + index, "下跌样本" + index, "10.00", "-1.50", "18", "1.6", "300000000"));
        }
        eastMoneyClient.quotes = quotes;
        for (String symbol : List.of("600211", "600212", "600213")) {
            eastMoneyClient.klines.put(symbol, rightEarlyKLines(symbol, "10.62", "90000"));
        }
        eastMoneyClient.klines.put("600399", belowMa20KLines("600399"));
        eastMoneyClient.financials.put("600399", goodFinancial("600399"));

        ShortTermReport report = service.report(3, 100, 12, null, null, null, null, null, null, null);

        assertThat(report.marketSentiment().phase()).isEqualTo("冰点/混沌");
        assertThat(report.candidates()).hasSize(3);
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600211", "600212", "600213");
        assertThat(report.candidates()).allSatisfy(candidate ->
                assertThat(candidate.technical().rightSideSignal()).contains("右侧"));
    }

    @Test
    void shouldTreatShrinkingRiseAsConstructiveVolumeSignal() {
        eastMoneyClient.quotes = List.of(
                quote("600214", "缩量上涨", "10.62", "1.10", "28", "2.6", "280000000")
        );
        eastMoneyClient.klines.put("600214", rightEarlyKLines("600214", "10.62", "80000"));
        eastMoneyClient.financials.put("600214", goodFinancial("600214"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600214");
        assertThat(candidate.technical().volumeRatio20()).isLessThan(BigDecimal.ONE);
        assertThat(candidate.score().volumeScore()).isGreaterThanOrEqualTo(new BigDecimal("72"));
        assertThat(candidate.strengths()).anySatisfy(strength -> assertThat(strength).contains("缩量上涨", "惜售"));
        assertThat(candidate.evidence()).extracting(ShortTermEvidence::summary)
                .anySatisfy(summary -> assertThat(summary).contains("缩量上涨"));
    }

    @Test
    void shouldNotLetTailCreateAnEntryWhenCrowdedMarketDowngradesTheDailySignal() {
        List<EastMoneyQuote> quotes = new ArrayList<>();
        quotes.add(quote("600301", "右侧候选", "10.62", "1.60", "18", "1.6", "600000000"));
        for (int index = 0; index < 80; index++) {
            quotes.add(quote(String.format("60%04d", 400 + index), "涨停样本" + index,
                    "10.00", "9.60", "30", "3", "300000000"));
        }
        eastMoneyClient.quotes = quotes;
        eastMoneyClient.klines.put("600301", confirmedRightEarlyKLines("600301", "10.62", "180000"));
        eastMoneyClient.financials.put("600301", goodFinancial("600301"));
        eastMoneyClient.intraday.put("600301", confirmedTail("600301"));

        ShortTermReport report = service.report(5, 100, 10, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600301");
        assertThat(report.marketSentiment().phase()).isEqualTo("高潮");
        assertThat(candidate.action()).isEqualTo("WATCH_RIGHT_SIDE");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
    }

    @Test
    void shouldLabelClosedMarketQuoteAsSnapshotInsteadOfRealtimeEvidence() {
        Clock closedClock = Clock.fixed(Instant.parse("2026-07-11T03:00:00Z"), SHANGHAI);
        TradingClockService closedTradingClock = new TradingClockService(closedClock);
        ShortTermService closedService = new ShortTermService(
                eastMoneyClient,
                new EvidenceCompletenessService(),
                closedTradingClock,
                new QuoteFreshnessService(closedTradingClock, closedClock)
        );
        eastMoneyClient.quotes = List.of(
                quote("600302", "休市样本", "10.62", "1.60", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600302", rightEarlyKLines("600302", "10.62", "180000"));
        eastMoneyClient.financials.put("600302", goodFinancial("600302"));

        ShortTermCandidate candidate = find(
                closedService.report(3, 50, 10, null, null, null, null, null, null, null),
                "600302"
        );

        assertThat(candidate.quoteFreshness().status()).isEqualTo("MARKET_CLOSED_SNAPSHOT");
        assertThat(candidate.evidenceCompleteness().presentEvidence()).doesNotContain("实时行情");
        assertThat(candidate.evidenceCompleteness().missingEvidence()).contains("实时行情");
    }

    @Test
    void shouldRejectPreviousTradingDayIntradayAsTodayTailConfirmation() {
        eastMoneyClient.quotes = List.of(
                quote("600303", "跨日样本", "10.62", "1.60", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600303", rightEarlyKLines("600303", "10.62", "180000"));
        eastMoneyClient.financials.put("600303", goodFinancial("600303"));
        eastMoneyClient.intraday.put("600303", confirmedTail("600303", LocalDate.parse("2026-07-06")));

        ShortTermCandidate candidate = find(
                service.report(3, 50, 10, null, null, null, null, null, null, null),
                "600303"
        );

        assertThat(candidate.tailSignal().status()).isEqualTo("STALE_TRADING_DAY");
        assertThat(candidate.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
    }

    @Test
    void shouldBlockExecutionAndDiscloseWhenMarketCoverageIsIncomplete() {
        eastMoneyClient.snapshotComplete = false;
        eastMoneyClient.snapshotExpectedCount = 100;
        eastMoneyClient.quotes = List.of(
                quote("600304", "覆盖不足样本", "10.62", "1.60", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600304", rightEarlyKLines("600304", "10.62", "180000"));
        eastMoneyClient.financials.put("600304", goodFinancial("600304"));
        eastMoneyClient.intraday.put("600304", confirmedTail("600304"));

        ShortTermReport report = service.report(3, 100, 10, null, null, null, null, null, null, null);
        ShortTermCandidate candidate = find(report, "600304");

        assertThat(report.quoteNote()).contains("覆盖不足", "1/100");
        assertThat(report.marketSentiment().phase()).isEqualTo("行情覆盖不足");
        assertThat(candidate.action()).isEqualTo("MARKET_RISK_WAIT");
        assertThat(candidate.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
    }

    @Test
    void shouldKeepRightSideEarlyStockForResearchWithoutPostWindowUpgrade() {
        eastMoneyClient.unstableIndustrySymbols = Set.of("300059");
        eastMoneyClient.quotes = List.of(
                quote("600001", "右侧股份", "10.62", "1.60", "18.00", "1.60", "180000000"),
                quote("600002", "急拉股份", "12.90", "6.60", "18.00", "1.80", "260000000"),
                quote("600004", "低流动性", "10.62", "1.20", "16.00", "1.40", "50000000"),
                quoteWithIndustry("600003", "样本证券", "证券", "11.30", "1.20", "14.00", "1.30", "200000000"),
                quote("300059", "东方财富", "18.20", "1.10", "26.00", "3.20", "300000000"),
                quote("000002", "*ST样本", "2.10", "1.00", "8.00", "0.90", "100000000")
        );
        eastMoneyClient.klines.put("600001", confirmedRightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.klines.put("600002", rightEarlyKLines("600002", "12.90", "420000"));
        eastMoneyClient.klines.put("600004", rightEarlyKLines("600004", "10.62", "160000"));
        eastMoneyClient.klines.put("600003", rightEarlyKLines("600003", "11.30", "210000"));
        eastMoneyClient.klines.put("300059", rightEarlyKLines("300059", "18.20", "300000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.financials.put("600002", goodFinancial("600002"));
        eastMoneyClient.financials.put("600004", goodFinancial("600004"));
        eastMoneyClient.financials.put("600003", goodFinancial("600003"));
        eastMoneyClient.financials.put("300059", goodFinancial("300059"));
        eastMoneyClient.intraday.put("600001", confirmedTail("600001"));

        ShortTermReport report = service.report(5, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600001");
        assertThat(report.scope()).contains("短线右侧");
        assertThat(report.ruleSet().scanLimit()).isEqualTo(100);
        assertThat(candidate.phaseLabel()).isEqualTo("右侧早期");
        assertThat(candidate.action()).isEqualTo("RIGHT_EARLY_ADD");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(candidate.todayAdvice().actionLabel()).isEqualTo("观望");
        assertThat(candidate.todayAdvice().summary()).contains("研究", "不可新建");
        assertThat(candidate.tailSignal().status()).isEqualTo("CONFIRMED");
        assertThat(candidate.technical().rightSideSignal()).contains("右侧早期");
        assertThat(candidate.financial().qualityScore()).isGreaterThanOrEqualTo(new BigDecimal("58"));
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("000002");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("600004");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("600003");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("300059");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::symbol).contains("600004", "600003", "300059");
        assertThat(report.exclusions()).filteredOn(exclusion -> "600004".equals(exclusion.symbol()))
                .singleElement()
                .satisfies(exclusion -> {
                    assertThat(exclusion.category()).isEqualTo("LOW_LIQUIDITY");
                    assertThat(exclusion.reason()).contains("流动性");
                });
        assertThat(report.exclusions()).filteredOn(exclusion -> "300059".equals(exclusion.symbol()))
                .singleElement()
                .satisfies(exclusion -> {
                    assertThat(exclusion.category()).isEqualTo("UNSTABLE_INDUSTRY");
                    assertThat(exclusion.reason()).contains("证券/券商");
                });
    }

    @Test
    void shouldAvoidChasingAfterSharpRightSideMove() {
        eastMoneyClient.quotes = List.of(
                quote("600002", "急拉股份", "12.90", "6.60", "18.00", "1.80", "260000000")
        );
        eastMoneyClient.klines.put("600002", rightEarlyKLines("600002", "12.90", "420000"));
        eastMoneyClient.financials.put("600002", goodFinancial("600002"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600002");
        assertThat(candidate.action()).isEqualTo("WAIT_PULLBACK");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT_PULLBACK");
        assertThat(candidate.todayAdvice().actionLabel()).isEqualTo("等回踩");
        assertThat(candidate.risks()).anySatisfy(risk -> assertThat(risk).contains("单日涨幅"));
    }

    @Test
    void shouldWaitUntilActionableTailWindow() {
        eastMoneyClient.quotes = List.of(
                quote("600001", "右侧股份", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.intraday.put("600001", tailBeforeClosingAuction("600001"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600001");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(candidate.tailSignal().status()).isEqualTo("NOT_READY");
        assertThat(candidate.tailSignal().statusLabel()).isEqualTo("等14:45");
        assertThat(candidate.tailSignal().reasons()).anySatisfy(reason -> assertThat(reason).contains("14:45"));
    }

    @Test
    void shouldTreatPostCloseFixedPriceAsSeparateFromRegularTailBuyPoint() {
        eastMoneyClient.quotes = List.of(
                quote("600001", "右侧股份", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.intraday.put("600001", postCloseOnly("600001"));
        Clock postCloseClock = Clock.fixed(Instant.parse("2026-07-07T07:20:00Z"), SHANGHAI);
        TradingClockService postCloseTradingClock = new TradingClockService(postCloseClock);
        ShortTermService postCloseService = new ShortTermService(
                eastMoneyClient,
                new EvidenceCompletenessService(),
                postCloseTradingClock,
                new QuoteFreshnessService(postCloseTradingClock, postCloseClock)
        );

        ShortTermReport report = postCloseService.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600001");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(candidate.tailSignal().status()).isEqualTo("POST_CLOSE_FIXED_PRICE");
        assertThat(candidate.tailSignal().statusLabel()).isEqualTo("盘后固定价");
        assertThat(candidate.tailSignal().riskControls()).anySatisfy(control -> assertThat(control).contains("不能和普通尾盘买点混用"));
        assertThat(candidate.evidenceCompleteness().allowsBuy()).isFalse();
    }

    @Test
    void shouldHideLongSidewaysStockWithoutEffectiveBreakout() {
        eastMoneyClient.quotes = List.of(
                quote("600005", "横盘股份", "10.08", "0.20", "12.00", "1.20", "180000000")
        );
        eastMoneyClient.klines.put("600005", longSidewaysKLines("600005"));
        eastMoneyClient.financials.put("600005", goodFinancial("600005"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("600005");
    }

    @Test
    void shouldKeepHotDirectionCandidateWhenValuationIsStretchedButNotExtreme() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry("600010", "风口机器人", "机器人", "10.62", "1.60", "95.00", "10.00", "600000000"),
                quote("600011", "低估设备", "10.62", "1.20", "16.00", "1.40", "120000000")
        );
        eastMoneyClient.klines.put("600010", rightEarlyKLines("600010", "10.62", "230000"));
        eastMoneyClient.klines.put("600011", rightEarlyKLines("600011", "10.62", "160000"));
        eastMoneyClient.financials.put("600010", goodFinancial("600010"));
        eastMoneyClient.financials.put("600011", goodFinancial("600011"));

        ShortTermReport report = service.report(5, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600010");
        assertThat(report.ruleSet().maxPe()).isEqualByComparingTo("100");
        assertThat(report.ruleSet().maxPb()).isEqualByComparingTo("15.0");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).contains("600010");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::symbol).doesNotContain("600010");
        assertThat(candidate.score().marketHeatScore()).isGreaterThan(new BigDecimal("60"));
        assertThat(candidate.score().valuationScore()).isGreaterThan(new BigDecimal("65"));
    }

    @Test
    void shouldKeepHotRightSideCandidateEvenWhenPeAndPbExceedOldExtremeGate() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry(
                        "600020",
                        "高估值机器人",
                        "机器人",
                        "10.62",
                        "1.60",
                        "300.00",
                        "45.00",
                        "900000000"
                )
        );
        eastMoneyClient.klines.put("600020", rightEarlyKLines("600020", "10.62", "230000"));
        eastMoneyClient.financials.put("600020", goodFinancial("600020"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600020");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::symbol).doesNotContain("600020");
        assertThat(candidate.valuationContext().state()).isEqualTo(ValuationContextState.STRETCHED);
        assertThat(candidate.risks()).anySatisfy(item -> assertThat(item).contains("参考"));
    }

    @Test
    void shouldExposeApprovedSoftValuationWeights() {
        eastMoneyClient.quotes = List.of(
                quote("600021", "权重样本", "10.62", "1.60", "18.00", "1.60", "900000000")
        );
        eastMoneyClient.klines.put("600021", rightEarlyKLines("600021", "10.62", "230000"));
        eastMoneyClient.financials.put("600021", goodFinancial("600021"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        assertThat(report.weightProfile().preliminaryTotal()).isEqualByComparingTo("1.00");
        assertThat(report.weightProfile().finalTotal()).isEqualByComparingTo("1.00");
        assertThat(report.weightProfile().preliminaryValuation()).isEqualByComparingTo("0.10");
        assertThat(report.weightProfile().finalValuation()).isEqualByComparingTo("0.05");
    }

    @Test
    void shouldNotGiveMuyuanSymbolSpecificHotDirectionBonus() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry(
                        "002714",
                        "中性样本",
                        "未知行业",
                        "10.62",
                        "1.60",
                        "18.00",
                        "1.60",
                        "900000000"
                )
        );
        eastMoneyClient.klines.put("002714", rightEarlyKLines("002714", "10.62", "230000"));
        eastMoneyClient.financials.put("002714", goodFinancial("002714"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "002714");
        assertThat(candidate.score().marketHeatScore()).isEqualByComparingTo("60");
        assertThat(report.hotDirections()).isEmpty();
    }

    @Test
    void shouldNotGiveLightTrialFromWatchedClosingAuctionSupport() {
        eastMoneyClient.quotes = List.of(
                quote("600012", "观察试错", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600012", confirmedRightEarlyKLines("600012", "10.62", "180000"));
        eastMoneyClient.financials.put("600012", goodFinancial("600012"));
        eastMoneyClient.intraday.put("600012", watchedTail("600012"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600012");
        assertThat(candidate.action()).isEqualTo("RIGHT_EARLY_ADD");
        assertThat(candidate.tailSignal().status()).isEqualTo("WATCH");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(candidate.todayAdvice().actionLabel()).isEqualTo("观望");
        assertThat(candidate.todayAdvice().summary()).contains("研究", "不可新建");
    }

    @Test
    void shouldRankEstablishedRightSideAheadOfApproachingCrossBelowMa20() {
        eastMoneyClient.quotes = List.of(
                quote("600017", "弱势临界", "10.30", "1.20", "18", "1.6", "600000000"),
                quote("600018", "多头延续", "10.70", "1.00", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600017", approachingBelowMa20KLines("600017"));
        eastMoneyClient.klines.put("600018", establishedRightSideKLines("600018"));
        eastMoneyClient.quotes.forEach(quote -> eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol())));

        ShortTermReport report = service.report(2, 100, 10, null, null, null, null, null, null, null);

        assertThat(find(report, "600017").technical().goldenCross().state()).isEqualTo("APPROACHING");
        assertThat(find(report, "600017").technical().rightSideSignal()).isEqualTo("尚未右侧");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600018", "600017");
    }

    @Test
    void shouldNotPromoteApproachingCrossThatIsAlreadyFarFromMa20() {
        eastMoneyClient.quotes = List.of(
                quote("600019", "过远临界", "12.00", "1.20", "18", "1.6", "600000000"),
                quote("600020", "合理延续", "10.70", "1.00", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600019", approachingGoldenCrossKLines("600019"));
        eastMoneyClient.klines.put("600020", establishedRightSideKLines("600020"));
        eastMoneyClient.quotes.forEach(quote -> eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol())));

        ShortTermReport report = service.report(2, 100, 10, null, null, null, null, null, null, null);

        ShortTermCandidate approaching = find(report, "600019");
        assertThat(approaching.technical().goldenCross().state()).isEqualTo("APPROACHING");
        assertThat(approaching.technical().rightSideSignal()).isIn("右侧雏形", "右侧已拉开");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600020", "600019");
    }

    @Test
    void shouldReportAllSuccessfullyReviewedKLinesBeforeCandidateTruncation() {
        List<EastMoneyQuote> quotes = IntStream.range(0, 35)
                .mapToObj(index -> quote(String.format("60%04d", 700 + index), "复核样本" + index,
                        "10.62", "1.20", "18", "1.6", "600000000"))
                .toList();
        eastMoneyClient.quotes = quotes;
        quotes.forEach(quote -> {
            eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "180000"));
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        });

        ShortTermReport report = service.report(3, 100, 35, null, null, null, null, null, null, null);

        assertThat(report.reviewedCount()).isEqualTo(35);
        assertThat(report.klineReviewedCount()).isEqualTo(35);
        assertThat(report.quoteNote()).contains("金叉K线复核 35/35");
    }

    @Test
    void shouldNotLetTailSupportCreateAnEntryAfterRecentGoldenCrossWindow() {
        eastMoneyClient.quotes = List.of(
                quote("600016", "延续观察", "10.70", "1.00", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600016", rightEarlyKLines("600016", "10.70", "108000"));
        eastMoneyClient.financials.put("600016", goodFinancial("600016"));
        eastMoneyClient.intraday.put("600016", watchedTail("600016"));

        ShortTermCandidate candidate = find(
                service.report(3, 100, 5, null, null, null, null, null, null, null),
                "600016"
        );

        assertThat(candidate.technical().goldenCross().state()).isEqualTo("ESTABLISHED");
        assertThat(candidate.tailSignal().status()).isEqualTo("WATCH");
        assertThat(candidate.action()).isEqualTo("WATCH_RIGHT_SIDE");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
    }

    @Test
    void shouldKeepStrongDailySignalAsNextDayWatchWhenTailTurnsWeak() {
        eastMoneyClient.quotes = List.of(
                quote("600013", "次日关注", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600013", confirmedRightEarlyKLines("600013", "10.62", "180000"));
        eastMoneyClient.financials.put("600013", goodFinancial("600013"));
        eastMoneyClient.intraday.put("600013", weakTail("600013"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600013");
        assertThat(candidate.action()).isEqualTo("RIGHT_EARLY_ADD");
        assertThat(candidate.tailSignal().status()).isEqualTo("WEAK");
        assertThat(candidate.todayAdvice().action()).isEqualTo("NEXT_WATCH");
        assertThat(candidate.todayAdvice().actionLabel()).isEqualTo("次日关注");
        assertThat(candidate.todayAdvice().summary()).contains("次日");
    }

    @Test
    void shouldKeepLowerTailThresholdForResearchWithoutPostWindowUpgrade() {
        eastMoneyClient.quotes = List.of(
                quote("600014", "大额成交", "10.62", "1.60", "18.00", "1.60", "3600000000")
        );
        eastMoneyClient.klines.put("600014", confirmedRightEarlyKLines("600014", "10.62", "180000"));
        eastMoneyClient.financials.put("600014", goodFinancial("600014"));
        eastMoneyClient.intraday.put("600014", largeTurnoverConfirmedTail("600014"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600014");
        assertThat(candidate.tailSignal().tailAmountRatioPercent()).isLessThan(new BigDecimal("6.00"));
        assertThat(candidate.tailSignal().status()).isEqualTo("CONFIRMED");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(candidate.todayAdvice().summary()).contains("研究", "不可新建");
    }

    @Test
    void shouldFailInsteadOfReturningEmptyCandidatesWhenRealtimeQuotesUnavailable() {
        eastMoneyClient.quoteFailure = new IllegalStateException("实时行情超时");

        assertThatThrownBy(() -> service.report(3, 100, 5, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("短线右侧实时行情加载失败")
                .hasMessageContaining("不返回空候选降级");
    }

    @Test
    void shouldScoreDecimalFinancialRatiosUsingDeclaredUnits() {
        eastMoneyClient.quotes = List.of(
                quote("600021", "比例样本", "10.50", "1.20", "35", "3.2", "600000000")
        );
        eastMoneyClient.klines.put("600021", rightEarlyKLines("600021", "10.50", "180000"));
        eastMoneyClient.financials.put("600021", goodFinancial("600021"));

        ShortTermReport report = service.report(3, 50, 10, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600021");
        assertThat(candidate.financial().roe()).isEqualByComparingTo("0.125");
        assertThat(candidate.financial().qualityScore()).isEqualByComparingTo("100");
        assertThat(candidate.evidence()).extracting(ShortTermEvidence::summary)
                .anySatisfy(summary -> assertThat(summary).contains("ROE 12.5%"));
    }

    private ShortTermCandidate find(ShortTermReport report, String symbol) {
        return report.candidates().stream()
                .filter(candidate -> symbol.equals(candidate.symbol()))
                .findFirst()
                .orElseThrow();
    }

    private ShortTermCandidate copyWithExecutionState(
            ShortTermCandidate candidate,
            TradingAdvice advice,
            QuoteFreshnessSnapshot freshness,
            EvidenceCompleteness completeness
    ) {
        return new ShortTermCandidate(
                candidate.rank(),
                candidate.symbol(),
                candidate.name(),
                candidate.market(),
                candidate.industry(),
                candidate.latestPrice(),
                candidate.changePercent(),
                candidate.peTtm(),
                candidate.pbRatio(),
                candidate.amount(),
                freshness,
                candidate.valuationContext(),
                candidate.phase(),
                candidate.phaseLabel(),
                candidate.action(),
                candidate.actionLabel(),
                candidate.reason(),
                advice,
                candidate.tailSignal(),
                candidate.score(),
                candidate.technical(),
                candidate.financial(),
                candidate.buyZoneLow(),
                candidate.buyZoneHigh(),
                candidate.stopPrice(),
                candidate.strengths(),
                candidate.risks(),
                candidate.entryRules(),
                candidate.exitRules(),
                completeness,
                candidate.evidence(),
                candidate.tradePlan()
        );
    }

    private OvernightRuleSet overnightRules() {
        return new OvernightRuleSet(
                LocalTime.of(14, 45),
                LocalTime.of(14, 56, 59),
                LocalTime.of(14, 50),
                2,
                new BigDecimal("0.3333"),
                new BigDecimal("0.50"),
                new BigDecimal("2.5"),
                new BigDecimal("4.0"),
                new BigDecimal("4.5"),
                new BigDecimal("7.0"),
                new BigDecimal("2.5"),
                new BigDecimal("4.5"),
                new BigDecimal("2.0")
        );
    }

    private List<EastMoneyKLine> rightEarlyKLines(String symbol, String finalClose, String finalVolume) {
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-01-01");
        for (int index = 0; index < 35; index++) {
            BigDecimal close = new BigDecimal("12.20").subtract(new BigDecimal("0.035").multiply(BigDecimal.valueOf(index)));
            rows.add(kline(symbol, start.plusDays(index), close, "95000"));
        }
        for (int index = 35; index < 72; index++) {
            BigDecimal close = new BigDecimal("10.95").subtract(new BigDecimal("0.035").multiply(BigDecimal.valueOf(index - 35)));
            rows.add(kline(symbol, start.plusDays(index), close, "98000"));
        }
        for (int index = 72; index < 103; index++) {
            BigDecimal close = new BigDecimal("9.60").add(new BigDecimal("0.018").multiply(BigDecimal.valueOf(index - 72)));
            rows.add(kline(symbol, start.plusDays(index), close, "100000"));
        }
        for (int index = 103; index < 119; index++) {
            BigDecimal close = new BigDecimal("10.05").add(new BigDecimal("0.030").multiply(BigDecimal.valueOf(index - 103)));
            rows.add(kline(symbol, start.plusDays(index), close, "105000"));
        }
        rows.add(kline(symbol, start.plusDays(119), new BigDecimal(finalClose), finalVolume));
        return rows;
    }

    private List<EastMoneyKLine> recentGoldenCrossKLines(String symbol, int barsAfterCross) {
        List<BigDecimal> closes = new ArrayList<>(Collections.nCopies(20, new BigDecimal("10.00")));
        closes.add(new BigDecimal("10.50"));
        for (int index = 0; index < barsAfterCross; index++) {
            closes.add(new BigDecimal("10.55").add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(index))));
        }
        return klineRows(symbol, closes);
    }

    private List<EastMoneyKLine> confirmedRightEarlyKLines(String symbol, String finalClose, String finalVolume) {
        List<EastMoneyKLine> rows = new ArrayList<>(rightEarlyKLines(symbol, finalClose, finalVolume));
        List<BigDecimal> recentCloses = List.of(
                new BigDecimal("10.00"), new BigDecimal("9.90"), new BigDecimal("9.80"),
                new BigDecimal("9.90"), new BigDecimal("10.00"), new BigDecimal("10.20"),
                new BigDecimal("10.40"), new BigDecimal(finalClose)
        );
        int start = rows.size() - recentCloses.size();
        for (int index = 0; index < recentCloses.size(); index++) {
            String volume = index == recentCloses.size() - 1 ? finalVolume : "105000";
            rows.set(start + index, kline(symbol, rows.get(start + index).tradeDate(), recentCloses.get(index), volume));
        }
        return rows;
    }

    private List<EastMoneyKLine> approachingGoldenCrossKLines(String symbol) {
        List<BigDecimal> closes = new ArrayList<>(Collections.nCopies(15, new BigDecimal("10.50")));
        closes.addAll(Collections.nCopies(5, new BigDecimal("10.00")));
        closes.addAll(List.of(new BigDecimal("10.10"), new BigDecimal("10.20"), new BigDecimal("10.30")));
        return klineRows(symbol, closes);
    }

    private List<EastMoneyKLine> approachingBelowMa20KLines(String symbol) {
        List<BigDecimal> closes = new ArrayList<>(Collections.nCopies(10, new BigDecimal("12.00")));
        closes.addAll(Collections.nCopies(3, new BigDecimal("10.00")));
        closes.addAll(Collections.nCopies(2, new BigDecimal("10.50")));
        closes.addAll(Collections.nCopies(5, new BigDecimal("10.00")));
        closes.addAll(List.of(new BigDecimal("10.10"), new BigDecimal("10.20"), new BigDecimal("10.30")));
        return klineRows(symbol, closes);
    }

    private List<EastMoneyKLine> establishedRightSideKLines(String symbol) {
        return recentGoldenCrossKLines(symbol, 4);
    }

    private List<EastMoneyKLine> unfinishedFormingGoldenCrossKLines(String symbol) {
        List<EastMoneyKLine> historical = recentGoldenCrossKLines(symbol, 0);
        LocalDate start = LocalDate.parse("2026-06-17");
        return IntStream.range(0, historical.size())
                .mapToObj(index -> kline(symbol, start.plusDays(index), historical.get(index).close(), "180000"))
                .toList();
    }

    private List<EastMoneyKLine> unfinishedFormingGoldenCrossKLinesWithLowVolume(String symbol) {
        List<EastMoneyKLine> rows = new ArrayList<>(unfinishedFormingGoldenCrossKLines(symbol));
        EastMoneyKLine latest = rows.get(rows.size() - 1);
        rows.set(rows.size() - 1, kline(symbol, latest.tradeDate(), latest.close(), "30000"));
        return rows;
    }

    private List<EastMoneyKLine> klineRows(String symbol, List<BigDecimal> closes) {
        LocalDate start = LocalDate.parse("2026-06-01");
        return IntStream.range(0, closes.size())
                .mapToObj(index -> kline(symbol, start.plusDays(index), closes.get(index), "180000"))
                .toList();
    }

    private List<EastMoneyKLine> longSidewaysKLines(String symbol) {
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-01-01");
        for (int index = 0; index < 100; index++) {
            BigDecimal close = new BigDecimal("10.00").add(new BigDecimal(index % 2 == 0 ? "0.08" : "-0.06"));
            rows.add(kline(symbol, start.plusDays(index), close, "110000"));
        }
        return rows;
    }

    private List<EastMoneyKLine> belowMa20KLines(String symbol) {
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-01-01");
        for (int index = 0; index < 80; index++) {
            BigDecimal close = new BigDecimal("12.00").subtract(new BigDecimal("0.030").multiply(BigDecimal.valueOf(index)));
            rows.add(kline(symbol, start.plusDays(index), close, "260000"));
        }
        rows.add(kline(symbol, start.plusDays(80), new BigDecimal("9.60"), "360000"));
        return rows;
    }

    private EastMoneyKLine kline(String symbol, LocalDate date, BigDecimal close, String volume) {
        return new EastMoneyKLine(
                symbol,
                date,
                close.subtract(new BigDecimal("0.05")),
                close,
                close.add(new BigDecimal("0.12")),
                close.subtract(new BigDecimal("0.14")),
                new BigDecimal(volume),
                null
        );
    }

    private EastMoneyQuote quote(String symbol, String name, String price, String changePercent, String pe, String pb, String amount) {
        return quoteWithIndustry(symbol, name, "通用设备", price, changePercent, pe, pb, amount);
    }

    private EastMoneyQuote quoteWithIndustry(String symbol, String name, String industry, String price, String changePercent, String pe, String pb, String amount) {
        return new EastMoneyQuote(
                symbol,
                name,
                "上交所",
                industry,
                new BigDecimal(price),
                new BigDecimal(changePercent),
                BigDecimal.ONE,
                new BigDecimal("100000"),
                new BigDecimal(amount),
                new BigDecimal(pe),
                new BigDecimal(pb),
                new BigDecimal(pe),
                "测试行情",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-07-07T06:59:00Z"),
                LocalDate.parse("2026-07-07"),
                Instant.parse("2026-07-07T06:58:00Z")
        );
    }

    private List<EastMoneyAnnualIndicator> goodFinancial(String symbol) {
        return List.of(
                new EastMoneyAnnualIndicator(symbol, "样本", "2025-12-31", "年报", new BigDecimal("0.1250"),
                        new BigDecimal("1.20"), new BigDecimal("0.2800"), new BigDecimal("0.0600"), new BigDecimal("0.1200"), BigDecimal.ONE, new BigDecimal("5.00")),
                new EastMoneyAnnualIndicator(symbol, "样本", "2024-12-31", "年报", new BigDecimal("0.1080"),
                        new BigDecimal("1.05"), new BigDecimal("0.2600"), new BigDecimal("0.0300"), new BigDecimal("0.0800"), BigDecimal.ONE, new BigDecimal("4.60")),
                new EastMoneyAnnualIndicator(symbol, "样本", "2023-12-31", "年报", new BigDecimal("0.0960"),
                        new BigDecimal("0.90"), new BigDecimal("0.2400"), new BigDecimal("0.0200"), new BigDecimal("0.0500"), BigDecimal.ONE, new BigDecimal("4.20"))
        );
    }

    private List<EastMoneyAnnualIndicator> acceptableFinancial(String symbol) {
        return List.of(
                new EastMoneyAnnualIndicator(symbol, "样本", "2025-12-31", "年报", new BigDecimal("0.0600"),
                        new BigDecimal("0.60"), new BigDecimal("0.1200"), new BigDecimal("-0.0200"),
                        new BigDecimal("0.0300"), BigDecimal.ONE, new BigDecimal("3.00"))
        );
    }

    private List<EastMoneyIntradayPoint> confirmedTail(String symbol) {
        return confirmedTail(symbol, LocalDate.parse("2026-07-07"));
    }

    private List<EastMoneyIntradayPoint> confirmedTail(String symbol, LocalDate tradeDate) {
        List<EastMoneyIntradayPoint> points = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            BigDecimal close = new BigDecimal("10.45").add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(index)));
            points.add(new EastMoneyIntradayPoint(
                    symbol,
                    tradeDate.atTime(14, 45).plusMinutes(index * 3L),
                    close.subtract(new BigDecimal("0.02")),
                    close,
                    close.add(new BigDecimal("0.03")),
                    close.subtract(new BigDecimal("0.04")),
                    new BigDecimal("30000").add(BigDecimal.valueOf(index).multiply(new BigDecimal("1000"))),
                    new BigDecimal("32000000").add(BigDecimal.valueOf(index).multiply(new BigDecimal("1500000"))),
                    new BigDecimal("10.45").add(BigDecimal.valueOf(index).multiply(new BigDecimal("0.01")))
            ));
        }
        return points;
    }

    private List<EastMoneyIntradayPoint> watchedTail(String symbol) {
        return List.of(
                intraday(symbol, "2026-07-07T14:45", "10.48", "10.50", "10.52", "10.47", "26000", "26000000", "10.48"),
                intraday(symbol, "2026-07-07T14:48", "10.50", "10.51", "10.53", "10.49", "24000", "25000000", "10.49"),
                intraday(symbol, "2026-07-07T14:52", "10.51", "10.51", "10.52", "10.50", "23000", "24000000", "10.49"),
                intraday(symbol, "2026-07-07T14:56", "10.51", "10.51", "10.52", "10.50", "22000", "23000000", "10.49")
        );
    }

    private List<EastMoneyIntradayPoint> weakTail(String symbol) {
        return List.of(
                intraday(symbol, "2026-07-07T14:45", "10.55", "10.55", "10.57", "10.53", "28000", "29000000", "10.50"),
                intraday(symbol, "2026-07-07T14:48", "10.55", "10.49", "10.55", "10.48", "26000", "27000000", "10.50"),
                intraday(symbol, "2026-07-07T14:52", "10.49", "10.45", "10.50", "10.44", "25000", "26000000", "10.50"),
                intraday(symbol, "2026-07-07T14:56", "10.45", "10.42", "10.46", "10.41", "24000", "25000000", "10.50")
        );
    }

    private List<EastMoneyIntradayPoint> largeTurnoverConfirmedTail(String symbol) {
        List<EastMoneyIntradayPoint> points = new ArrayList<>();
        points.add(intraday(symbol, "2026-07-07T14:30", "10.32", "10.38", "10.40", "10.31", "2600000", "3000000000", "10.36"));
        points.add(intraday(symbol, "2026-07-07T14:45", "10.48", "10.50", "10.52", "10.47", "36000", "36000000", "10.48"));
        points.add(intraday(symbol, "2026-07-07T14:48", "10.50", "10.52", "10.53", "10.49", "36000", "37000000", "10.49"));
        points.add(intraday(symbol, "2026-07-07T14:52", "10.52", "10.54", "10.55", "10.51", "36000", "38000000", "10.50"));
        points.add(intraday(symbol, "2026-07-07T14:56", "10.54", "10.55", "10.56", "10.53", "36000", "39000000", "10.50"));
        return points;
    }

    private List<EastMoneyIntradayPoint> tailBeforeClosingAuction(String symbol) {
        return List.of(
                intraday(symbol, "2026-07-07T14:40", "10.34", "10.38", "10.39", "10.33", "14000", "14000000", "10.32"),
                intraday(symbol, "2026-07-07T14:42", "10.38", "10.42", "10.43", "10.37", "18000", "19000000", "10.36"),
                intraday(symbol, "2026-07-07T14:44", "10.42", "10.48", "10.49", "10.41", "22000", "24000000", "10.39")
        );
    }

    private List<EastMoneyIntradayPoint> postCloseOnly(String symbol) {
        return List.of(
                intraday(symbol, "2026-07-07T15:05", "10.40", "10.45", "10.46", "10.39", "20000", "20000000", "10.42"),
                intraday(symbol, "2026-07-07T15:15", "10.44", "10.48", "10.49", "10.43", "18000", "19000000", "10.43"),
                intraday(symbol, "2026-07-07T15:19", "10.50", "10.56", "10.58", "10.49", "30000", "32000000", "10.45")
        );
    }

    private EastMoneyIntradayPoint intraday(
            String symbol,
            String minute,
            String open,
            String close,
            String high,
            String low,
            String volume,
            String amount,
            String averagePrice
    ) {
        return new EastMoneyIntradayPoint(
                symbol,
                LocalDateTime.parse(minute),
                new BigDecimal(open),
                new BigDecimal(close),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(volume),
                new BigDecimal(amount),
                new BigDecimal(averagePrice)
        );
    }

    private static final class StubEastMoneyClient extends EastMoneyClient {

        private List<EastMoneyQuote> quotes = List.of();
        private RuntimeException quoteFailure;
        private int requestedQuoteLimit;
        private boolean snapshotComplete = true;
        private int snapshotExpectedCount;
        private String snapshotSource = "测试行情";
        private Instant snapshotFetchedAt = Instant.parse("2026-07-07T06:59:00Z");
        private Set<String> unstableIndustrySymbols = Set.of();
        private final List<String> requestedKlineSymbols = Collections.synchronizedList(new ArrayList<>());
        private final List<String> requestedFinancialSymbols = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, List<EastMoneyKLine>> klines = new HashMap<>();
        private final Map<String, List<EastMoneyAnnualIndicator>> financials = new HashMap<>();
        private final Map<String, List<EastMoneyIntradayPoint>> intraday = new HashMap<>();

        private StubEastMoneyClient() {
            super(null, null, null);
        }

        @Override
        public List<EastMoneyQuote> fetchAshareQuotes(int limit) {
            requestedQuoteLimit = limit;
            if (quoteFailure != null) {
                throw quoteFailure;
            }
            return quotes.stream().limit(limit).toList();
        }

        @Override
        public AshareQuoteSnapshot fetchAshareQuoteSnapshot(int limit) {
            requestedQuoteLimit = limit;
            if (quoteFailure != null) {
                throw quoteFailure;
            }
            int fetched = Math.min(quotes.size(), limit);
            int expected = snapshotExpectedCount > 0 ? snapshotExpectedCount : fetched;
            return new AshareQuoteSnapshot(
                    quotes.stream().limit(limit).toList(),
                    limit,
                    expected,
                    fetched,
                    Math.max(0, expected - fetched),
                    snapshotComplete,
                    snapshotSource,
                    snapshotFetchedAt
            );
        }

        @Override
        public List<EastMoneyKLine> fetchDailyKLines(String symbol, LocalDate begin, LocalDate end) {
            requestedKlineSymbols.add(symbol);
            return klines.getOrDefault(symbol, List.of());
        }

        @Override
        public List<EastMoneyQuote> fetchIndustryBoardConstituents(String industryName, int limit) {
            return quotes.stream()
                    .filter(quote -> unstableIndustrySymbols.contains(quote.symbol()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<EastMoneyAnnualIndicator> fetchAnnualIndicatorHistory(String symbol, int limit) {
            requestedFinancialSymbols.add(symbol);
            return financials.getOrDefault(symbol, List.of()).stream().limit(limit).toList();
        }

        @Override
        public List<EastMoneyIntradayPoint> fetchIntradayTrends(String symbol) {
            return intraday.getOrDefault(symbol, List.of());
        }
    }
}
