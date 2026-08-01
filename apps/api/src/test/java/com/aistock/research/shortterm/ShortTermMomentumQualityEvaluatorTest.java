package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermMomentumQualityEvaluatorTest {

    private final ShortTermMomentumQualityEvaluator evaluator = new ShortTermMomentumQualityEvaluator();

    @Test
    void givesTheHighestTurnoverScoreAtThreePercentAndKeepsOneToEightPercentAsObservationBands() {
        ShortTermMomentumQuality center = evaluate("3.00", completedRows());
        ShortTermMomentumQuality lowerPreferred = evaluate("2.00", completedRows());
        ShortTermMomentumQuality upperPreferred = evaluate("5.00", completedRows());
        ShortTermMomentumQuality lowerObservation = evaluate("1.00", completedRows());
        ShortTermMomentumQuality upperObservation = evaluate("8.00", completedRows());

        assertThat(center.turnoverScore()).isEqualByComparingTo("100.00");
        assertThat(center.turnoverBand()).isEqualTo("PREFERRED");
        assertThat(lowerPreferred.turnoverScore()).isGreaterThan(lowerObservation.turnoverScore());
        assertThat(upperPreferred.turnoverScore()).isGreaterThan(upperObservation.turnoverScore());
        assertThat(lowerObservation.turnoverBand()).isEqualTo("OBSERVATION");
        assertThat(upperObservation.turnoverBand()).isEqualTo("OBSERVATION");
    }

    @Test
    void treatsLowerTurnoverAsPreferredForVeryLargeAmountStocks() {
        ShortTermMomentumQuality result = evaluator.evaluate(
                quote("1.60", "12.00", "3600000000"),
                completedRows(),
                new BigDecimal("12.00"),
                true
        );

        assertThat(result.turnoverBand()).isEqualTo("PREFERRED");
        assertThat(result.turnoverScore()).isGreaterThanOrEqualTo(new BigDecimal("85"));
    }

    @Test
    void calculatesUpperShadowMedianAndCloseLocationFromTheLatestThreeBullishCandles() {
        ShortTermMomentumQuality result = evaluate("3.00", List.of(
                row("2026-07-24", "10.00", "10.80", "11.00", "9.90"),
                row("2026-07-27", "10.80", "11.60", "12.00", "10.70"),
                row("2026-07-28", "11.60", "12.40", "12.50", "11.50")
        ));

        assertThat(result.latestUpperShadowPercent()).isEqualByComparingTo("10.00");
        assertThat(result.bullishUpperShadowMedian3Percent()).isEqualByComparingTo("18.18");
        assertThat(result.closeLocationPercent()).isEqualByComparingTo("90.00");
        assertThat(result.closeStrengthLabel()).isEqualTo("上攻收盘强");
        assertThat(result.closeStrengthScore()).isGreaterThanOrEqualTo(new BigDecimal("90"));
        assertThat(result.extremeUpperShadow()).isFalse();
    }

    @Test
    void marksLongUpperShadowWithWeakCloseAsObservationOnly() {
        ShortTermMomentumQuality result = evaluate("3.00", List.of(
                row("2026-07-24", "10.00", "10.70", "10.80", "9.90"),
                row("2026-07-27", "10.70", "11.30", "11.40", "10.60"),
                row("2026-07-28", "11.30", "11.50", "12.30", "11.20")
        ));

        assertThat(result.latestUpperShadowPercent()).isGreaterThan(new BigDecimal("50"));
        assertThat(result.closeLocationPercent()).isLessThan(new BigDecimal("60"));
        assertThat(result.extremeUpperShadow()).isTrue();
        assertThat(result.closeStrengthLabel()).isEqualTo("长上影观察");
    }

    @Test
    void marksRepeatedLongUpperShadowsAsObservationEvenWhenTheLatestCandleClosesStrongly() {
        ShortTermMomentumQuality result = evaluate("3.00", List.of(
                row("2026-07-24", "10.00", "10.20", "12.00", "9.90"),
                row("2026-07-27", "10.20", "10.40", "12.20", "10.10"),
                row("2026-07-28", "10.40", "11.00", "11.05", "10.30")
        ));

        assertThat(result.bullishUpperShadowMedian3Percent()).isGreaterThan(new BigDecimal("50"));
        assertThat(result.closeLocationPercent()).isGreaterThan(new BigDecimal("80"));
        assertThat(result.extremeUpperShadow()).isTrue();
        assertThat(result.closeStrengthLabel()).isEqualTo("长上影观察");
    }

    @Test
    void usesRealtimePriceAsProvisionalCloseForAnUnfinishedDailyBar() {
        EastMoneyQuote quote = quote("3.00", "12.90");

        ShortTermMomentumQuality result = evaluator.evaluate(
                quote,
                List.of(
                        row("2026-07-27", "11.00", "11.70", "11.80", "10.90"),
                        row("2026-07-28", "11.70", "12.20", "12.40", "11.60"),
                        row("2026-07-29", "12.20", "12.40", "12.70", "12.10")
                ),
                new BigDecimal("12.90"),
                false
        );

        assertThat(result.provisional()).isTrue();
        assertThat(result.closeLocationPercent()).isEqualByComparingTo("100.00");
        assertThat(result.latestUpperShadowPercent()).isEqualByComparingTo("0.00");
    }

    @Test
    void keepsTheOfficialCloseForACompletedDailyBar() {
        ShortTermMomentumQuality result = evaluator.evaluate(
                quote("3.00", "12.90"),
                List.of(
                        row("2026-07-27", "11.00", "11.70", "11.80", "10.90"),
                        row("2026-07-28", "11.70", "12.20", "12.40", "11.60"),
                        row("2026-07-29", "12.20", "12.40", "12.70", "12.10")
                ),
                new BigDecimal("12.90"),
                true
        );

        assertThat(result.provisional()).isFalse();
        assertThat(result.closeLocationPercent()).isEqualByComparingTo("50.00");
        assertThat(result.latestUpperShadowPercent()).isEqualByComparingTo("50.00");
    }

    private ShortTermMomentumQuality evaluate(String turnoverRate, List<EastMoneyKLine> rows) {
        return evaluator.evaluate(
                quote(turnoverRate, rows.get(rows.size() - 1).close().toPlainString()),
                rows,
                rows.get(rows.size() - 1).close(),
                true
        );
    }

    private List<EastMoneyKLine> completedRows() {
        return List.of(
                row("2026-07-24", "10.00", "10.70", "10.80", "9.90"),
                row("2026-07-27", "10.70", "11.30", "11.40", "10.60"),
                row("2026-07-28", "11.30", "12.00", "12.10", "11.20")
        );
    }

    private EastMoneyKLine row(String date, String open, String close, String high, String low) {
        return new EastMoneyKLine(
                "600001",
                LocalDate.parse(date),
                new BigDecimal(open),
                new BigDecimal(close),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal("200000"),
                new BigDecimal("240000000")
        );
    }

    private EastMoneyQuote quote(String turnoverRate, String latestPrice) {
        return quote(turnoverRate, latestPrice, "240000000");
    }

    private EastMoneyQuote quote(String turnoverRate, String latestPrice, String amount) {
        Instant timestamp = Instant.parse("2026-07-29T06:50:00Z");
        return new EastMoneyQuote(
                "600001",
                "测试股份",
                "SH",
                "电力",
                new BigDecimal(latestPrice),
                new BigDecimal("1.80"),
                new BigDecimal(turnoverRate),
                new BigDecimal("200000"),
                new BigDecimal(amount),
                new BigDecimal("18"),
                new BigDecimal("1.8"),
                new BigDecimal("18"),
                "东方财富",
                "https://quote.eastmoney.com/600001.html",
                timestamp,
                LocalDate.parse("2026-07-29"),
                timestamp
        );
    }
}
