package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermMarketRegimeClassifierTest {

    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-08-12");
    private final ShortTermMarketRegimeClassifier classifier = new ShortTermMarketRegimeClassifier();

    @Test
    void sparseAmountsCannotRepresentMarketWideAdvancingTurnover() {
        List<EastMoneyQuote> quotes = market(70, "1.5", 30, "-0.2").stream().map(q ->
                new EastMoneyQuote(q.symbol(), q.name(), q.market(), q.industry(), q.latestPrice(), q.changePercent(),
                        q.turnoverRate(), q.volume(), q.symbol().equals("600000") ? q.amount() : null,
                        q.peRatio(), q.pbRatio(), q.peTtm(), q.sourceName(), q.quoteUrl(), q.fetchedAt(),
                        q.tradeDate(), q.marketTimestamp())).toList();
        ShortTermMarketRegime regime = classifier.classify(quotes, reliableCoverage(), sentiment("发酵", 70), null, liquidity("5", "4"));
        assertThat(regime.advancingTurnoverSharePercent()).isNull();
        assertThat(regime.maxAction()).isEqualTo("LIGHT_TRIAL");
        assertThat(regime.dataGaps()).anyMatch(gap -> gap.contains("成交额"));
    }

    @Test
    void comparableHealthyVolumeRetainsNormalActionAndProxyCountsAreExplicit() {
        ShortTermMarketRegime regime = classifier.classify(market(70, "1.5", 30, "-0.2"), reliableCoverage(),
                sentiment("发酵", 70), null, liquidity("5", "4"));
        assertThat(regime.maxAction()).isEqualTo("NORMAL");
        assertThat(regime.limitCountSource()).isEqualTo("PRICE_CHANGE_PROXY");
        assertThat(regime.limitUpCount()).isZero();
    }

    @Test
    void missingComparisonCapsPositionWithoutInventingContraction() {
        ShortTermMarketRegime regime = classifier.classify(market(70, "1.5", 30, "-0.2"), reliableCoverage(),
                sentiment("发酵", 70), null, null);
        assertThat(regime.maxAction()).isEqualTo("LIGHT_TRIAL");
        assertThat(regime.state()).isEqualTo("TREND_EXPANSION");
        assertThat(regime.dataGaps()).anyMatch(gap -> gap.contains("暂不可比"));
    }

    @Test
    void materialContractionCapsOtherwiseStrongBreadth() {
        ShortTermMarketRegime regime = classifier.classify(market(70, "1.5", 30, "-0.2"), reliableCoverage(),
                sentiment("发酵", 70), null, liquidity("-15", "-5"));
        assertThat(regime.state()).isEqualTo("LIQUIDITY_CONTRACTION");
        assertThat(regime.maxAction()).isEqualTo("LIGHT_TRIAL");
    }

    @Test
    void expandingTurnoverWithWeakBreadthPausesExecution() {
        ShortTermMarketRegime regime = classifier.classify(market(30, "1.5", 70, "-0.9"), reliableCoverage(),
                sentiment("修复", 30), null, liquidity("10", "8"));
        assertThat(regime.label()).isEqualTo("放量下跌");
        assertThat(regime.maxAction()).isEqualTo("NO_TRADE");
    }

    @Test
    void measuredHighBreakRateCapsActionWithoutInferringMissingCounts() {
        ShortTermLimitUpSentiment pulse = new ShortTermLimitUpSentiment(6, 5, null, new BigDecimal("45.45"),
                2, 2, 0, 4, 2, 0, 0, new BigDecimal("66.67"), "分歧", "测试");
        ShortTermMarketRegime regime = classifier.classify(market(70, "1.5", 30, "-0.2"), reliableCoverage(),
                sentiment("发酵", 70), pulse, liquidity("5", "4"));
        assertThat(regime.maxAction()).isEqualTo("LIGHT_TRIAL");
        assertThat(regime.limitCountSource()).isEqualTo("MIXED");
        assertThat(regime.brokenCount()).isEqualTo(5);
    }

    private ShortTermMarketLiquidity liquidity(String amount, String volume) {
        return new ShortTermMarketLiquidity(TRADE_DATE, Instant.parse("2026-08-12T06:49:00Z"), "SAME_TIME", "同刻比较",
                new BigDecimal("60000000000"), new BigDecimal("100"), new BigDecimal("10000000000"),
                new BigDecimal("9000000000"), new BigDecimal(amount), new BigDecimal("100000"),
                new BigDecimal("90000"), new BigDecimal(volume), TRADE_DATE.minusDays(1), "14:48", "测试", "测试", List.of());
    }

    @Test
    void classifiesSystemicSelloffAsRiskOffAndNonExecutable() {
        List<EastMoneyQuote> quotes = market(10, "1.00", 90, "-5.00");

        ShortTermMarketRegime regime = classifier.classify(quotes, reliableCoverage(), sentiment("极端退潮", 10));

        assertThat(regime.state()).isEqualTo("RISK_OFF");
        assertThat(regime.maxAction()).isEqualTo("NO_TRADE");
    }

    @Test
    void classifiesBroadOrderlyAdvanceAsTrendExpansion() {
        List<EastMoneyQuote> quotes = market(70, "1.50", 30, "-0.20");

        ShortTermMarketRegime regime = classifier.classify(quotes, reliableCoverage(), sentiment("发酵", 70));

        assertThat(regime.state()).isEqualTo("TREND_EXPANSION");
        assertThat(regime.maxAction()).isEqualTo("NORMAL");
        assertThat(regime.advancingTurnoverSharePercent()).isEqualByComparingTo("70.00");
    }

    @Test
    void classifiesHighDispersionConsensusAsCrowdedVolatile() {
        List<EastMoneyQuote> quotes = market(85, "4.50", 15, "-3.20");

        ShortTermMarketRegime regime = classifier.classify(quotes, reliableCoverage(), sentiment("高潮", 85));

        assertThat(regime.state()).isEqualTo("CROWDED_VOLATILE");
        assertThat(regime.maxAction()).isEqualTo("LIGHT_TRIAL");
        assertThat(regime.averageAbsoluteChangePercent()).isGreaterThan(new BigDecimal("3.50"));
    }

    @Test
    void neutralMixedBreadthIsRepairAndCapsNormalAdds() {
        List<EastMoneyQuote> quotes = market(52, "0.40", 48, "-0.30");

        ShortTermMarketRegime regime = classifier.classify(quotes, reliableCoverage(), sentiment("修复", 52));

        assertThat(regime.state()).isEqualTo("REPAIR");
        assertThat(regime.maxAction()).isEqualTo("LIGHT_TRIAL");
    }

    @Test
    void broadButMildDeclineDoesNotManufactureSystemicRiskOff() {
        List<EastMoneyQuote> quotes = market(20, "0.10", 80, "-0.20");

        ShortTermMarketRegime regime = classifier.classify(quotes, reliableCoverage(), sentiment("修复", 20));

        assertThat(regime.state()).isEqualTo("REPAIR");
        assertThat(regime.maxAction()).isEqualTo("LIGHT_TRIAL");
    }

    @Test
    void unreliableCoverageNeverManufacturesARegime() {
        ShortTermCoverageSnapshot coverage = new ShortTermCoverageSnapshot(
                100, 90, 10, new BigDecimal("0.9000"), false, "测试", Instant.now()
        );

        ShortTermMarketRegime regime = classifier.classify(
                market(70, "1.50", 30, "-0.20"), coverage, sentiment("发酵", 70)
        );

        assertThat(regime.state()).isEqualTo("UNAVAILABLE");
        assertThat(regime.maxAction()).isEqualTo("NO_TRADE");
        assertThat(regime.dataGaps()).anyMatch(gap -> gap.contains("覆盖"));
    }

    @Test
    void sparseReturnSamplesCannotBorrowReliableQuoteCoverage() {
        ShortTermCoverageSnapshot coverage = new ShortTermCoverageSnapshot(
                100, 100, 0, BigDecimal.ONE, true, "测试", Instant.now()
        );

        ShortTermMarketRegime regime = classifier.classify(
                market(2, "1.00", 1, "-0.20"), coverage, sentiment("发酵", 67)
        );

        assertThat(regime.state()).isEqualTo("UNAVAILABLE");
        assertThat(regime.maxAction()).isEqualTo("NO_TRADE");
        assertThat(regime.dataGaps()).anyMatch(gap -> gap.contains("涨跌幅样本覆盖"));
    }

    @Test
    void limitUpPoolPulseReplacesApproximateCountsAndEnrichesExplanation() {
        List<EastMoneyQuote> quotes = market(70, "1.50", 30, "-0.20");
        ShortTermLimitUpSentiment pulse = new ShortTermLimitUpSentiment(
                80, 18, 13, new BigDecimal("18.37"), 5, 12, 4,
                40, 20, 12, 8, new BigDecimal("50.00"), "情绪偏暖", "测试"
        );

        ShortTermMarketRegime regime = classifier.classify(
                quotes, reliableCoverage(), sentiment("发酵", 70), pulse
        );

        assertThat(regime.limitUpRatioPercent()).isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(regime.limitDownRatioPercent()).isEqualByComparingTo(new BigDecimal("13.00"));
        assertThat(regime.explanation())
                .contains("涨停池实测")
                .contains("涨停 80 家")
                .contains("炸板率 18.37%")
                .contains("最高 5 连板");
    }

    @Test
    void brokenPoolGapIsExposedWhenPulseLacksBrokenCount() {
        List<EastMoneyQuote> quotes = market(70, "1.50", 30, "-0.20");
        ShortTermLimitUpSentiment pulse = new ShortTermLimitUpSentiment(
                80, null, null, null, 5, 12, 4,
                40, 20, 12, 8, new BigDecimal("50.00"), "中性震荡", "测试"
        );

        ShortTermMarketRegime regime = classifier.classify(
                quotes, reliableCoverage(), sentiment("发酵", 70), pulse
        );

        assertThat(regime.dataGaps()).anyMatch(gap -> gap.contains("炸板数据缺失"));
    }

    @Test
    void nullPulseKeepsApproximateBehaviorUnchanged() {
        List<EastMoneyQuote> quotes = market(70, "1.50", 30, "-0.20");

        ShortTermMarketRegime withNullPulse = classifier.classify(
                quotes, reliableCoverage(), sentiment("发酵", 70), null
        );
        ShortTermMarketRegime legacy = classifier.classify(
                quotes, reliableCoverage(), sentiment("发酵", 70)
        );

        assertThat(withNullPulse).isEqualTo(legacy);
    }

    private List<EastMoneyQuote> market(int positiveCount, String positive, int negativeCount, String negative) {
        return IntStream.range(0, positiveCount + negativeCount)
                .mapToObj(index -> quote(
                        String.format("600%03d", index),
                        index < positiveCount ? positive : negative
                ))
                .toList();
    }

    private EastMoneyQuote quote(String symbol, String change) {
        return new EastMoneyQuote(
                symbol, "样本" + symbol, "沪市", "测试行业",
                new BigDecimal("10.00"), new BigDecimal(change), new BigDecimal("3.00"),
                new BigDecimal("100000"), new BigDecimal("600000000"), new BigDecimal("18"),
                new BigDecimal("1.6"), new BigDecimal("18"), "测试", "https://example.test/" + symbol,
                Instant.parse("2026-08-12T06:49:00Z"), TRADE_DATE,
                Instant.parse("2026-08-12T06:49:00Z")
        );
    }

    private ShortTermCoverageSnapshot reliableCoverage() {
        return new ShortTermCoverageSnapshot(
                100, 100, 0, BigDecimal.ONE, true, "测试", Instant.parse("2026-08-12T06:49:00Z")
        );
    }

    private ShortTermMarketSentiment sentiment(String phase, int breadth) {
        return new ShortTermMarketSentiment(
                phase, BigDecimal.valueOf(breadth), breadth, 100 - breadth,
                10, 2, BigDecimal.valueOf(breadth), "测试"
        );
    }
}
