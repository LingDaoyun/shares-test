package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermVolatilityQualityEvaluatorTest {

    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-08-12");
    private final ShortTermVolatilityQualityEvaluator evaluator = new ShortTermVolatilityQualityEvaluator();

    @Test
    void detectsContractionBreakoutAsAnIndependentBoundedSignal() {
        ShortTermVolatilityQuality quality = evaluator.evaluate(
                contractionBreakoutRows(),
                new BigDecimal("11.80"),
                TRADE_DATE,
                snapshot("2.00", "1.00", "1.20")
        );

        assertThat(quality.state()).isEqualTo("CONTRACTION_BREAKOUT");
        assertThat(quality.contractionBreakout()).isTrue();
        assertThat(quality.contractionRatio5To20()).isLessThanOrEqualTo(new BigDecimal("0.80"));
        assertThat(quality.breakoutExpansionRatio()).isGreaterThanOrEqualTo(new BigDecimal("1.15"));
        assertThat(quality.distanceToMa20Atr()).isEqualByComparingTo("0.50");
        assertThat(quality.contribution()).isPositive().isLessThanOrEqualTo(new BigDecimal("3.00"));
    }

    @Test
    void zeroAtrAndFlatRangesStayNeutralWithoutDivisionByZero() {
        List<EastMoneyKLine> rows = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            rows.add(row(TRADE_DATE.minusDays(29L - index), "10.00", "10.00", "10.00"));
        }

        ShortTermVolatilityQuality quality = evaluator.evaluate(
                rows,
                new BigDecimal("10.00"),
                TRADE_DATE,
                snapshot("0", "0", "0")
        );

        assertThat(quality.state()).isEqualTo("UNAVAILABLE");
        assertThat(quality.contribution()).isEqualByComparingTo("0.00");
        assertThat(quality.dataGaps()).anyMatch(gap -> gap.contains("ATR"));
    }

    @Test
    void insufficientRowsNeverReceiveAnOptimisticDefault() {
        ShortTermVolatilityQuality quality = evaluator.evaluate(
                contractionBreakoutRows().subList(0, 10),
                new BigDecimal("11.80"),
                TRADE_DATE,
                snapshot("2.00", "1.00", "1.20")
        );

        assertThat(quality.state()).isEqualTo("UNAVAILABLE");
        assertThat(quality.contribution()).isEqualByComparingTo("0.00");
    }

    @Test
    void missingCutoffDateFailsClosedInsteadOfReadingUnboundedKLines() {
        ShortTermVolatilityQuality quality = evaluator.evaluate(
                contractionBreakoutRows(),
                new BigDecimal("11.80"),
                null,
                snapshot("2.00", "1.00", "1.20")
        );

        assertThat(quality.state()).isEqualTo("UNAVAILABLE");
        assertThat(quality.contribution()).isEqualByComparingTo("0.00");
        assertThat(quality.dataGaps()).anyMatch(gap -> gap.contains("截止交易日"));
    }

    @Test
    void overextendedDistanceIsPenalizedInAtrUnits() {
        ShortTermVolatilityQuality quality = evaluator.evaluate(
                ordinaryRows(),
                new BigDecimal("12.00"),
                TRADE_DATE,
                snapshot("1.00", "3.20", "-0.20")
        );

        assertThat(quality.state()).isEqualTo("OVEREXTENDED");
        assertThat(quality.distanceToMa20Atr()).isEqualByComparingTo("3.20");
        assertThat(quality.contribution()).isNegative().isGreaterThanOrEqualTo(new BigDecimal("-3.00"));
    }

    private List<EastMoneyKLine> contractionBreakoutRows() {
        List<EastMoneyKLine> rows = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            BigDecimal close = new BigDecimal("10.00").add(BigDecimal.valueOf(index).multiply(new BigDecimal("0.03")));
            rows.add(row(TRADE_DATE.minusDays(29L - index), close.toPlainString(),
                    close.add(new BigDecimal("0.60")).toPlainString(),
                    close.subtract(new BigDecimal("0.60")).toPlainString()));
        }
        for (int index = 24; index < 29; index++) {
            BigDecimal close = new BigDecimal("10.72").add(BigDecimal.valueOf(index - 24L).multiply(new BigDecimal("0.02")));
            rows.add(row(TRADE_DATE.minusDays(29L - index), close.toPlainString(),
                    close.add(new BigDecimal("0.15")).toPlainString(),
                    close.subtract(new BigDecimal("0.15")).toPlainString()));
        }
        rows.add(row(TRADE_DATE, "11.80", "12.10", "10.65"));
        return rows;
    }

    private List<EastMoneyKLine> ordinaryRows() {
        List<EastMoneyKLine> rows = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            BigDecimal close = new BigDecimal("10.00").add(BigDecimal.valueOf(index).multiply(new BigDecimal("0.04")));
            rows.add(row(TRADE_DATE.minusDays(29L - index), close.toPlainString(),
                    close.add(new BigDecimal("0.35")).toPlainString(),
                    close.subtract(new BigDecimal("0.35")).toPlainString()));
        }
        return rows;
    }

    private EastMoneyKLine row(LocalDate date, String closeText, String highText, String lowText) {
        BigDecimal close = new BigDecimal(closeText);
        return new EastMoneyKLine(
                "600001", date, close.subtract(new BigDecimal("0.05")), close,
                new BigDecimal(highText), new BigDecimal(lowText),
                new BigDecimal("100000"), new BigDecimal("100000000"), new BigDecimal("3.00")
        );
    }

    private ShortTermTechnicalSnapshot snapshot(String atr, String distance, String breakout) {
        return new ShortTermTechnicalSnapshot(
                TRADE_DATE,
                new BigDecimal("11.40"), new BigDecimal("11.20"), new BigDecimal("11.00"), new BigDecimal("10.50"),
                new BigDecimal("0.30"), new BigDecimal("0.10"), new BigDecimal("11.66"), new BigDecimal("12.00"),
                new BigDecimal(breakout), new BigDecimal("8.00"), new BigDecimal("12.00"), new BigDecimal("8.00"),
                new BigDecimal("1.40"), new BigDecimal("1.30"), new BigDecimal("70"), new BigDecimal("65"),
                new BigDecimal(distance), new BigDecimal("2.00"), new BigDecimal("4.00"), 3,
                "右侧早期确认", ShortTermGoldenCrossSnapshot.unavailable(), new BigDecimal(atr), new BigDecimal("10.50")
        );
    }
}
