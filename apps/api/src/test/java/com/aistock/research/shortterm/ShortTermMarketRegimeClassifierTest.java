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
