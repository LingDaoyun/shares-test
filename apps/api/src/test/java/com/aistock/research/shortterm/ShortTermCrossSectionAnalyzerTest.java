package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermCrossSectionAnalyzerTest {

    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-08-10");
    private final ShortTermCrossSectionAnalyzer analyzer = new ShortTermCrossSectionAnalyzer();

    @Test
    void calculatesRelativeStrengthAndSoftIndustryLeadershipFromSeparateUniverses() {
        List<EastMoneyQuote> universe = List.of(
                quote("600001", "设备", "100000000"),
                quote("600002", "设备", "300000000"),
                quote("600003", "设备", "200000000")
        );
        Map<String, List<EastMoneyKLine>> klines = new LinkedHashMap<>();
        klines.put("600001", risingRows("600001", "8.00", "10.00"));
        klines.put("600002", risingRows("600002", "9.50", "10.00"));
        klines.put("600003", risingRows("600003", "9.00", "10.00"));

        ShortTermCrossSectionAnalysis analysis = analyzer.analyze(universe, universe, klines);
        ShortTermRelativeStrength strongest = analysis.relativeStrengthBySymbol().get("600001");
        ShortTermIndustryLeadership lowestLiquidity = analysis.industryLeadershipBySymbol().get("600001");
        ShortTermIndustryLeadership leader = analysis.industryLeadershipBySymbol().get("600002");

        assertThat(strongest.marketPercentile20()).isEqualByComparingTo("100.00");
        assertThat(strongest.industryPercentile20()).isEqualByComparingTo("100.00");
        assertThat(strongest.contribution()).isPositive().isLessThanOrEqualTo(new BigDecimal("4.00"));
        assertThat(lowestLiquidity.amountRank()).isEqualTo(3);
        assertThat(lowestLiquidity.percentile()).isEqualByComparingTo("0.00");
        assertThat(lowestLiquidity.contribution()).isEqualByComparingTo("-2.00");
        assertThat(leader.amountRank()).isEqualTo(1);
        assertThat(leader.percentile()).isEqualByComparingTo("100.00");
        assertThat(leader.contribution()).isEqualByComparingTo("2.00");
    }

    @Test
    void ignoresFutureBarsAndUsesCurrentQuoteAsPointInTimeEndpoint() {
        List<EastMoneyQuote> universe = List.of(
                quote("600001", "设备", "300000000"),
                quote("600002", "设备", "200000000"),
                quote("600003", "设备", "100000000")
        );
        Map<String, List<EastMoneyKLine>> klines = new LinkedHashMap<>();
        List<EastMoneyKLine> withFuture = new ArrayList<>(risingRows("600001", "8.00", "10.00"));
        withFuture.add(kline("600001", TRADE_DATE.plusDays(1), "100.00"));
        klines.put("600001", withFuture);
        klines.put("600002", risingRows("600002", "9.00", "10.00"));
        klines.put("600003", risingRows("600003", "9.50", "10.00"));

        ShortTermRelativeStrength snapshot = analyzer.analyze(universe, universe, klines)
                .relativeStrengthBySymbol().get("600001");

        assertThat(snapshot.return20()).isLessThan(new BigDecimal("30.00"));
        assertThat(snapshot.dataGaps()).anyMatch(gap -> gap.contains("未来K线"));
    }

    @Test
    void missingHistoryIsNeutralAndNeverReceivesOptimisticPercentile() {
        List<EastMoneyQuote> universe = List.of(
                quote("600001", "设备", "300000000"),
                quote("600002", "设备", "200000000"),
                quote("600003", "设备", "100000000")
        );
        Map<String, List<EastMoneyKLine>> klines = Map.of(
                "600001", risingRows("600001", "8.00", "10.00"),
                "600002", risingRows("600002", "9.00", "10.00"),
                "600003", risingRows("600003", "9.50", "10.00").subList(0, 10)
        );

        ShortTermRelativeStrength missing = analyzer.analyze(universe, universe, klines)
                .relativeStrengthBySymbol().get("600003");

        assertThat(missing.compositeScore()).isNull();
        assertThat(missing.contribution()).isEqualByComparingTo("0.00");
        assertThat(missing.dataGaps()).anyMatch(gap -> gap.contains("20日"));
    }

    @Test
    void tiesReceiveTheSameDeterministicMidpointPercentile() {
        List<EastMoneyQuote> universe = List.of(
                quote("600001", "设备", "300000000"),
                quote("600002", "设备", "200000000"),
                quote("600003", "设备", "100000000")
        );
        Map<String, List<EastMoneyKLine>> klines = Map.of(
                "600001", risingRows("600001", "9.00", "10.00"),
                "600002", risingRows("600002", "9.00", "10.00"),
                "600003", risingRows("600003", "9.00", "10.00")
        );

        ShortTermCrossSectionAnalysis analysis = analyzer.analyze(universe, universe, klines);

        assertThat(analysis.relativeStrengthBySymbol().values())
                .extracting(ShortTermRelativeStrength::marketPercentile20)
                .allMatch(value -> value.compareTo(new BigDecimal("50.00")) == 0);
    }

    @Test
    void missingIndustryNeverCreatesASyntheticPeerCohort() {
        List<EastMoneyQuote> universe = List.of(
                quote("600001", null, "300000000"),
                quote("600002", null, "200000000"),
                quote("600003", null, "100000000")
        );
        Map<String, List<EastMoneyKLine>> klines = Map.of(
                "600001", risingRows("600001", "8.00", "10.00"),
                "600002", risingRows("600002", "9.00", "10.00"),
                "600003", risingRows("600003", "9.50", "10.00")
        );

        ShortTermRelativeStrength snapshot = analyzer.analyze(universe, universe, klines)
                .relativeStrengthBySymbol().get("600001");

        assertThat(snapshot.marketPercentile20()).isEqualByComparingTo("100.00");
        assertThat(snapshot.industryPercentile5()).isNull();
        assertThat(snapshot.industryPercentile10()).isNull();
        assertThat(snapshot.industryPercentile20()).isNull();
        assertThat(snapshot.dataGaps()).anyMatch(gap -> gap.contains("同行横截面"));
    }

    private EastMoneyQuote quote(String symbol, String industry, String amount) {
        return new EastMoneyQuote(
                symbol, symbol, "沪市", industry,
                new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("3.00"),
                new BigDecimal("100000"), new BigDecimal(amount), new BigDecimal("18"),
                new BigDecimal("1.6"), new BigDecimal("18"), "测试", "https://example.test/" + symbol,
                Instant.parse("2026-08-10T06:49:00Z"), TRADE_DATE,
                Instant.parse("2026-08-10T06:49:00Z")
        );
    }

    private List<EastMoneyKLine> risingRows(String symbol, String firstClose, String lastClose) {
        List<EastMoneyKLine> rows = new ArrayList<>();
        BigDecimal first = new BigDecimal(firstClose);
        BigDecimal last = new BigDecimal(lastClose);
        for (int index = 0; index < 25; index++) {
            BigDecimal close = first.add(last.subtract(first)
                    .multiply(BigDecimal.valueOf(index))
                    .divide(BigDecimal.valueOf(24), 6, java.math.RoundingMode.HALF_UP));
            rows.add(kline(symbol, TRADE_DATE.minusDays(25L - index), close.toPlainString()));
        }
        return rows;
    }

    private EastMoneyKLine kline(String symbol, LocalDate date, String closeText) {
        BigDecimal close = new BigDecimal(closeText);
        return new EastMoneyKLine(
                symbol, date, close, close, close.add(new BigDecimal("0.10")),
                close.subtract(new BigDecimal("0.10")), new BigDecimal("100000"),
                new BigDecimal("100000000"), new BigDecimal("3.00")
        );
    }
}
