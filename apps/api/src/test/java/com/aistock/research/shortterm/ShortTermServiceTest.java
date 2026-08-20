package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyIndustryFundFlowSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.configuration.ShortTermChipSettings;
import com.aistock.research.quality.EvidenceCompleteness;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.shortterm.chip.ShortTermChipAnalysisService;
import com.aistock.research.shortterm.chip.ShortTermChipSnapshot;
import com.aistock.research.shortterm.schedule.ShortTermAutomationSettings;
import com.aistock.research.trading.QuoteFreshnessSnapshot;
import com.aistock.research.trading.QuoteFreshnessService;
import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.trading.TradingClockService;
import com.aistock.research.valuation.ValuationContextState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        ShortTermReport report = service.report(8, null, null, null,null, null, null, null, null, null);

        assertThat(eastMoneyClient.requestedQuoteLimit).isEqualTo(6000);
        assertThat(report.ruleSet().scanLimit()).isEqualTo(6000);
        assertThat(report.ruleSet().klineLimit()).isEqualTo(120);
        assertThat(report.ruleSet().maxEntryRisePercent()).isEqualByComparingTo("6.5");
        assertThat(report.ruleSet().minFinancialScore()).isEqualByComparingTo("55");
    }

    @Test
    void clampsManualVolumeRatioThresholdToTheApprovedRange() {
        ShortTermReport report = service.report(
                8, null, null, null, null, new BigDecimal("0.80"), null, null, null);

        assertThat(report.ruleSet().minVolumeRatio()).isEqualByComparingTo("1.00");
    }

    @Test
    void excludesQuotesAboveDefaultPricePerShareLimitBeforeQuantitativeReview() {
        eastMoneyClient.quotes = List.of(
                quote("600100", "百元高价股", "128.00", "1.20", "18.00", "3.00", "900000000"),
                quote("600101", "低价右侧股", "9.80", "1.20", "18.00", "1.60", "900000000")
        );
        eastMoneyClient.klines.put("600101", rightEarlyKLines("600101", "9.80", "180000"));
        eastMoneyClient.financials.put("600101", goodFinancial("600101"));

        ShortTermReport report = service.report(5, 100, 10, null, null, null, null, null, null);

        assertThat(report.ruleSet().maxPricePerShare()).isEqualByComparingTo("100");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("600100");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::symbol).contains("600100");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::category).contains("PRICE_ABOVE_LIMIT");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).contains("600101");
    }

    @Test
    void honorsCustomPricePerShareLimitFromManualRequest() {
        eastMoneyClient.quotes = List.of(
                quote("600100", "高价股", "128.00", "1.20", "18.00", "3.00", "900000000")
        );
        eastMoneyClient.klines.put("600100", rightEarlyKLines("600100", "128.00", "180000"));
        eastMoneyClient.financials.put("600100", goodFinancial("600100"));

        ShortTermReport report = service.report(
                5, 100, 10, null, new BigDecimal("150"), null, null, null, null);

        assertThat(report.ruleSet().maxPricePerShare()).isEqualByComparingTo("150");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::category)
                .doesNotContain("PRICE_ABOVE_LIMIT");
    }

    @Test
    void shouldExcludeChiNextByDefaultAndAllowItWhenPermissionSwitchIsOn() {
        eastMoneyClient.quotes = List.of(
                quote("300750", "宁德时代", "260.00", "0.80", "28.00", "5.20", "900000000"),
                quote("600036", "招商银行", "36.80", "0.20", "6.00", "0.85", "900000000")
        );
        eastMoneyClient.quotes.forEach(quote -> {
            eastMoneyClient.klines.put(
                    quote.symbol(),
                    confirmedRightEarlyKLines(quote.symbol(), quote.latestPrice().toPlainString(), "260000")
            );
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        });

        ShortTermReport defaultReport = service.report(new ShortTermScanRequest(
                5, 100, 10, null,null, null, null, null, false
        ));
        assertThat(defaultReport.ruleSet().allowChiNext()).isFalse();
        assertThat(defaultReport.reviewedSymbols()).doesNotContain("300750");
        assertThat(defaultReport.reviewedSymbols()).contains("600036");

        ShortTermReport allowedReport = service.report(new ShortTermScanRequest(
                5, 100, 10, null, new BigDecimal("9999"), null, null, null, null, false, true
        ));
        assertThat(allowedReport.ruleSet().allowChiNext()).isTrue();
        assertThat(allowedReport.reviewedSymbols()).contains("300750");
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
                new ShortTermScanRequest(3, 100, 10, null,null, null, null, null, null),
                Set.of("600795", "002128")
        );

        assertThat(report.reviewedSymbols()).containsExactlyInAnyOrder("600795", "002128");
        assertThat(eastMoneyClient.requestedKlineSymbols).containsExactlyInAnyOrder("600795", "002128");
        assertThat(eastMoneyClient.requestedKlineSymbols).doesNotContain("601918");
        assertThat(eastMoneyClient.requestedFinancialSymbols).containsExactly("002128");
        assertThat(eastMoneyClient.requestedFinancialSymbols).doesNotContain("601918");
        assertThat(report.exclusions()).anySatisfy(exclusion -> {
            assertThat(exclusion.symbol()).isEqualTo("600795");
            assertThat(exclusion.category()).isEqualTo("GOLDEN_CROSS_UNAVAILABLE");
        });
        assertThat(report.universeCount()).isEqualTo(3);
    }

    @Test
    void finalReportFetchesReportedFullMarketIndependentOfManualScanLimit() {
        eastMoneyClient.quotes = IntStream.range(0, 5000)
                .mapToObj(index -> quote(
                        String.format("%06d", 600000 + index),
                        "全市场样本" + index,
                        "10.62",
                        "1.20",
                        "18",
                        "1.60",
                        "600000000"
                ))
                .toList();
        eastMoneyClient.snapshotExpectedCount = 5000;
        for (String symbol : List.of("600000", "600001")) {
            eastMoneyClient.klines.put(symbol, rightEarlyKLines(symbol, "10.62", "180000"));
            eastMoneyClient.financials.put(symbol, goodFinancial(symbol));
        }

        ShortTermReport report = service.finalReport(
                new ShortTermScanRequest(3, 100, 10, null,null, null, null, null, null),
                Set.of("600000", "600001")
        );

        assertThat(eastMoneyClient.requestedQuoteLimit).isGreaterThan(5000);
        assertThat(report.coverage().expectedCount()).isEqualTo(5000);
        assertThat(report.coverage().fetchedCount()).isEqualTo(5000);
        assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("1.0000");
        assertThat(report.coverage().executionReliable()).isTrue();
        assertThat(report.reviewedSymbols()).containsExactlyInAnyOrder("600000", "600001");
    }

    @Test
    void manualSampleDoesNotPretendToHaveFullMarketCoverage() {
        eastMoneyClient.quotes = IntStream.range(0, 5000)
                .mapToObj(index -> quote(
                        String.format("%06d", 600000 + index),
                        "采样样本" + index,
                        "10.62",
                        "1.20",
                        "18",
                        "1.60",
                        "600000000"
                ))
                .toList();
        eastMoneyClient.snapshotExpectedCount = 5000;

        ShortTermReport report = service.report(
                new ShortTermScanRequest(3, 100, 10, null,null, null, null, null, null)
        );

        assertThat(eastMoneyClient.requestedQuoteLimit).isEqualTo(100);
        assertThat(report.coverage().expectedCount()).isEqualTo(5000);
        assertThat(report.coverage().fetchedCount()).isEqualTo(100);
        assertThat(report.coverage().executionReliable()).isFalse();
    }

    @Test
    void absentReportedUniverseNeverProducesReliableFinalCoverage() {
        eastMoneyClient.quotes = IntStream.range(0, 100)
                .mapToObj(index -> quote(
                        String.format("%06d", 600000 + index),
                        "未知总量" + index,
                        "10.62",
                        "1.20",
                        "18",
                        "1.60",
                        "600000000"
                ))
                .toList();
        eastMoneyClient.snapshotHasReportedTotal = false;
        eastMoneyClient.klines.put("600000", rightEarlyKLines("600000", "10.62", "180000"));
        eastMoneyClient.financials.put("600000", goodFinancial("600000"));

        ShortTermReport report = service.finalReport(
                new ShortTermScanRequest(3, 100, 10, null,null, null, null, null, null),
                Set.of("600000")
        );

        assertThat(report.coverage().executionReliable()).isFalse();
        assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("0");
    }

    @Test
    void incompleteFinalUniverseNeverBecomesReliableAtTheNinetyPercentThreshold() {
        eastMoneyClient.quotes = IntStream.range(0, 9)
                .mapToObj(index -> quote(
                        String.format("600%03d", index),
                        "缺页样本" + index,
                        "10.62",
                        "1.20",
                        "18",
                        "1.60",
                        "600000000"
                ))
                .toList();
        eastMoneyClient.snapshotExpectedCount = 10;
        eastMoneyClient.snapshotComplete = false;
        eastMoneyClient.klines.put("600000", rightEarlyKLines("600000", "10.62", "180000"));
        eastMoneyClient.financials.put("600000", goodFinancial("600000"));

        ShortTermReport report = service.finalReport(
                new ShortTermScanRequest(3, 100, 10, null,null, null, null, null, null),
                Set.of("600000")
        );

        assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("0.9000");
        assertThat(report.coverage().executionReliable()).isFalse();
        assertThat(report.candidates()).isNotEmpty();
        assertThat(report.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.action()).isIn("MARKET_RISK_WAIT", "DATA_REVIEW");
            assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
            assertThat(candidate.tradePlan().status()).isEqualTo("BLOCKED");
        });
    }

    @Test
    void completeNoPriceRowsAreAuditedButExcludedFromEffectiveCoverageDenominator() {
        List<EastMoneyQuote> valid = IntStream.range(0, 94)
                .mapToObj(index -> quote(
                        String.format("600%03d", index), "有效样本" + index,
                        "10.62", "1.20", "18", "1.60", "600000000"))
                .toList();
        List<EastMoneyQuote> invalid = IntStream.range(94, 100)
                .mapToObj(index -> quote(
                        String.format("600%03d", index), "无价格样本" + index,
                        "0", "1.20", "18", "1.60", "600000000"))
                .toList();
        eastMoneyClient.quotes = java.util.stream.Stream.concat(valid.stream(), invalid.stream()).toList();
        eastMoneyClient.snapshotExpectedCount = 100;

        ShortTermReport report = service.report(
                new ShortTermScanRequest(3, 6000, 10, null, null, null, null, null, null, null, null)
        );

        assertThat(report.coverage().rawExpectedCount()).isEqualTo(100);
        assertThat(report.coverage().rawFetchedCount()).isEqualTo(100);
        assertThat(report.coverage().excludedNoPriceCount()).isEqualTo(6);
        assertThat(report.coverage().rawComplete()).isTrue();
        assertThat(report.coverage().fetchedCount()).isEqualTo(94);
        assertThat(report.coverage().expectedCount()).isEqualTo(94);
        assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("1.0000");
        assertThat(report.coverage().executionReliable()).isTrue();
        assertThat(report.quoteNote()).contains(
                "有效行情覆盖 94/94",
                "行情源原始抓取 100/100",
                "无有效现价排除 6"
        );
    }

    @Test
    void missingRawRowsRemainInEffectiveDenominatorAndKeepCoverageUnreliable() {
        List<EastMoneyQuote> valid = IntStream.range(0, 99)
                .mapToObj(index -> quote(
                        String.format("600%03d", index), "有效样本" + index,
                        "10.62", "1.20", "18", "1.60", "600000000"))
                .toList();
        List<EastMoneyQuote> noPrice = IntStream.range(0, 5)
                .mapToObj(index -> quote(
                        String.format("601%03d", index), "无价格样本" + index,
                        "0", "1.20", "18", "1.60", "600000000"))
                .toList();
        eastMoneyClient.quotes = java.util.stream.Stream.concat(valid.stream(), noPrice.stream()).toList();
        eastMoneyClient.snapshotExpectedCount = 105;
        eastMoneyClient.snapshotComplete = false;

        ShortTermReport report = service.report(
                new ShortTermScanRequest(3, 6000, 10, null, null, null, null, null, null, null, null)
        );

        assertThat(report.coverage().rawExpectedCount()).isEqualTo(105);
        assertThat(report.coverage().rawFetchedCount()).isEqualTo(104);
        assertThat(report.coverage().excludedNoPriceCount()).isEqualTo(5);
        assertThat(report.coverage().rawComplete()).isFalse();
        assertThat(report.coverage().expectedCount()).isEqualTo(100);
        assertThat(report.coverage().fetchedCount()).isEqualTo(99);
        assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("0.9900");
        assertThat(report.coverage().executionReliable()).isFalse();
    }

    @Test
    void finalReportExcludesFutureQuoteBeforeAnyExpensiveReview() {
        Instant validTimestamp = Instant.parse("2026-07-07T06:49:00Z");
        eastMoneyClient.quotes = List.of(
                quoteAt("600001", "有效报价", "普通行业", validTimestamp),
                quoteAt("600002", "未来报价", "机器人", Instant.parse("2026-07-07T06:52:00Z"))
        );
        eastMoneyClient.snapshotExpectedCount = 2;
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.klines.put("600002", rightEarlyKLines("600002", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.financials.put("600002", goodFinancial("600002"));
        Clock decisionClock = Clock.fixed(Instant.parse("2026-07-07T06:50:00Z"), SHANGHAI);
        ShortTermService pointInTimeService = serviceAt(decisionClock);

        ShortTermReport report = pointInTimeService.finalReport(
                new ShortTermScanRequest(3, 100, 10, null,null, null, null, null, null),
                Set.of("600001", "600002")
        );

        assertThat(report.coverage().fetchedCount()).isEqualTo(1);
        assertThat(report.coverage().executionReliable()).isFalse();
        assertThat(report.reviewedSymbols()).containsExactly("600001");
        assertThat(eastMoneyClient.requestedKlineSymbols).doesNotContain("600002");
        assertThat(report.hotDirections()).extracting(ShortTermHotDirection::code)
                .doesNotContain("ROBOT_EQUIPMENT");
        assertThat(report.dataCutoffAt()).isEqualTo(validTimestamp);
    }

    @Test
    void finalReportUsesQuoteFetchCompletionAsDecisionTime() {
        MutableClock decisionClock = new MutableClock(
                Instant.parse("2026-07-07T06:50:00Z"),
                SHANGHAI
        );
        Instant quoteTimestamp = Instant.parse("2026-07-07T06:51:00Z");
        eastMoneyClient.quotes = List.of(
                quoteAt("600001", "抓取期间更新", "普通行业", quoteTimestamp)
        );
        eastMoneyClient.snapshotExpectedCount = 1;
        eastMoneyClient.snapshotFetchedAt = Instant.parse("2026-07-07T06:51:30Z");
        eastMoneyClient.afterQuoteSnapshotFetched = () ->
                decisionClock.setInstant(Instant.parse("2026-07-07T06:52:00Z"));
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));

        ShortTermReport report = serviceAt(decisionClock).finalReport(
                new ShortTermScanRequest(3, 100, 10, null,null, null, null, null, null),
                Set.of("600001")
        );

        assertThat(report.coverage().fetchedCount()).isEqualTo(1);
        assertThat(report.coverage().executionReliable()).isTrue();
        assertThat(report.reviewedSymbols()).containsExactly("600001");
        assertThat(report.dataCutoffAt()).isEqualTo(quoteTimestamp);
    }

    @Test
    void finalReportBlocksMissingMarketTimestampAndNeverUsesFetchedAtAsCutoff() {
        eastMoneyClient.quotes = List.of(quoteAt("600001", "无市场时间", "普通行业", null));
        eastMoneyClient.snapshotExpectedCount = 1;
        Clock decisionClock = Clock.fixed(Instant.parse("2026-07-07T06:50:00Z"), SHANGHAI);
        ShortTermService pointInTimeService = serviceAt(decisionClock);

        ShortTermReport report = pointInTimeService.finalReport(
                new ShortTermScanRequest(3, 100, 10, null,null, null, null, null, null),
                Set.of("600001")
        );

        assertThat(report.coverage().fetchedCount()).isZero();
        assertThat(report.coverage().executionReliable()).isFalse();
        assertThat(report.reviewedSymbols()).isEmpty();
        assertThat(report.dataCutoffAt()).isNull();
        assertThat(eastMoneyClient.requestedKlineSymbols).isEmpty();
    }

    @Test
    void manualReportExcludesFutureQuoteFromResearchAndScoring() {
        assertManualReportIgnoresInvalidQuote(quoteAt(
                "600002",
                "未来热点报价",
                "机器人",
                Instant.parse("2026-07-07T06:52:00Z"),
                "8.50",
                "2000000000"
        ));
    }

    @Test
    void manualReportExcludesMissingTimestampQuoteFromResearchAndScoring() {
        assertManualReportIgnoresInvalidQuote(quoteAt(
                "600002",
                "无时间热点报价",
                "机器人",
                null,
                "8.50",
                "2000000000"
        ));
    }

    @Test
    void finalReportRejectsEmptyPreselection() {
        assertThatThrownBy(() -> service.finalReport(ShortTermScanRequest.empty(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预选股票为空");
    }

    @Test
    void shouldExposeCoverageAndKeepAbsentCompatibilityCoverageUnreliable() {
        eastMoneyClient.quotes = IntStream.range(0, 19)
                .mapToObj(index -> quote(
                        String.format("6001%02d", index),
                        "覆盖样本" + index,
                        "10.62",
                        "1.20",
                        "18",
                        "1.60",
                        "600000000"
                ))
                .toList();
        eastMoneyClient.snapshotExpectedCount = 20;
        eastMoneyClient.snapshotComplete = false;
        eastMoneyClient.quotes.forEach(quote ->
                eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "180000")));

        ShortTermReport report = service.report(
                new ShortTermScanRequest(3, 100, 19, null,null, null, null, null, null)
        );

        assertThat(report.coverage().expectedCount()).isEqualTo(20);
        assertThat(report.coverage().fetchedCount()).isEqualTo(19);
        assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("0.9500");
        assertThat(report.coverage().executionReliable()).isFalse();
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
        eastMoneyClient.snapshotFetchedAt = Instant.parse("2026-07-07T06:53:00Z");
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));

        ShortTermReport report = service.report(ShortTermScanRequest.empty());

        assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("1.0000");
        assertThat(report.coverage().executionReliable()).isFalse();
        assertThat(report.marketSentiment().phase()).isEqualTo("行情覆盖不足");
    }

    @Test
    void actionableTailUsesOnlyMinutesFrom1445InclusiveTo1450Exclusive() {
        eastMoneyClient.quotes = List.of(
                quote("600001", "尾盘边界", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.intraday.put("600001", List.of(
                intraday("600001", "2026-07-07T14:44", "10.00", "10.00", "10.01", "9.99", "10000", "10000000", "10.00"),
                intraday("600001", "2026-07-07T14:45", "10.05", "10.10", "10.11", "10.04", "12000", "12000000", "10.05"),
                intraday("600001", "2026-07-07T14:48", "10.15", "10.20", "10.21", "10.14", "14000", "14000000", "10.10"),
                intraday("600001", "2026-07-07T14:49", "10.20", "10.21", "10.22", "10.18", "15000", "15000000", "10.11"),
                intraday("600001", "2026-07-07T14:50", "8.00", "8.00", "8.01", "7.99", "90000", "90000000", "9.80")
        ));

        ShortTermCandidate candidate = find(
                service.report(1, 100, 5, null,null, null, null, null, null, null),
                "600001"
        );

        assertThat(candidate.tailSignal().latestMinute()).isEqualTo("14:49");
        assertThat(candidate.tailSignal().tailStartPrice()).isEqualByComparingTo("10.10");
        assertThat(candidate.tailSignal().changeFromActionableTailPercent()).isEqualByComparingTo("1.09");
        assertThat(candidate.tailSignal().actionableTailWindow()).isTrue();
        assertThat(candidate.tailSignal().reasons()).anySatisfy(reason ->
                assertThat(reason).contains("14:50", "未参与"));
    }

    @Test
    void actionableTailNeverUsesMinuteAfterDecisionTime() {
        eastMoneyClient.quotes = List.of(
                quoteAt(
                        "600001",
                        "点时边界",
                        "通用设备",
                        Instant.parse("2026-07-07T06:49:00Z")
                )
        );
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.intraday.put("600001", List.of(
                intraday("600001", "2026-07-07T14:45", "10.05", "10.10", "10.11", "10.04", "12000", "12000000", "10.05"),
                intraday("600001", "2026-07-07T14:52", "10.15", "10.20", "10.21", "10.14", "14000", "14000000", "10.10")
        ));
        Clock decisionClock = Clock.fixed(Instant.parse("2026-07-07T06:50:00Z"), SHANGHAI);
        ShortTermService pointInTimeService = serviceAt(decisionClock);

        ShortTermCandidate candidate = find(
                pointInTimeService.report(1, 100, 5, null,null, null, null, null, null, null),
                "600001"
        );

        assertThat(candidate.tailSignal().latestMinute()).isEqualTo("14:45");
        assertThat(candidate.tailSignal().latestPrice()).isEqualByComparingTo("10.10");
    }

    private ShortTermService serviceAt(Clock clock) {
        TradingClockService tradingClock = new TradingClockService(clock);
        return new ShortTermService(
                eastMoneyClient,
                new EvidenceCompletenessService(),
                tradingClock,
                new QuoteFreshnessService(tradingClock, clock)
        );
    }

    private ShortTermService chipAwareService(
            String activationMode,
            ShortTermChipAnalysisService chipAnalysisService
    ) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.chip.enabled", "true")
                .withProperty("research.short-term.chip.activation-mode", activationMode);
        return new ShortTermService(
                eastMoneyClient,
                new EvidenceCompletenessService(),
                tradingClockService,
                new QuoteFreshnessService(tradingClockService, TEST_CLOCK),
                new ShortTermTechnicalSignalEvaluator(new ShortTermGoldenCrossAnalyzer()),
                new ShortTermTradePlanService(tradingClockService),
                new ShortTermAutomationSettings(environment),
                chipAnalysisService,
                new ShortTermChipSettings(environment)
        );
    }

    private ShortTermChipSnapshot chipSnapshot(String contribution) {
        ShortTermChipSnapshot snapshot = mock(ShortTermChipSnapshot.class);
        when(snapshot.contributionScore()).thenReturn(new BigDecimal(contribution));
        return snapshot;
    }

    private void assertManualReportIgnoresInvalidQuote(EastMoneyQuote invalidQuote) {
        EastMoneyQuote validQuote = quoteAt(
                "600001",
                "有效热点报价",
                "机器人",
                Instant.parse("2026-07-07T06:49:00Z"),
                "1.20",
                "600000000"
        );
        for (EastMoneyQuote quote : List.of(validQuote, invalidQuote)) {
            eastMoneyClient.klines.put(
                    quote.symbol(),
                    rightEarlyKLines(quote.symbol(), quote.latestPrice().toPlainString(), "180000")
            );
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        }
        Clock decisionClock = Clock.fixed(Instant.parse("2026-07-07T06:50:00Z"), SHANGHAI);
        ShortTermService pointInTimeService = serviceAt(decisionClock);
        ShortTermScanRequest request = new ShortTermScanRequest(
                3, 100, 10, null,null, null, null, null, null
        );

        eastMoneyClient.quotes = List.of(validQuote);
        ShortTermReport baseline = pointInTimeService.report(request);
        ShortTermCandidate baselineCandidate = find(baseline, validQuote.symbol());

        eastMoneyClient.requestedKlineSymbols.clear();
        eastMoneyClient.requestedFinancialSymbols.clear();
        eastMoneyClient.quotes = List.of(validQuote, invalidQuote);
        ShortTermReport report = pointInTimeService.report(request);
        ShortTermCandidate candidate = find(report, validQuote.symbol());

        assertThat(report.coverage().fetchedCount()).isEqualTo(1);
        assertThat(report.coverage().executionReliable()).isFalse();
        assertThat(report.hotDirections()).isEqualTo(baseline.hotDirections());
        assertThat(report.reviewedSymbols()).containsExactly(validQuote.symbol());
        assertThat(eastMoneyClient.requestedKlineSymbols).containsExactly(validQuote.symbol());
        assertThat(eastMoneyClient.requestedFinancialSymbols).containsExactly(validQuote.symbol());
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly(validQuote.symbol());
        assertThat(candidate.score().marketHeatScore())
                .isEqualByComparingTo(baselineCandidate.score().marketHeatScore());
        assertThat(candidate.score().finalScore())
                .isEqualByComparingTo(baselineCandidate.score().finalScore());
        assertThat(candidate.action()).isEqualTo("MARKET_RISK_WAIT");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
    }

    @Test
    void shouldReturnUpToEightShortTermRecommendationsByDefault() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry("600101", "光通信甲", "光通信A", "10.62", "1.60", "35", "4.2", "900000000"),
                quoteWithIndustry("600102", "光通信乙", "光通信B", "10.62", "1.50", "42", "4.6", "860000000"),
                quoteWithIndustry("600103", "光通信丙", "光通信C", "10.62", "1.40", "48", "5.1", "820000000"),
                quoteWithIndustry("600104", "光通信丁", "光通信D", "10.62", "1.30", "55", "5.5", "780000000"),
                quoteWithIndustry("600105", "光通信戊", "光通信E", "10.62", "1.20", "60", "5.9", "740000000")
        );
        for (EastMoneyQuote quote : eastMoneyClient.quotes) {
            eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "180000"));
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        }

        ShortTermReport report = service.report(null, 100, 10, null,null, null, null, null, null, null);

        assertThat(report.candidates()).hasSize(5);
        assertThat(report.methodology()).anySatisfy(item ->
                assertThat(item).contains("默认输出八个", "观察层", "不会为了凑数"));
    }

    @Test
    void shouldComputeAtrAndSupportFromCompletedDailyBars() {
        eastMoneyClient.quotes = List.of(
                quote("600107", "波动样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600107", rightEarlyKLines("600107", "10.62", "180000"));
        eastMoneyClient.financials.put("600107", goodFinancial("600107"));

        ShortTermCandidate candidate = find(
                service.report(1, 100, 5, null,null, null, null, null, null, null),
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
                service.report(1, 100, 5, null,null, null, null, null, null, null),
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

        ShortTermReport report = service.report(3, 100, 10, null,null, null, null, null, null, null);

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

        ShortTermReport report = service.report(2, 100, 10, null,null, null, null, null, null, null);

        ShortTermCandidate confirmed = find(report, "600601");
        ShortTermCandidate observed = find(report, "600602");
        assertThat(confirmed.technical().rightSideSignal()).isEqualTo("右侧早期确认");
        assertThat(observed.technical().rightSideSignal()).isEqualTo("右侧早期观察");
        assertThat(confirmed.action()).isEqualTo(observed.action()).isEqualTo("WAIT_CONFIRM");
        assertThat(confirmed.technical().goldenCross().priorityTier())
                .isEqualTo(observed.technical().goldenCross().priorityTier())
                .isEqualTo(1);
        assertThat(confirmed.score().rankingScore()).isGreaterThan(observed.score().rankingScore());
        assertThat(confirmed.score().technicalRankingScore())
                .isEqualByComparingTo(confirmed.score().finalScore().add(confirmed.score().stageAdjustment()));
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600601", "600602");
    }

    @Test
    void shouldPrioritizeStrongBuyingPressureWithinTheSameActionLayer() {
        eastMoneyClient.quotes = List.of(
                quote("600607", "强买盘样本", "10.62", "1.20", "18", "1.6", "600000000"),
                quote("600608", "资金流出样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600607", rightEarlyKLines("600607", "10.62", "180000"));
        eastMoneyClient.klines.put("600608", rightEarlyKLines("600608", "10.62", "180000"));
        eastMoneyClient.quotes.forEach(quote ->
                eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol())));
        eastMoneyClient.fundFlows.put("600607", fundFlow("600607", "8", "3", "2"));
        eastMoneyClient.fundFlows.put("600608", fundFlow("600608", "-6", "-2", "-1"));

        ShortTermReport report = service.report(2, 100, 10, null,null, null, null, null, null, null);

        ShortTermCandidate strongBuying = find(report, "600607");
        ShortTermCandidate outflow = find(report, "600608");
        assertThat(strongBuying.action()).isEqualTo(outflow.action());
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600607", "600608");
        assertThat(strongBuying.score().buyPressureScore())
                .isGreaterThan(outflow.score().buyPressureScore());
        assertThat(strongBuying.score().mainNetInflowRatio()).isEqualByComparingTo("8.00");
        assertThat(strongBuying.strengths()).anyMatch(item -> item.contains("主力净流入"));
        assertThat(eastMoneyClient.fundFlowBatchCalls).isEqualTo(1);
        assertThat(eastMoneyClient.requestedFundFlowSymbols)
                .containsExactlyInAnyOrder("600607", "600608");
    }

    @Test
    void marketFundDirectionIsReportContextAndDoesNotReorderCandidates() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry("600607", "强买盘样本", "电子", "10.62", "1.20", "18", "1.6", "600000000"),
                quoteWithIndustry("600608", "资金流出样本", "银行", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600607", rightEarlyKLines("600607", "10.62", "180000"));
        eastMoneyClient.klines.put("600608", rightEarlyKLines("600608", "10.62", "180000"));
        eastMoneyClient.quotes.forEach(quote ->
                eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol())));
        eastMoneyClient.fundFlows.put("600607", fundFlow("600607", "8", "3", "2"));
        eastMoneyClient.fundFlows.put("600608", fundFlow("600608", "-6", "-2", "-1"));
        eastMoneyClient.industryFundFlows = List.of(
                industryFundFlow("BK0475", "银行", "900000000000", "5.5", 41, 1),
                industryFundFlow("BK1201", "电子", "-120000000000", "-1.8", 100, 220)
        );

        ShortTermReport report = service.report(2, 100, 10, null,null, null, null, null, null, null);

        assertThat(report.marketFundDirection().topInflows())
                .extracting(ShortTermIndustryFundDirection::name)
                .containsExactly("银行");
        assertThat(report.marketFundDirection().topOutflows())
                .extracting(ShortTermIndustryFundDirection::name)
                .containsExactly("电子");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600607", "600608");
        assertThat(eastMoneyClient.industryFundFlowCalls).isEqualTo(1);
    }

    @Test
    void marketFundDirectionFailureCreatesDataGapWithoutBlockingReport() {
        eastMoneyClient.industryFundFlowFailure = new IllegalStateException("行业接口超时");

        ShortTermReport report = service.report(3, 100, 10, null,null, null, null, null, null, null);

        assertThat(report.marketFundDirection().topInflows()).isEmpty();
        assertThat(report.marketFundDirection().topOutflows()).isEmpty();
        assertThat(report.marketFundDirection().dataGaps())
                .anyMatch(gap -> gap.contains("行业资金流获取失败") && gap.contains("行业接口超时"));
    }

    @Test
    void legacyActiveChipConfigurationCannotChangeProductionOrder() {
        eastMoneyClient.quotes = List.of(
                quote("600607", "强买盘样本", "10.62", "1.20", "18", "1.6", "600000000"),
                quote("600608", "优质筹码样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600607", rightEarlyKLines("600607", "10.62", "180000"));
        eastMoneyClient.klines.put("600608", rightEarlyKLines("600608", "10.62", "180000"));
        eastMoneyClient.quotes.forEach(quote ->
                eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol())));
        eastMoneyClient.fundFlows.put("600607", fundFlow("600607", "8", "3", "2"));
        eastMoneyClient.fundFlows.put("600608", fundFlow("600608", "-6", "-2", "-1"));
        ShortTermChipAnalysisService chipAnalysis = mock(ShortTermChipAnalysisService.class);
        when(chipAnalysis.analyze(any(), anyList(), anyBoolean(), any())).thenAnswer(invocation -> {
            EastMoneyQuote quote = invocation.getArgument(0);
            return chipSnapshot("600608".equals(quote.symbol()) ? "25" : "0");
        });

        ShortTermReport shadow = chipAwareService("SHADOW", chipAnalysis)
                .report(2, 100, 10, null,null, null, null, null, null, null);
        ShortTermReport active = chipAwareService("ACTIVE", chipAnalysis)
                .report(2, 100, 10, null,null, null, null, null, null, null);

        assertThat(shadow.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600607", "600608");
        assertThat(shadow.candidates()).allSatisfy(candidate -> assertThat(candidate.chip()).isNotNull());
        assertThat(find(shadow, "600608").score().chipContributionScore())
                .isGreaterThan(find(shadow, "600607").score().chipContributionScore());
        assertThat(find(shadow, "600608").score().v3RankingScore()).isNotNull();
        assertThat(active.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600607", "600608");
        assertThat(find(active, "600608").action()).isEqualTo(find(active, "600607").action());
        assertThat(find(active, "600608").score().v3Rank()).isEqualTo(1);
        assertThat(find(active, "600608").score().rankDelta()).isPositive();
        assertThat(find(active, "600608").score().rankingScore())
                .isEqualByComparingTo(find(active, "600608").score().v2RankingScore());
        assertThat(active.quoteNote()).doesNotContain("筹码");
        assertThat(active.methodology()).noneMatch(item -> item.contains("筹码"));
        assertThat(active.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.strengths()).noneMatch(item -> item.contains("筹码"));
            assertThat(candidate.risks()).noneMatch(item -> item.contains("筹码"));
            assertThat(candidate.evidence()).allSatisfy(item -> {
                assertThat(item.title()).doesNotContain("筹码");
                assertThat(item.summary()).doesNotContain("筹码");
            });
        });
        assertThat(eastMoneyClient.turnoverEnrichmentCalls).isPositive();
    }

    @Test
    void activeChipRankingUsesOneComparatorWhenChipAvailabilityIsMixed() {
        eastMoneyClient.quotes = List.of(
                quote("600607", "筹码样本", "10.62", "1.20", "18", "1.6", "600000000"),
                quote("600608", "低分资金流样本", "10.62", "1.20", "18", "1.6", "600000000"),
                quote("600609", "高分无资金流样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.quotes.forEach(quote -> {
            eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "180000"));
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        });
        eastMoneyClient.fundFlows.put("600608", fundFlow("600608", "-6", "-2", "-1"));
        ShortTermChipAnalysisService chipAnalysis = mock(ShortTermChipAnalysisService.class);
        when(chipAnalysis.analyze(any(), anyList(), anyBoolean(), any())).thenAnswer(invocation -> {
            EastMoneyQuote quote = invocation.getArgument(0);
            return "600607".equals(quote.symbol()) ? chipSnapshot("25") : null;
        });

        ShortTermReport report = chipAwareService("ACTIVE", chipAnalysis)
                .report(3, 100, 10, null,null, null, null, null, null, null);

        ShortTermCandidate lowScoreWithFlow = find(report, "600608");
        ShortTermCandidate highScoreWithoutFlow = find(report, "600609");
        assertThat(highScoreWithoutFlow.action()).isEqualTo(lowScoreWithFlow.action());
        assertThat(highScoreWithoutFlow.score().rankingScore())
                .isGreaterThan(lowScoreWithFlow.score().rankingScore());
        assertThat(report.candidates().indexOf(highScoreWithoutFlow))
                .isLessThan(report.candidates().indexOf(lowScoreWithFlow));
    }

    @Test
    void deserializesLegacyV2CandidateWithoutNewRankingAndSignalFields() throws Exception {
        eastMoneyClient.quotes = List.of(
                quote("600607", "历史样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600607", rightEarlyKLines("600607", "10.62", "180000"));
        eastMoneyClient.financials.put("600607", goodFinancial("600607"));
        ShortTermCandidate current = service
                .report(1, 100, 10, null,null, null, null, null, null, null)
                .candidates()
                .get(0);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        com.fasterxml.jackson.databind.node.ObjectNode legacy = mapper.valueToTree(current);
        legacy.remove("chip");
        legacy.remove("volatilityQuality");
        legacy.remove("signalProfile");
        com.fasterxml.jackson.databind.node.ObjectNode score =
                (com.fasterxml.jackson.databind.node.ObjectNode) legacy.get("score");
        List.of(
                "v2RankingScore", "chipContributionScore", "v3RankingScore",
                "v2Rank", "v3Rank", "rankDelta"
        ).forEach(score::remove);

        ShortTermCandidate restored = mapper.treeToValue(legacy, ShortTermCandidate.class);

        assertThat(restored.symbol()).isEqualTo("600607");
        assertThat(restored.chip()).isNull();
        assertThat(restored.score().v3RankingScore()).isNull();
        assertThat(restored.volatilityQuality().state()).isEqualTo("UNAVAILABLE");
        assertThat(restored.signalProfile().primaryFamily()).isEqualTo("UNAVAILABLE");
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

        ShortTermReport report = service.report(2, 100, 10, null,null, null, null, null, null, null);

        ShortTermCandidate blocked = find(report, "600603");
        ShortTermCandidate eligible = find(report, "600604");
        assertThat(blocked.technical().rightSideSignal()).isEqualTo("右侧早期确认");
        assertThat(blocked.action()).isEqualTo("DATA_REVIEW");
        assertThat(eligible.technical().rightSideSignal()).isEqualTo("右侧早期观察");
        assertThat(eligible.action()).isEqualTo("WAIT_CONFIRM");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600604", "600603");
    }

    @Test
    void shouldRankActionPriorityBeforeRightSideMaturity() {
        eastMoneyClient.quotes = List.of(
                quote("600605", "等回踩确认样本", "12.90", "1.20", "18", "1.6", "600000000"),
                quote("600606", "右侧观察样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600605", rightEarlyKLines("600605", "12.90", "180000"));
        eastMoneyClient.klines.put("600606", rightEarlyKLines("600606", "10.62", "105000"));
        eastMoneyClient.quotes.forEach(quote ->
                eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol())));
        ShortTermChipAnalysisService chipAnalysis = mock(ShortTermChipAnalysisService.class);
        when(chipAnalysis.analyze(any(), anyList(), anyBoolean(), any())).thenAnswer(invocation -> {
            EastMoneyQuote quote = invocation.getArgument(0);
            return chipSnapshot("600605".equals(quote.symbol()) ? "25" : "0");
        });

        ShortTermReport report = chipAwareService("ACTIVE", chipAnalysis)
                .report(2, 100, 10, null,null, null, null, null, null, null);

        ShortTermCandidate pullback = find(report, "600605");
        ShortTermCandidate observation = find(report, "600606");
        assertThat(pullback.technical().rightSideSignal()).isEqualTo("右侧已拉开");
        assertThat(pullback.action()).isEqualTo("WAIT_PULLBACK");
        assertThat(observation.technical().rightSideSignal()).isEqualTo("右侧早期观察");
        assertThat(observation.action()).isEqualTo("WAIT_CONFIRM");
        assertThat(pullback.technical().goldenCross().priorityTier())
                .isEqualTo(observation.technical().goldenCross().priorityTier())
                .isEqualTo(1);
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600605", "600606");
    }

    @Test
    void shouldNotExecuteOverextendedConfirmedGoldenCross() {
        eastMoneyClient.quotes = List.of(
                quote("600504", "过度拉开金叉", "12.00", "1.60", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600504", recentGoldenCrossKLines("600504", 2));
        eastMoneyClient.financials.put("600504", goodFinancial("600504"));

        ShortTermCandidate overextendedConfirmed = find(
                service.report(3, 100, 10, null,null, null, null, null, null, null),
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
                service.report(3, 100, 10, null,null, null, null, null, null, null),
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
                service.report(3, 100, 10, null,null, null, null, null, null, null),
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

        ShortTermReport report = service.report(4, 50, 10, null,null, null, null, null, null, null);

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

        ShortTermReport report = service.report(5, 50, 10, null,null, null, null, null, null, null);

        assertThat(report.marketSentiment().phase()).isEqualTo("冰点/混沌");
        assertThat(report.marketRegime().state()).isEqualTo("RISK_OFF");
        assertThat(report.candidates()).isEmpty();
        assertThat(eastMoneyClient.requestedKlineSymbols).isEmpty();
    }

    @Test
    void shouldBlockAllShortTermRecommendationsWhenFullMarketIsInExtremeSelloff() {
        List<EastMoneyQuote> quotes = new ArrayList<>();
        quotes.add(quote("600201", "逆势右侧候选", "10.62", "1.60", "18", "1.6", "600000000"));
        for (int index = 0; index < 4200; index++) {
            quotes.add(quote(String.format("%06d", 601000 + index), "普跌样本" + index,
                    "10.00", "-2.10", "18", "1.6", "300000000"));
        }
        for (int index = 0; index < 1000; index++) {
            quotes.add(quote(String.format("%06d", 1 + index), "跌停样本" + index,
                    "10.00", "-10.00", "18", "1.6", "300000000"));
        }
        eastMoneyClient.quotes = quotes;
        eastMoneyClient.snapshotExpectedCount = quotes.size();
        eastMoneyClient.klines.put("600201", rightEarlyKLines("600201", "10.62", "180000"));
        eastMoneyClient.financials.put("600201", goodFinancial("600201"));

        ShortTermReport report = service.report(8, 6000, 60, null,null, null, null, null, null, null);

        assertThat(report.coverage().executionReliable()).isTrue();
        assertThat(report.marketSentiment().phase()).isEqualTo("极端退潮");
        assertThat(report.marketRegime().state()).isEqualTo("RISK_OFF");
        assertThat(report.marketRegime().maxAction()).isEqualTo("NO_TRADE");
        assertThat(report.marketSentiment().declining()).isGreaterThan(5000);
        assertThat(report.marketSentiment().limitDownLike()).isGreaterThanOrEqualTo(1000);
        assertThat(report.candidates()).isEmpty();
        assertThat(report.candidateCount()).isZero();
        assertThat(report.quoteNote()).contains("极端弱市");
        assertThat(eastMoneyClient.requestedKlineSymbols).isEmpty();
        assertThat(eastMoneyClient.requestedFinancialSymbols).isEmpty();
    }

    @Test
    void shouldStillUseBreadthRiskOffGateBelowSixHundredLimitDownLikeStocks() {
        List<EastMoneyQuote> quotes = new ArrayList<>();
        quotes.add(quote("600201", "逆势右侧候选", "10.62", "1.60", "18", "1.6", "600000000"));
        for (int index = 0; index < 5400; index++) {
            quotes.add(quote(String.format("%06d", 601000 + index), "普跌样本" + index,
                    "10.00", "-2.10", "18", "1.6", "300000000"));
        }
        for (int index = 0; index < 599; index++) {
            quotes.add(quote(String.format("%06d", 1 + index), "跌停样本" + index,
                    "10.00", "-10.00", "18", "1.6", "300000000"));
        }
        eastMoneyClient.quotes = quotes;
        eastMoneyClient.snapshotExpectedCount = quotes.size();
        eastMoneyClient.klines.put("600201", rightEarlyKLines("600201", "10.62", "180000"));
        eastMoneyClient.financials.put("600201", goodFinancial("600201"));

        ShortTermReport report = service.report(8, 6000, 60, null,null, null, null, null, null, null);

        assertThat(report.coverage().executionReliable()).isTrue();
        assertThat(report.marketSentiment().phase()).isEqualTo("退潮");
        assertThat(report.marketSentiment().limitDownLike()).isEqualTo(599);
        assertThat(report.marketRegime().state()).isEqualTo("RISK_OFF");
        assertThat(report.quoteNote()).contains("极端弱市闸门");
        assertThat(eastMoneyClient.requestedKlineSymbols).isEmpty();
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

        ShortTermReport report = service.report(3, 50, 12, null,null, null, null, null, null, null);

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
            quotes.add(quote("6007" + index, "下跌样本" + index, "10.00", "-1.50", "18", "1.6", "1000000"));
        }
        eastMoneyClient.quotes = quotes;
        for (String symbol : List.of("600211", "600212", "600213")) {
            eastMoneyClient.klines.put(symbol, rightEarlyKLines(symbol, "10.62", "90000"));
        }
        eastMoneyClient.klines.put("600399", belowMa20KLines("600399"));
        eastMoneyClient.financials.put("600399", goodFinancial("600399"));

        ShortTermReport report = service.report(3, 100, 12, null,null, null, null, null, null, null);

        assertThat(report.marketSentiment().phase()).isEqualTo("冰点/混沌");
        assertThat(report.candidates()).hasSize(3);
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600211", "600212", "600213");
        assertThat(report.candidates()).allSatisfy(candidate ->
                assertThat(candidate.technical().rightSideSignal()).contains("右侧"));
    }

    @Test
    void shouldKeepShrinkingRiseAsObservationInsteadOfVolumeConfirmation() {
        eastMoneyClient.quotes = List.of(
                quote("600214", "缩量上涨", "10.62", "1.10", "28", "2.6", "280000000")
        );
        eastMoneyClient.klines.put("600214", rightEarlyKLines("600214", "10.62", "80000"));
        eastMoneyClient.financials.put("600214", goodFinancial("600214"));

        ShortTermReport report = service.report(3, 100, 5, null,null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600214");
        assertThat(candidate.technical().volumeRatio20()).isLessThan(BigDecimal.ONE);
        assertThat(candidate.score().volumeScore()).isLessThan(new BigDecimal("65"));
        assertThat(candidate.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
        assertThat(candidate.strengths()).anySatisfy(strength -> assertThat(strength).contains("缩量上涨", "惜售"));
        assertThat(candidate.evidence()).extracting(ShortTermEvidence::summary)
                .anySatisfy(summary -> assertThat(summary).contains("量比 1.20-3.20 才属于主确认区间"));
    }

    @Test
    void usesScanSnapshotVolumeForDisplayAndKeepsLowThreeDayRatioAsCandidate() {
        String symbol = "600215";
        EastMoneyQuote scanQuote = withVolume(
                quote(symbol, "扫描量样本", "10.62", "1.10", "28", "2.6", "280000000"),
                "50000"
        );
        eastMoneyClient.quotes = List.of(scanQuote);
        eastMoneyClient.klines.put(
                symbol,
                endingOn(
                        confirmedRightEarlyKLines(symbol, "10.62", "230000"),
                        scanQuote.tradeDate()
                )
        );
        eastMoneyClient.financials.put(symbol, goodFinancial(symbol));

        ShortTermReport report = service.report(
                3, 100, 10, null, null, null, null, null, null, null
        );

        ShortTermCandidate candidate = find(report, symbol);
        assertThat(candidate.technical().todayVolume()).isEqualByComparingTo("50000.00");
        assertThat(candidate.technical().averageVolume3()).isEqualByComparingTo("105000.00");
        assertThat(candidate.technical().volumeRatio3()).isEqualByComparingTo("0.48");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).contains(symbol);
    }

    @Test
    void shouldAllowOnlyLightTrialWhenCrowdedMarketHasConfirmedRightSideAndTailEvidence() {
        List<EastMoneyQuote> quotes = new ArrayList<>();
        quotes.add(withTurnover(
                quoteAt(
                        "600301",
                        "右侧候选",
                        "通用设备",
                        Instant.parse("2026-07-07T06:49:00Z"),
                        "1.60",
                        "600000000"
                ),
                "3.00"
        ));
        for (int index = 0; index < 80; index++) {
            quotes.add(quoteAt(
                    String.format("60%04d", 400 + index),
                    "涨停样本" + index,
                    "通用设备",
                    Instant.parse("2026-07-07T06:49:00Z"),
                    "9.60",
                    "300000000"
            ));
        }
        eastMoneyClient.quotes = quotes;
        eastMoneyClient.snapshotExpectedCount = quotes.size();
        eastMoneyClient.snapshotFetchedAt = Instant.parse("2026-07-07T06:49:00Z");
        eastMoneyClient.klines.put("600301", confirmedRightEarlyKLines("600301", "10.62", "180000"));
        eastMoneyClient.financials.put("600301", goodFinancial("600301"));
        eastMoneyClient.intraday.put("600301", confirmedTail("600301"));

        ShortTermReport report = serviceAt(Clock.fixed(Instant.parse("2026-07-07T06:49:30Z"), SHANGHAI))
                .report(5, 100, 10, null,null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600301");
        assertThat(report.coverage().executionReliable()).as(report.coverage().toString()).isTrue();
        assertThat(report.marketSentiment().phase()).isEqualTo("高潮");
        assertThat(report.marketRegime().state()).isEqualTo("CROWDED_VOLATILE");
        assertThat(candidate.action()).isEqualTo("REGIME_LIGHT_TRIAL");
        assertThat(candidate.todayAdvice().action()).isEqualTo("LIGHT_TRIAL");
        assertThat(candidate.todayAdvice().summary()).contains("拥挤高波动", "轻仓");
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
                quoteAt(
                        "600302",
                        "休市样本",
                        "通用设备",
                        Instant.parse("2026-07-11T02:59:00Z")
                )
        );
        eastMoneyClient.klines.put("600302", rightEarlyKLines("600302", "10.62", "180000"));
        eastMoneyClient.financials.put("600302", goodFinancial("600302"));

        ShortTermCandidate candidate = find(
                closedService.report(3, 50, 10, null,null, null, null, null, null, null),
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
                service.report(3, 50, 10, null,null, null, null, null, null, null),
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

        ShortTermReport report = service.report(3, 100, 10, null,null, null, null, null, null, null);
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

        ShortTermReport report = service.report(5, 100, 5, null,null, null, null, null, null, true);

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
        assertThat(candidate.score().supportReversalScore()).isEqualByComparingTo("0.00");
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
    void shouldExcludeQuotesAboveConfiguredEntryRiseLimitFromRecommendations() {
        eastMoneyClient.quotes = List.of(
                quote("600002", "急拉股份", "12.90", "6.60", "18.00", "1.80", "260000000")
        );
        eastMoneyClient.klines.put("600002", rightEarlyKLines("600002", "12.90", "420000"));
        eastMoneyClient.financials.put("600002", goodFinancial("600002"));

        ShortTermReport report = service.report(3, 100, 5, null,null, null, null, null, null, null);

        assertThat(report.ruleSet().maxEntryRisePercent()).isEqualByComparingTo("6.5");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("600002");
        assertThat(report.exclusions()).filteredOn(exclusion -> "600002".equals(exclusion.symbol()))
                .singleElement()
                .satisfies(exclusion -> {
                    assertThat(exclusion.category()).isEqualTo("CHASE_RISK");
                    assertThat(exclusion.reason()).contains("追涨上限", "6.5%");
                });
    }

    @Test
    void shouldExcludeDeclineBeyondTwoPercentBeforeTechnicalReview() {
        eastMoneyClient.quotes = List.of(
                quote("600009", "下跌候选", "14.96", "-2.35", "18.00", "1.60", "260000000")
        );
        eastMoneyClient.klines.put("600009", rightEarlyKLines("600009", "14.96", "420000"));
        eastMoneyClient.financials.put("600009", goodFinancial("600009"));

        ShortTermReport report = service.report(3, 100, 5, null,null, null, null, null, null, null);

        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("600009");
        assertThat(report.exclusions()).filteredOn(exclusion -> "600009".equals(exclusion.symbol()))
                .singleElement()
                .satisfies(exclusion -> {
                    assertThat(exclusion.category()).isEqualTo("DAILY_DECLINE_TOO_LARGE");
                    assertThat(exclusion.reason()).isEqualTo("当日跌幅超过承接观察上限");
                    assertThat(exclusion.evidence()).contains("当日涨跌幅", "-2.35%");
                });
        assertThat(eastMoneyClient.requestedKlineSymbols).doesNotContain("600009");
    }

    @Test
    void shouldKeepConfirmedLowerShadowSupportAsLightTrialCandidate() {
        Instant timestamp = Instant.parse("2026-07-07T06:49:00Z");
        eastMoneyClient.snapshotFetchedAt = timestamp;
        eastMoneyClient.quotes = List.of(
                withTurnover(quoteAt("600041", "承接股份", "通用设备", timestamp, "-1.00", "900000000"), "3.00"),
                withTurnover(quoteAt("600042", "同行上涨一", "通用设备", timestamp, "1.20", "800000000"), "3.00"),
                withTurnover(quoteAt("600043", "同行上涨二", "通用设备", timestamp, "0.80", "700000000"), "3.00")
        );
        eastMoneyClient.klines.put("600041", lowerShadowSupportKLines("600041"));
        eastMoneyClient.klines.put("600042", confirmedRightEarlyKLines("600042", "10.62", "230000"));
        eastMoneyClient.klines.put("600043", confirmedRightEarlyKLines("600043", "10.62", "230000"));
        eastMoneyClient.quotes.forEach(quote -> eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol())));
        eastMoneyClient.intraday.put("600041", confirmedTail("600041"));

        ShortTermReport report = serviceAt(Clock.fixed(Instant.parse("2026-07-07T06:49:30Z"), SHANGHAI))
                .report(8, 100, 8, null,null, null, null, null, null, null);
        ShortTermCandidate candidate = find(report, "600041");

        assertThat(candidate.action()).isEqualTo("SUPPORT_REVERSAL_LIGHT_TRIAL");
        assertThat(candidate.todayAdvice().action()).isEqualTo("LIGHT_TRIAL");
        assertThat(candidate.technical().supportReversal().confirmed()).isTrue();
        assertThat(candidate.score().supportReversalScore()).isGreaterThanOrEqualTo(new BigDecimal("70"));
        assertThat(candidate.entryRules()).anyMatch(rule -> rule.contains("承接低点") || rule.contains("收复支撑"));
    }

    @Test
    void shouldHideSlightDeclineWithoutConfirmedLowerShadowSupportAfterKlineReview() {
        Instant timestamp = Instant.parse("2026-07-07T06:54:00Z");
        eastMoneyClient.snapshotFetchedAt = timestamp;
        eastMoneyClient.quotes = List.of(
                withTurnover(quoteAt("600044", "普通微跌", "通用设备", timestamp, "-1.00", "900000000"), "3.00"),
                withTurnover(quoteAt("600045", "同行上涨一", "通用设备", timestamp, "1.20", "800000000"), "3.00"),
                withTurnover(quoteAt("600046", "同行上涨二", "通用设备", timestamp, "0.80", "700000000"), "3.00")
        );
        eastMoneyClient.klines.put("600044", rightEarlyKLines("600044", "10.62", "230000"));
        eastMoneyClient.klines.put("600045", confirmedRightEarlyKLines("600045", "10.62", "230000"));
        eastMoneyClient.klines.put("600046", confirmedRightEarlyKLines("600046", "10.62", "230000"));
        eastMoneyClient.quotes.forEach(quote -> eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol())));

        ShortTermReport report = serviceAt(Clock.fixed(Instant.parse("2026-07-07T06:55:00Z"), SHANGHAI))
                .report(8, 100, 8, null,null, null, null, null, null, null);

        assertThat(eastMoneyClient.requestedKlineSymbols).contains("600044");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("600044");
        assertThat(report.exclusions()).filteredOn(exclusion -> "600044".equals(exclusion.symbol()))
                .singleElement()
                .satisfies(exclusion -> assertThat(exclusion.category())
                        .isEqualTo("SUPPORT_REVERSAL_NOT_CONFIRMED"));
    }

    @Test
    void shouldKeepNonTopThreeIndustryStockAndApplySoftLeadershipPenalty() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry("600701", "行业龙头A", "光伏设备", "10.62", "1.20", "18.00", "1.60", "1800000000"),
                quoteWithIndustry("600702", "行业龙头B", "光伏设备", "10.62", "1.10", "18.00", "1.60", "1500000000"),
                quoteWithIndustry("600703", "行业龙头C", "光伏设备", "10.62", "1.00", "18.00", "1.60", "1200000000"),
                quoteWithIndustry("600704", "跟随样本", "光伏设备", "10.62", "0.90", "18.00", "1.60", "700000000")
        );
        eastMoneyClient.quotes.forEach(quote -> {
            eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "230000"));
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        });

        ShortTermReport report = service.report(4, 100, 5, null,null, null, null, null, null, null);

        assertThat(report.reviewedSymbols()).containsExactlyInAnyOrder("600701", "600702", "600703", "600704");
        assertThat(report.exclusions()).filteredOn(exclusion -> "600704".equals(exclusion.symbol())).isEmpty();
        assertThat(eastMoneyClient.requestedKlineSymbols).contains("600704");
        ShortTermCandidate follower = find(report, "600704");
        assertThat(follower.industryLeadership().amountRank()).isEqualTo(4);
        assertThat(follower.industryLeadership().contribution()).isNegative();
    }

    @Test
    void hotDirectionUsesFullQuoteUniverseBeforeLiquidityAndChaseFilters() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry("600711", "热点可交易", "新材料", "10.62", "1.20", "18", "1.6", "600000000"),
                quoteWithIndustry("600712", "热点低流动", "新材料", "10.62", "2.50", "18", "1.6", "1000000"),
                quoteWithIndustry("600713", "热点过热", "新材料", "10.62", "8.50", "18", "1.6", "800000000"),
                quoteWithIndustry("300711", "创业板背景样本", "新材料", "10.62", "2.10", "18", "1.6", "500000000")
        );
        eastMoneyClient.klines.put("600711", rightEarlyKLines("600711", "10.62", "180000"));
        eastMoneyClient.financials.put("600711", goodFinancial("600711"));

        ShortTermReport report = service.report(3, 100, 10, null,null, null, null, null, null, null);

        assertThat(report.hotDirections()).filteredOn(direction -> "新材料".equals(direction.label()))
                .singleElement().satisfies(direction -> assertThat(direction.sampleCount()).isEqualTo(4));
        assertThat(report.marketRegime().sampleCount()).isEqualTo(4);
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("300711");
        assertThat(find(report, "600711").score().marketHeatContribution()).isNotZero();
    }

    @Test
    void exposesVolatilityQualitySignalFamilyAndVisibleRankingContribution() {
        eastMoneyClient.quotes = List.of(
                quote("600715", "波动质量样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600715", confirmedRightEarlyKLines("600715", "10.62", "230000"));
        eastMoneyClient.financials.put("600715", goodFinancial("600715"));

        ShortTermCandidate candidate = find(
                service.report(3, 100, 10, null,null, null, null, null, null, null),
                "600715"
        );

        assertThat(candidate.volatilityQuality()).isNotNull();
        assertThat(candidate.signalProfile()).isNotNull();
        assertThat(candidate.signalProfile().activeFamilies()).isNotEmpty();
        assertThat(candidate.score().volatilityContribution()).isEqualByComparingTo(
                candidate.volatilityQuality().contribution()
        );
        assertThat(candidate.score().visibleRankingAdjustment()).isEqualByComparingTo(
                candidate.score().rankingScore().subtract(candidate.score().technicalRankingScore())
        );
    }

    @Test
    void repairMarketDowngradesWouldBeAddToRegimeLightTrial() {
        Instant timestamp = Instant.parse("2026-07-07T06:49:00Z");
        List<EastMoneyQuote> quotes = new ArrayList<>();
        quotes.add(withTurnover(quoteAt(
                "600716", "修复期右侧", "通用设备", timestamp, "1.60", "900000000"), "3.00"));
        for (int index = 0; index < 51; index++) {
            quotes.add(quoteAt(String.format("601%03d", index), "微涨" + index,
                    "普通行业", timestamp, "0.40", "1000000"));
        }
        for (int index = 0; index < 48; index++) {
            quotes.add(quoteAt(String.format("603%03d", index), "微跌" + index,
                    "普通行业", timestamp, "-0.30", "1000000"));
        }
        eastMoneyClient.quotes = quotes;
        eastMoneyClient.snapshotExpectedCount = quotes.size();
        eastMoneyClient.snapshotFetchedAt = timestamp;
        eastMoneyClient.klines.put("600716", confirmedRightEarlyKLines("600716", "10.62", "230000"));
        eastMoneyClient.financials.put("600716", goodFinancial("600716"));
        eastMoneyClient.intraday.put("600716", confirmedTail("600716"));

        ShortTermReport report = serviceAt(Clock.fixed(Instant.parse("2026-07-07T06:49:30Z"), SHANGHAI))
                .report(3, 100, 10, null,null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600716");
        assertThat(report.marketRegime().state()).isEqualTo("REPAIR");
        assertThat(candidate.action()).isEqualTo("REGIME_LIGHT_TRIAL");
        assertThat(candidate.todayAdvice().action()).isEqualTo("LIGHT_TRIAL");
        assertThat(candidate.todayAdvice().summary()).contains("修复", "轻仓");
    }

    @Test
    void reportsMarketAndTechnicalCoverageSeparatelyWithoutAllowingSubstitution() {
        eastMoneyClient.quotes = IntStream.range(0, 95)
                .mapToObj(index -> quoteWithIndustry(
                        String.format("600%03d", index), "样本" + index, "行业" + index,
                        "10.62", "1.20", "18", "1.6", "600000000"))
                .toList();
        eastMoneyClient.snapshotExpectedCount = 100;
        eastMoneyClient.quotes.forEach(quote ->
                eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "180000")));

        ShortTermReport report = service.report(
                new ShortTermScanRequest(3, 100, 10, null,null, null, null, null, null));

        assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("0.9500");
        assertThat(report.coverage().executionReliable()).isTrue();
        assertThat(report.technicalReviewCoverage().quotePreselectedCount()).isEqualTo(95);
        assertThat(report.technicalReviewCoverage().requestedCount()).isEqualTo(10);
        assertThat(report.technicalReviewCoverage().sufficientCount()).isEqualTo(10);
        assertThat(report.technicalReviewCoverage().coverageRatio()).isEqualByComparingTo("0.1053");
    }

    @Test
    void removesFutureKLinesFromBothTechnicalAndRelativeStrengthAnalysis() {
        eastMoneyClient.quotes = List.of(
                quote("600721", "点时样本", "10.62", "1.20", "18", "1.6", "600000000")
        );
        List<EastMoneyKLine> rows = new ArrayList<>(rightEarlyKLines("600721", "10.62", "180000"));
        rows.add(kline("600721", LocalDate.parse("2026-07-08"), new BigDecimal("88.00"), "999999"));
        eastMoneyClient.klines.put("600721", rows);
        eastMoneyClient.financials.put("600721", goodFinancial("600721"));

        ShortTermCandidate candidate = find(
                service.report(3, 100, 10, null,null, null, null, null, null, null),
                "600721"
        );

        assertThat(candidate.technical().tradeDate()).isBeforeOrEqualTo(LocalDate.parse("2026-07-07"));
        assertThat(candidate.relativeStrength().dataGaps()).anyMatch(gap -> gap.contains("未来K线"));
    }

    @Test
    void shouldWaitUntilActionableTailWindow() {
        eastMoneyClient.quotes = List.of(
                quote("600001", "右侧股份", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.intraday.put("600001", tailBeforeClosingAuction("600001"));

        ShortTermReport report = service.report(3, 100, 5, null,null, null, null, null, null, null);

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

        ShortTermReport report = postCloseService.report(3, 100, 5, null,null, null, null, null, null, null);

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

        ShortTermReport report = service.report(3, 100, 5, null,null, null, null, null, null, null);

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

        ShortTermReport report = service.report(5, 100, 5, null,null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600010");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).contains("600010");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::symbol).doesNotContain("600010");
        assertThat(candidate.score().marketHeatScore()).isGreaterThan(new BigDecimal("60"));
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

        ShortTermReport report = service.report(3, 100, 5, null,null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600020");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::symbol).doesNotContain("600020");
        assertThat(candidate.peTtm()).isEqualByComparingTo("300.00");
        assertThat(candidate.risks()).isNotNull();
    }

    @Test
    void shouldExposeApprovedCoreSignalWeights() {
        eastMoneyClient.quotes = List.of(
                quote("600021", "权重样本", "10.62", "1.60", "18.00", "1.60", "900000000")
        );
        eastMoneyClient.klines.put("600021", rightEarlyKLines("600021", "10.62", "230000"));
        eastMoneyClient.financials.put("600021", goodFinancial("600021"));

        ShortTermReport report = service.report(3, 100, 5, null,null, null, null, null, null, null);

        assertThat(report.weightProfile().preliminaryTotal()).isEqualByComparingTo("1.00");
        assertThat(report.weightProfile().finalTotal()).isEqualByComparingTo("1.00");
        assertThat(report.weightProfile().preliminaryLiquidity()).isEqualByComparingTo("0.35");
        assertThat(report.weightProfile().preliminaryNonChase()).isEqualByComparingTo("0.25");
        assertThat(report.weightProfile().preliminaryHeat()).isEqualByComparingTo("0.40");
        assertThat(report.weightProfile().finalGoldenCross()).isEqualByComparingTo("0.60");
        assertThat(report.weightProfile().finalVolume()).isEqualByComparingTo("0.24");
        assertThat(report.weightProfile().finalTurnover()).isEqualByComparingTo("0.10");
        assertThat(report.weightProfile().finalCloseStrength()).isEqualByComparingTo("0.06");
    }

    @Test
    void shouldReturnEightCandidatesByDefaultWithoutManufacturingExecutableAdvice() {
        eastMoneyClient.quotes = IntStream.range(0, 10)
                .mapToObj(index -> withTurnover(
                        quoteWithIndustry(
                                String.format("600%03d", 100 + index),
                                "八只候选" + index,
                                "行业" + index,
                                "10.62",
                                "1.60",
                                "18.00",
                                "1.60",
                                "900000000"
                        ),
                        "3.00"
                ))
                .toList();
        eastMoneyClient.quotes.forEach(quote -> {
            eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "230000"));
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        });

        ShortTermReport report = service.report(null, 100, 20, null,null, null, null, null, null, null);

        assertThat(report.candidates()).hasSize(8);
        assertThat(report.candidates()).allSatisfy(candidate ->
                assertThat(candidate.todayAdvice().action()).isNotBlank());
    }

    @Test
    void shouldKeepValuationOutOfTheCoreShortTermScore() {
        eastMoneyClient.quotes = List.of(
                withTurnover(quote("600031", "低估值样本", "10.62", "1.60", "12", "1.2", "900000000"), "3.00"),
                withTurnover(quote("600032", "高估值样本", "10.62", "1.60", "300", "45", "900000000"), "3.00")
        );
        eastMoneyClient.quotes.forEach(quote -> {
            eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "230000"));
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        });

        ShortTermReport report = service.report(2, 100, 10, null,null, null, null, null, null, null);

        ShortTermCandidate lowValuation = find(report, "600031");
        ShortTermCandidate highValuation = find(report, "600032");
        assertThat(lowValuation.score().finalScore()).isEqualByComparingTo(highValuation.score().finalScore());
        assertThat(lowValuation.score().goldenCrossScore()).isEqualByComparingTo(highValuation.score().goldenCrossScore());
        assertThat(lowValuation.score().turnoverScore()).isEqualByComparingTo(highValuation.score().turnoverScore());
    }

    @Test
    void shouldDowngradeAnExtremeUpperShadowToObservation() {
        EastMoneyQuote quote = withTurnover(
                quote("600033", "长上影样本", "10.62", "1.60", "18", "1.6", "900000000"),
                "3.00"
        );
        eastMoneyClient.quotes = List.of(quote);
        eastMoneyClient.klines.put("600033", longUpperShadowKLines("600033"));
        eastMoneyClient.financials.put("600033", goodFinancial("600033"));

        ShortTermCandidate candidate = find(
                service.report(1, 100, 10, null,null, null, null, null, null, null),
                "600033"
        );

        assertThat(candidate.technical().momentumQuality().extremeUpperShadow()).isTrue();
        assertThat(candidate.action()).isNotEqualTo("RIGHT_EARLY_ADD");
        assertThat(candidate.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
        assertThat(candidate.risks()).anySatisfy(item -> assertThat(item).contains("长上影", "观察"));
        assertThat(candidate.evidence()).extracting(ShortTermEvidence::title)
                .contains("换手适配", "K线收盘强度");
    }

    @Test
    void shouldHideHardRiskCandidatesAndWriteTheirReasonsToTheExclusionAudit() {
        EastMoneyQuote eligible = quoteWithIndustry("600034", "合格样本", "智能制造", "10.62", "1.60", "18", "1.6", "900000000");
        EastMoneyQuote financialRisk = quoteWithIndustry("600035", "财务红旗", "工业软件", "10.62", "1.60", "18", "1.6", "900000000");
        EastMoneyQuote volumeMissing = quoteWithIndustry("600036", "量能缺失", "机器人", "10.62", "1.60", "18", "1.6", "900000000");
        EastMoneyQuote noGoldenCross = quoteWithIndustry("600037", "无金叉", "电力设备", "9.60", "1.60", "18", "1.6", "900000000");
        eastMoneyClient.quotes = List.of(eligible, financialRisk, volumeMissing, noGoldenCross);
        eastMoneyClient.klines.put("600034", rightEarlyKLines("600034", "10.62", "230000"));
        eastMoneyClient.klines.put("600035", rightEarlyKLines("600035", "10.62", "230000"));
        eastMoneyClient.klines.put("600036", latestVolumeMissingKLines("600036"));
        eastMoneyClient.klines.put("600037", belowMa20KLines("600037"));
        eastMoneyClient.financials.put("600034", goodFinancial("600034"));
        eastMoneyClient.financials.put("600035", badFinancial("600035"));
        eastMoneyClient.financials.put("600036", goodFinancial("600036"));
        eastMoneyClient.financials.put("600037", goodFinancial("600037"));

        ShortTermReport report = service.report(8, 100, 20, null,null, null, null, null, null, null);

        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .contains("600034")
                .doesNotContain("600035", "600036", "600037");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::category)
                .contains("FINANCIAL_HARD_RISK", "VOLUME_DATA_MISSING", "GOLDEN_CROSS_UNAVAILABLE");
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

        ShortTermReport report = service.report(3, 100, 5, null,null, null, null, null, null, null);

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

        ShortTermReport report = service.report(3, 100, 5, null,null, null, null, null, null, null);

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

        ShortTermReport report = service.report(2, 100, 10, null,null, null, null, null, null, null);

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

        ShortTermReport report = service.report(2, 100, 10, null,null, null, null, null, null, null);

        ShortTermCandidate approaching = find(report, "600019");
        assertThat(approaching.technical().goldenCross().state()).isEqualTo("APPROACHING");
        assertThat(approaching.technical().rightSideSignal()).isIn("右侧雏形", "右侧已拉开");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
                .containsExactly("600020", "600019");
    }

    @Test
    void shouldReportAllSuccessfullyReviewedKLinesBeforeCandidateTruncation() {
        List<EastMoneyQuote> quotes = IntStream.range(0, 35)
                .mapToObj(index -> quoteWithIndustry(
                        String.format("60%04d", 700 + index),
                        "复核样本" + index,
                        "复核行业" + index,
                        "10.62",
                        "1.20",
                        "18",
                        "1.6",
                        "600000000"
                ))
                .toList();
        eastMoneyClient.quotes = quotes;
        quotes.forEach(quote -> {
            eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "180000"));
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        });

        ShortTermReport report = service.report(3, 100, 35, null,null, null, null, null, null, null);

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
                service.report(3, 100, 5, null,null, null, null, null, null, null),
                "600016"
        );

        assertThat(candidate.technical().goldenCross().state()).isEqualTo("ESTABLISHED");
        assertThat(candidate.tailSignal().status()).isEqualTo("WATCH");
        assertThat(candidate.action()).isEqualTo("WAIT_CONFIRM");
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

        ShortTermReport report = service.report(3, 100, 5, null,null, null, null, null, null, null);

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

        ShortTermReport report = service.report(3, 100, 5, null,null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600014");
        assertThat(candidate.tailSignal().tailAmountRatioPercent()).isLessThan(new BigDecimal("6.00"));
        assertThat(candidate.tailSignal().status()).isEqualTo("CONFIRMED");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(candidate.todayAdvice().summary()).contains("研究", "不可新建");
    }

    @Test
    void shouldFailInsteadOfReturningEmptyCandidatesWhenRealtimeQuotesUnavailable() {
        eastMoneyClient.quoteFailure = new IllegalStateException("实时行情超时");

        assertThatThrownBy(() -> service.report(3, 100, 5, null,null, null, null, null, null, null))
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

        ShortTermReport report = service.report(3, 50, 10, null,null, null, null, null, null, null);

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
                new BigDecimal("6.5"),
                new BigDecimal("7.0"),
                new BigDecimal("2.5"),
                new BigDecimal("6.5"),
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

    private List<EastMoneyKLine> endingOn(List<EastMoneyKLine> rows, LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(rows.size() - 1L);
        return IntStream.range(0, rows.size())
                .mapToObj(index -> {
                    EastMoneyKLine row = rows.get(index);
                    return new EastMoneyKLine(
                            row.symbol(),
                            startDate.plusDays(index),
                            row.open(),
                            row.close(),
                            row.high(),
                            row.low(),
                            row.volume(),
                            row.amount(),
                            row.turnoverRate()
                    );
                })
                .toList();
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

    private List<EastMoneyKLine> longUpperShadowKLines(String symbol) {
        List<EastMoneyKLine> rows = new ArrayList<>(rightEarlyKLines(symbol, "10.62", "230000"));
        EastMoneyKLine latest = rows.get(rows.size() - 1);
        rows.set(rows.size() - 1, new EastMoneyKLine(
                symbol,
                latest.tradeDate(),
                new BigDecimal("10.55"),
                new BigDecimal("10.62"),
                new BigDecimal("12.20"),
                new BigDecimal("10.50"),
                latest.volume(),
                latest.amount()
        ));
        return rows;
    }

    private List<EastMoneyKLine> lowerShadowSupportKLines(String symbol) {
        List<EastMoneyKLine> rows = new ArrayList<>(rightEarlyKLines(symbol, "10.62", "230000"));
        EastMoneyKLine latest = rows.get(rows.size() - 1);
        rows.set(rows.size() - 1, new EastMoneyKLine(
                symbol,
                latest.tradeDate(),
                new BigDecimal("10.70"),
                new BigDecimal("10.62"),
                new BigDecimal("10.72"),
                new BigDecimal("10.18"),
                latest.volume(),
                latest.amount()
        ));
        return rows;
    }

    private List<EastMoneyKLine> latestVolumeMissingKLines(String symbol) {
        List<EastMoneyKLine> rows = new ArrayList<>(rightEarlyKLines(symbol, "10.62", "230000"));
        EastMoneyKLine latest = rows.get(rows.size() - 1);
        rows.set(rows.size() - 1, new EastMoneyKLine(
                symbol,
                latest.tradeDate(),
                latest.open(),
                latest.close(),
                latest.high(),
                latest.low(),
                null,
                latest.amount()
        ));
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
                new BigDecimal("3.00"),
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

    private EastMoneyQuote withTurnover(EastMoneyQuote quote, String turnoverRate) {
        return new EastMoneyQuote(
                quote.symbol(),
                quote.name(),
                quote.market(),
                quote.industry(),
                quote.latestPrice(),
                quote.changePercent(),
                new BigDecimal(turnoverRate),
                quote.volume(),
                quote.amount(),
                quote.peRatio(),
                quote.pbRatio(),
                quote.peTtm(),
                quote.sourceName(),
                quote.quoteUrl(),
                quote.fetchedAt(),
                quote.tradeDate(),
                quote.marketTimestamp()
        );
    }

    private EastMoneyQuote withVolume(EastMoneyQuote quote, String volume) {
        return new EastMoneyQuote(
                quote.symbol(),
                quote.name(),
                quote.market(),
                quote.industry(),
                quote.latestPrice(),
                quote.changePercent(),
                quote.turnoverRate(),
                new BigDecimal(volume),
                quote.amount(),
                quote.peRatio(),
                quote.pbRatio(),
                quote.peTtm(),
                quote.sourceName(),
                quote.quoteUrl(),
                quote.fetchedAt(),
                quote.tradeDate(),
                quote.marketTimestamp()
        );
    }

    private EastMoneyFundFlowSnapshot fundFlow(
            String symbol,
            String mainRatio,
            String superLargeRatio,
            String largeRatio
    ) {
        return new EastMoneyFundFlowSnapshot(
                symbol,
                "样本" + symbol,
                new BigDecimal("100000000"),
                new BigDecimal("60000000"),
                new BigDecimal("40000000"),
                BigDecimal.ZERO,
                new BigDecimal("-100000000"),
                new BigDecimal(mainRatio),
                new BigDecimal(superLargeRatio),
                new BigDecimal(largeRatio),
                BigDecimal.ZERO,
                new BigDecimal("-10"),
                "东方财富资金流",
                "https://quote.eastmoney.com/sh" + symbol + ".html",
                Instant.parse("2026-07-07T07:01:00Z"),
                LocalDate.parse("2026-07-07"),
                Instant.parse("2026-07-07T07:00:00Z")
        );
    }

    private EastMoneyIndustryFundFlowSnapshot industryFundFlow(
            String code,
            String name,
            String mainNetInflow,
            String mainNetInflowRatio,
            int advancing,
            int declining
    ) {
        return new EastMoneyIndustryFundFlowSnapshot(
                code,
                name,
                new BigDecimal(mainNetInflow),
                new BigDecimal(mainNetInflowRatio),
                new BigDecimal("60000000"),
                new BigDecimal("1.5"),
                new BigDecimal("40000000"),
                new BigDecimal("1.0"),
                advancing,
                declining,
                advancing + declining,
                "东方财富行业资金流",
                "https://push2delay.eastmoney.com/api/qt/clist/get",
                Instant.parse("2026-07-07T07:01:00Z"),
                LocalDate.parse("2026-07-07"),
                Instant.parse("2026-07-07T07:00:00Z")
        );
    }

    private EastMoneyQuote quoteAt(
            String symbol,
            String name,
            String industry,
            Instant marketTimestamp
    ) {
        return quoteAt(symbol, name, industry, marketTimestamp, "1.20", "600000000");
    }

    private EastMoneyQuote quoteAt(
            String symbol,
            String name,
            String industry,
            Instant marketTimestamp,
            String changePercent,
            String amount
    ) {
        return new EastMoneyQuote(
                symbol,
                name,
                "上交所",
                industry,
                new BigDecimal("10.62"),
                new BigDecimal(changePercent),
                BigDecimal.ONE,
                new BigDecimal("100000"),
                new BigDecimal(amount),
                new BigDecimal("18"),
                new BigDecimal("1.60"),
                new BigDecimal("18"),
                "测试行情",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-07-07T06:49:30Z"),
                marketTimestamp == null ? null : marketTimestamp.atZone(SHANGHAI).toLocalDate(),
                marketTimestamp
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

    private List<EastMoneyAnnualIndicator> badFinancial(String symbol) {
        return List.of(
                new EastMoneyAnnualIndicator(symbol, "样本", "2025-12-31", "年报", new BigDecimal("-0.0500"),
                        new BigDecimal("-0.20"), new BigDecimal("0.0800"), new BigDecimal("-0.1000"),
                        new BigDecimal("-0.5000"), BigDecimal.ONE, new BigDecimal("1.00"))
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
                    tradeDate.atTime(14, 45).plusMinutes(index),
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
        points.add(intraday(symbol, "2026-07-07T14:49", "10.52", "10.54", "10.55", "10.51", "36000", "38000000", "10.50"));
        points.add(intraday(symbol, "2026-07-07T14:50", "10.54", "10.55", "10.56", "10.53", "36000", "39000000", "10.50"));
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
        private boolean snapshotHasReportedTotal = true;
        private String snapshotSource = "测试行情";
        private Instant snapshotFetchedAt = Instant.parse("2026-07-07T06:59:00Z");
        private Runnable afterQuoteSnapshotFetched = () -> {
        };
        private Set<String> unstableIndustrySymbols = Set.of();
        private final List<String> requestedKlineSymbols = Collections.synchronizedList(new ArrayList<>());
        private final List<String> requestedFinancialSymbols = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, List<EastMoneyKLine>> klines = new HashMap<>();
        private final Map<String, List<EastMoneyAnnualIndicator>> financials = new HashMap<>();
        private final Map<String, List<EastMoneyIntradayPoint>> intraday = new HashMap<>();
        private final Map<String, EastMoneyFundFlowSnapshot> fundFlows = new HashMap<>();
        private List<EastMoneyIndustryFundFlowSnapshot> industryFundFlows = List.of();
        private RuntimeException industryFundFlowFailure;
        private int industryFundFlowCalls;
        private int fundFlowBatchCalls;
        private int turnoverEnrichmentCalls;
        private List<String> requestedFundFlowSymbols = List.of();

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
            int expected = snapshotHasReportedTotal
                    ? (snapshotExpectedCount > 0 ? snapshotExpectedCount : fetched)
                    : 0;
            AshareQuoteSnapshot snapshot = new AshareQuoteSnapshot(
                    quotes.stream().limit(limit).toList(),
                    limit,
                    expected,
                    fetched,
                    Math.max(0, expected - fetched),
                    snapshotHasReportedTotal && snapshotComplete,
                    snapshotSource,
                    snapshotFetchedAt
            );
            afterQuoteSnapshotFetched.run();
            return snapshot;
        }

        @Override
        public List<EastMoneyKLine> fetchDailyKLines(String symbol, LocalDate begin, LocalDate end) {
            requestedKlineSymbols.add(symbol);
            return klines.getOrDefault(symbol, List.of());
        }

        @Override
        public List<EastMoneyKLine> enrichDailyKLineTurnover(
                String symbol,
                LocalDate begin,
                LocalDate end,
                List<EastMoneyKLine> canonicalRows
        ) {
            turnoverEnrichmentCalls++;
            return canonicalRows;
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

        @Override
        public Map<String, EastMoneyFundFlowSnapshot> fetchFundFlowSnapshots(List<String> symbols) {
            fundFlowBatchCalls++;
            requestedFundFlowSymbols = List.copyOf(symbols);
            Map<String, EastMoneyFundFlowSnapshot> result = new HashMap<>();
            symbols.forEach(symbol -> {
                EastMoneyFundFlowSnapshot snapshot = fundFlows.get(symbol);
                if (snapshot != null) {
                    result.put(symbol, snapshot);
                }
            });
            return result;
        }

        @Override
        public List<EastMoneyIndustryFundFlowSnapshot> fetchIndustryFundFlows() {
            industryFundFlowCalls++;
            if (industryFundFlowFailure != null) {
                throw industryFundFlowFailure;
            }
            return industryFundFlows;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
