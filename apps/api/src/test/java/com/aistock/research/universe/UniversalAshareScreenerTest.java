package com.aistock.research.universe;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.valuation.ValuationContextState;
import com.aistock.research.valuation.ValuationModel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UniversalAshareScreenerTest {

    private final StubEastMoneyClient client = new StubEastMoneyClient();
    private final UniversalAshareScreener screener = new UniversalAshareScreener(client);

    @Test
    void usesOnlyCompletedAnnualReportYears() {
        assertThat(UniversalAshareScreener.latestCompletedAnnualReportYear(LocalDate.of(2026, 4, 30)))
                .isEqualTo(2024);
        assertThat(UniversalAshareScreener.latestCompletedAnnualReportYear(LocalDate.of(2026, 5, 1)))
                .isEqualTo(2025);
    }

    @Test
    void recordsEmptyLatestAnnualReportYearWithoutPromotingOlderDataToLatest() {
        client.baseQuotes = List.of(
                quote("600987", "航民股份", "纺织制造", "7.20", "0.20", "11.80", "1.02", "190000000")
        );
        client.tencentQuotes = client.baseQuotes;
        client.annualIndicatorsByYear = Map.of(
                2024,
                Map.of("600987", annualIndicator("600987", 2024))
        );

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                3, 50, null, null, null, null, false, true, "VALUE"));

        UniversalScreenCandidate candidate = find(report, "600987");
        assertThat(candidate.longTermAssessment().financialQuality().sampleYears()).isEqualTo(1);
        assertThat(candidate.longTermAssessment().dataGaps())
                .anyMatch(gap -> gap.contains("2025 年年报指标返回空集合"));
        assertThat(candidate.dataGaps())
                .anyMatch(gap -> gap.contains("最新年报 ROE"));
    }

    @Test
    void shouldApplyShortTermLiquidityAndSidewaysGatesWithoutRequiringPositivePe() {
        client.baseQuotes = List.of(
                quote("600036", "招商银行", "银行", "36.80", "-1.00", "6.00", "0.85", "900000000"),
                quote("000777", "朋友推荐", "机械设备", "18.00", "-0.20", "16.00", "1.80", "260000000"),
                quote("000002", "*ST样本", "房地产", "1.20", "-1.00", "8.00", "0.70", "120000000"),
                quote("300001", "亏损样本", "软件", "12.00", "0.10", "-4.00", "3.00", "180000000"),
                quote("002001", "低流动样本", "消费", "8.00", "0.20", "12.00", "1.00", "30000000"),
                quote("600777", "横盘样本", "公用事业", "9.00", "0.00", "10.00", "0.90", "160000000")
        );
        client.tencentQuotes = client.baseQuotes;
        client.sidewaysSymbols = Set.of("600777");

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                10,
                100,
                null,
                null,
                null,
                null,
                true,
                true,
                "SHORT_TERM"
        ));

        assertThat(report.stageStats()).extracting(UniversalScreenStageStats::stage)
                .contains("UNIVERSE", "TRADABLE", "MODE_ELIGIBILITY", "LIQUIDITY", "SIDEWAYS", "FINAL");
        assertThat(report.candidates()).extracting(UniversalScreenCandidate::symbol)
                .contains("000777", "600036", "300001")
                .doesNotContain("000002", "002001", "600777");
        assertThat(find(report, "000777").marketTimestamp())
                .isEqualTo(Instant.parse("2026-07-08T07:30:00Z"));
        assertThat(report.exclusionsSample()).extracting(UniversalScreenExclusion::stage)
                .contains("TRADABLE", "LIQUIDITY", "SIDEWAYS");
    }

    @Test
    void shouldResolveDefaultRulesWithoutTurningProxyFinancialScoresIntoBuyActions() {
        client.baseQuotes = List.of(
                quote("000777", "朋友推荐", "机械设备", "18.00", "-0.20", "16.00", "1.80", "260000000")
        );
        client.tencentQuotes = client.baseQuotes;

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertThat(report.ruleSet().scanLimit()).isEqualTo(6000);
        assertThat(report.ruleSet().limit()).isEqualTo(3);
        assertThat(report.ruleSet().minAmount()).isEqualByComparingTo("80000000");
        assertThat(report.ruleSet().excludeSideways()).isFalse();
        assertThat(report.candidates()).allSatisfy(candidate ->
                assertThat(candidate.action()).isNotEqualTo("ACCUMULATE"));
    }

    @Test
    void valueModeDefaultsToTwelveCandidatesWithoutChangingOtherModes() {
        client.baseQuotes = List.of(
                quote("000777", "朋友推荐", "机械设备", "18.00", "-0.20", "16.00", "1.80", "260000000")
        );
        client.tencentQuotes = client.baseQuotes;

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                null, 50, null, null, null, null, false, true, "VALUE"
        ));

        assertThat(report.ruleSet().limit()).isEqualTo(12);
        assertThat(report.candidates()).allSatisfy(candidate ->
                assertThat(candidate.action()).isNotEqualTo("ACCUMULATE"));
    }

    @Test
    void shouldKeepPriceAndMarketTimestampFromTheSameQuoteSource() {
        EastMoneyQuote base = quote(
                "600036", "招商银行", "银行", "36.80", "-0.20", "6.00", "0.85", "900000000");
        EastMoneyQuote realtimeWithoutPrice = new EastMoneyQuote(
                "600036", "招商银行", "上交所", "银行", null, new BigDecimal("-0.10"),
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("910000000"),
                new BigDecimal("6.00"), new BigDecimal("0.85"), new BigDecimal("6.00"),
                "腾讯行情", "https://quote.example.com/600036",
                Instant.parse("2026-07-08T07:31:01Z"), LocalDate.parse("2026-07-08"),
                Instant.parse("2026-07-08T07:31:00Z"));
        client.baseQuotes = List.of(base);
        client.tencentQuotes = List.of(realtimeWithoutPrice);

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                3, 50, null, null, null, null, false, true, "VALUE"));

        UniversalScreenCandidate candidate = find(report, "600036");
        assertThat(candidate.latestPrice()).isEqualByComparingTo("36.80");
        assertThat(candidate.sourceName()).isEqualTo(base.sourceName());
        assertThat(candidate.quoteUrl()).isEqualTo(base.quoteUrl());
        assertThat(candidate.fetchedAt()).isEqualTo(base.fetchedAt());
        assertThat(candidate.tradeDate()).isEqualTo(base.tradeDate());
        assertThat(candidate.marketTimestamp()).isEqualTo(base.marketTimestamp());
    }

    @Test
    void controllerShouldPassQueryParametersToScreener() {
        client.baseQuotes = List.of(
                quote("000777", "朋友推荐", "机械设备", "18.00", "-0.20", "16.00", "1.80", "260000000")
        );
        client.tencentQuotes = client.baseQuotes;
        UniversalScreenController controller = new UniversalScreenController(screener);

        UniversalScreenReport report = controller.report(
                8,
                300,
                new BigDecimal("120000000"),
                new BigDecimal("28"),
                new BigDecimal("3.50"),
                new BigDecimal("52"),
                false,
                false,
                "VALUE"
        );

        assertThat(report.ruleSet().limit()).isEqualTo(8);
        assertThat(report.ruleSet().scanLimit()).isEqualTo(300);
        assertThat(report.ruleSet().minAmount()).isEqualByComparingTo("120000000");
        assertThat(report.ruleSet().excludeSideways()).isFalse();
        assertThat(report.ruleSet().includeNorthExchange()).isFalse();
        assertThat(report.ruleSet().mode()).isEqualTo("VALUE");
    }

    @Test
    void shouldLimitSidewaysKlineReviewToFrontRankedWindow() {
        client.baseQuotes = IntStream.range(0, 240)
                .mapToObj(index -> quote(
                        String.format("60%04d", index),
                        "样本" + index,
                        "制造",
                        "10.00",
                        "-0.20",
                        "12.00",
                        "1.20",
                        "200000000"
                ))
                .toList();
        client.tencentQuotes = client.baseQuotes;

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                10,
                240,
                null,
                null,
                null,
                null,
                true,
                true,
                "SHORT_TERM"
        ));

        assertThat(report.candidates()).hasSize(10);
        assertThat(client.klineRequestCount).isLessThanOrEqualTo(12);
        assertThat(client.tencentRequestSymbolCount).isLessThanOrEqualTo(80);
        UniversalScreenStageStats deepReview = stage(report, "DEEP_REVIEW");
        assertThat(deepReview.inputCount()).isEqualTo(240);
        assertThat(deepReview.passedCount()).isEqualTo(client.klineRequestCount);
        assertThat(deepReview.deferredCount()).isEqualTo(240 - client.klineRequestCount);
        assertThat(report.stageStats()).allSatisfy(item ->
                assertThat(item.inputCount()).isEqualTo(
                        item.passedCount() + item.excludedCount() + item.deferredCount()
                ));
    }

    @Test
    void shouldFailFastWhenRealtimeQuotePoolBlocks() {
        StubEastMoneyClient slowClient = new StubEastMoneyClient();
        slowClient.fetchAshareDelayMillis = 600;
        UniversalAshareScreener fastFailScreener = new UniversalAshareScreener(slowClient, Duration.ofMillis(120));

        long started = System.nanoTime();

        assertThatThrownBy(() -> fastFailScreener.screen(new UniversalScreenRequest(
                10,
                240,
                null,
                null,
                null,
                null,
                true,
                true,
                "ALL"
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("全市场实时行情加载超过");

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(elapsedMillis).isLessThan(500);
    }

    @Test
    void shouldExplainZeroRealtimeAmountWhenLiquidityGateEmptiesPool() {
        client.baseQuotes = List.of(
                quote("600036", "招商银行", "银行", "37.94", "0.00", "6.35", "0.84", "0"),
                quote("000100", "TCL科技", "电子", "5.04", "0.00", "20.72", "1.72", "0")
        );
        client.tencentQuotes = client.baseQuotes;

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                10,
                100,
                null,
                null,
                null,
                null,
                true,
                true,
                "SHORT_TERM"
        ));

        assertThat(report.candidates()).isEmpty();
        assertThat(report.quoteNote()).contains("实时成交额为 0");
    }

    @Test
    void shouldExposePartialCoverageInsteadOfClaimingFullMarketCompletion() {
        client.baseQuotes = List.of(
                quote("600036", "招商银行", "银行", "37.94", "0.00", "6.35", "0.84", "900000000"),
                quote("000100", "TCL科技", "电子", "5.04", "0.00", "20.72", "1.72", "800000000")
        );
        client.tencentQuotes = client.baseQuotes;
        client.expectedCount = 100;

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                10, 100, null, null, null, null, false, true, "ALL"
        ));

        assertThat(report.coverage().requestedCount()).isEqualTo(100);
        assertThat(report.coverage().expectedCount()).isEqualTo(100);
        assertThat(report.coverage().fetchedCount()).isEqualTo(2);
        assertThat(report.coverage().missingCount()).isEqualTo(98);
        assertThat(report.coverage().complete()).isFalse();
        assertThat(report.quoteNote()).contains("部分覆盖");
    }

    @Test
    void allModeKeepsTradableInventoryWithoutProfitLiquidityOrSidewaysHardGates() {
        client.baseQuotes = List.of(
                quote("300001", "亏损横盘样本", "软件", "12.00", "0.00", "-4.00", "3.00", "20000000")
        );
        client.tencentQuotes = client.baseQuotes;
        client.sidewaysSymbols = Set.of("300001");

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                10, 50, null, null, null, null, true, true, "ALL"
        ));

        UniversalScreenCandidate candidate = find(report, "300001");
        assertThat(candidate.action()).isEqualTo("ELIGIBLE");
        assertThat(report.ruleSet().excludeSideways()).isFalse();
        assertThat(client.klineRequestCount).isZero();
    }

    @Test
    void valueModeKeepsNegativePeCycleCompanyForNormalizedResearch() {
        client.baseQuotes = List.of(
                quote("002714", "周期龙头", "生猪养殖", "36.00", "0.00", "-8.00", "3.20", "900000000")
        );
        client.tencentQuotes = client.baseQuotes;

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                10,
                50,
                null,
                new BigDecimal("45"),
                new BigDecimal("6"),
                null,
                true,
                true,
                "VALUE"
        ));

        UniversalScreenCandidate candidate = find(report, "002714");
        assertThat(candidate.peTtm()).isEqualByComparingTo("-8.00");
        assertThat(candidate.valuationContext().state()).isEqualTo(ValuationContextState.DISTORTED);
        assertThat(candidate.valuationContext().applicableModel()).isEqualTo(ValuationModel.CYCLICAL);
        assertThat(candidate.action()).isEqualTo("NORMALIZED_CYCLE_RESEARCH");
        assertThat(report.exclusionsSample()).extracting(UniversalScreenExclusion::symbol).doesNotContain("002714");
        assertThat(report.ruleSet().excludeSideways()).isFalse();
        assertThat(client.klineRequestCount).isZero();
    }

    @Test
    void valueModeDoesNotUseReferenceBandsAsEligibilityCliffs() {
        client.baseQuotes = List.of(
                quote("600901", "高估值质量研究", "软件", "80.00", "0.20", "180.00", "20.00", "900000000")
        );
        client.tencentQuotes = client.baseQuotes;

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                10,
                50,
                null,
                new BigDecimal("45"),
                new BigDecimal("6"),
                null,
                false,
                true,
                "VALUE"
        ));

        UniversalScreenCandidate candidate = find(report, "600901");
        assertThat(candidate.valuationContext().state()).isEqualTo(ValuationContextState.STRETCHED);
        assertThat(candidate.risks()).anySatisfy(item -> assertThat(item).contains("参考"));
        assertThat(report.exclusionsSample()).extracting(UniversalScreenExclusion::symbol).doesNotContain("600901");
    }

    @Test
    void qualityProxyDoesNotDoubleCountPeAndPb() {
        client.baseQuotes = List.of(
                quote("600911", "低倍数样本", "软件", "10.00", "0.10", "8.00", "0.80", "900000000"),
                quote("600912", "高倍数样本", "软件", "10.00", "0.10", "180.00", "20.00", "900000000")
        );
        client.tencentQuotes = client.baseQuotes;

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                10, 50, null, null, null, null, false, true, "VALUE"
        ));

        assertThat(find(report, "600911").score().financialScore())
                .isEqualByComparingTo(find(report, "600912").score().financialScore());
    }

    @Test
    void missingFinancialHistoryIsDowngradedByTheApplicableIndustryTemplate() {
        client.baseQuotes = List.of(
                quote("600036", "银行样本", "银行", "10.00", "0.10", "8.00", "0.80", "900000000"),
                quote("600912", "软件样本", "软件", "10.00", "0.10", "80.00", "8.00", "900000000")
        );
        client.tencentQuotes = client.baseQuotes;

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                10, 50, null, null, null, null, false, true, "VALUE"
        ));

        assertThat(find(report, "600036").score().financialScore()).isLessThanOrEqualTo(new BigDecimal("20"));
        assertThat(find(report, "600912").score().financialScore()).isLessThanOrEqualTo(new BigDecimal("20"));
        assertThat(report.candidates()).allSatisfy(candidate ->
                assertThat(candidate.longTermAssessment().financialQuality().status()).isEqualTo("INSUFFICIENT"));
        assertThat(report.candidates()).allMatch(candidate -> !"ACCUMULATE".equals(candidate.action()));
    }

    @Test
    void cycleModeAllowsNegativePeForCycleIndustriesAndRejectsNonCycleIndustries() {
        client.baseQuotes = List.of(
                quote("002772", "周期样本", "农业", "10.00", "0.00", "-2.00", "1.20", "200000000"),
                quote("600003", "普通样本", "软件", "10.00", "0.00", "20.00", "2.00", "200000000")
        );
        client.tencentQuotes = client.baseQuotes;

        UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
                10, 50, null, null, null, null, true, true, "CYCLE"
        ));

        UniversalScreenCandidate candidate = find(report, "002772");
        assertThat(candidate.action()).isEqualTo("CYCLE_RESEARCH");
        assertThat(report.candidates()).extracting(UniversalScreenCandidate::symbol).doesNotContain("600003");
    }



    private EastMoneyQuote quote(
            String symbol,
            String name,
            String industry,
            String latestPrice,
            String changePercent,
            String peTtm,
            String pbRatio,
            String amount
    ) {
        return new EastMoneyQuote(
                symbol,
                name,
                symbol.startsWith("6") ? "上交所" : "深交所",
                industry,
                decimal(latestPrice),
                decimal(changePercent),
                BigDecimal.ONE,
                BigDecimal.ONE,
                decimal(amount),
                decimal(peTtm),
                decimal(pbRatio),
                decimal(peTtm),
                "测试行情",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-07-08T07:30:01Z"),
                LocalDate.parse("2026-07-08"),
                Instant.parse("2026-07-08T07:30:00Z")
        );
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private UniversalScreenCandidate find(UniversalScreenReport report, String symbol) {
        return report.candidates().stream()
                .filter(candidate -> symbol.equals(candidate.symbol()))
                .findFirst()
                .orElseThrow();
    }

    private UniversalScreenStageStats stage(UniversalScreenReport report, String stage) {
        return report.stageStats().stream()
                .filter(item -> stage.equals(item.stage()))
                .findFirst()
                .orElseThrow();
    }

    private EastMoneyAnnualIndicator annualIndicator(String symbol, int year) {
        return new EastMoneyAnnualIndicator(
                symbol,
                "fixture",
                year + "-12-31",
                year + "年 年报",
                new BigDecimal("0.14"),
                new BigDecimal("0.80"),
                new BigDecimal("0.28"),
                new BigDecimal("0.05"),
                new BigDecimal("0.06"),
                new BigDecimal("0.70"),
                new BigDecimal("3.20"),
                new BigDecimal("12000000000"),
                new BigDecimal("900000000"),
                "10派3元",
                new BigDecimal("0.03")
        );
    }

    private static final class StubEastMoneyClient extends EastMoneyClient {

        private List<EastMoneyQuote> baseQuotes = List.of();
        private List<EastMoneyQuote> tencentQuotes = List.of();
        private Set<String> sidewaysSymbols = Set.of();
        private int klineRequestCount;
        private int tencentRequestSymbolCount;
        private long fetchAshareDelayMillis;
        private int expectedCount;
        private Map<Integer, Map<String, EastMoneyAnnualIndicator>> annualIndicatorsByYear = Map.of();

        private StubEastMoneyClient() {
            super(null, null, null);
        }

        @Override
        public List<EastMoneyQuote> fetchAshareQuotes(int limit) {
            if (fetchAshareDelayMillis > 0) {
                try {
                    Thread.sleep(fetchAshareDelayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试行情加载被中断", exception);
                }
            }
            return baseQuotes.stream().limit(limit).toList();
        }

        @Override
        public AshareQuoteSnapshot fetchAshareQuoteSnapshot(int limit) {
            if (fetchAshareDelayMillis > 0) {
                try {
                    Thread.sleep(fetchAshareDelayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试行情加载被中断", exception);
                }
            }
            List<EastMoneyQuote> quotes = baseQuotes.stream().limit(limit).toList();
            int expected = expectedCount > 0 ? expectedCount : quotes.size();
            int missing = Math.max(0, expected - quotes.size());
            return new AshareQuoteSnapshot(
                    quotes,
                    limit,
                    expected,
                    quotes.size(),
                    missing,
                    missing == 0,
                    "测试行情",
                    Instant.parse("2026-07-10T07:00:00Z")
            );
        }

        @Override
        public List<EastMoneyQuote> fetchTencentQuotes(List<String> symbols, int limit) {
            tencentRequestSymbolCount = symbols.size();
            return tencentQuotes.stream()
                    .filter(quote -> symbols.contains(quote.symbol()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Map<String, EastMoneyAnnualIndicator> fetchAnnualIndicators(int dataYear, int pageSize) {
            return annualIndicatorsByYear.getOrDefault(dataYear, Map.of());
        }

        @Override
        public List<EastMoneyKLine> fetchDailyKLines(String symbol, LocalDate begin, LocalDate end) {
            klineRequestCount++;
            if (!sidewaysSymbols.contains(symbol)) {
                return risingKLines(symbol);
            }
            return IntStream.range(0, 100)
                    .mapToObj(index -> new EastMoneyKLine(
                            symbol,
                            LocalDate.of(2026, 1, 1).plusDays(index),
                            new BigDecimal("10.00"),
                            new BigDecimal("10.00"),
                            new BigDecimal("10.40"),
                            new BigDecimal("9.80"),
                            new BigDecimal("1000000"),
                            new BigDecimal("10000000")
                    ))
                    .toList();
        }

        private List<EastMoneyKLine> risingKLines(String symbol) {
            return IntStream.range(0, 100)
                    .mapToObj(index -> {
                        BigDecimal close = new BigDecimal("10.00").add(BigDecimal.valueOf(index).multiply(new BigDecimal("0.08")));
                        return new EastMoneyKLine(
                                symbol,
                                LocalDate.of(2026, 1, 1).plusDays(index),
                                close,
                                close,
                                close.add(new BigDecimal("0.40")),
                                close.subtract(new BigDecimal("0.30")),
                                new BigDecimal("1000000"),
                                new BigDecimal("10000000")
                        );
                    })
                    .toList();
        }
    }
}
