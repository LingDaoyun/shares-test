package com.aistock.research.shortterm.chip;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalChipDistributionCalculatorTest {

    @Test
    void oneHundredPercentTurnoverReplacesThePreviousDistribution() {
        LocalChipDistributionCalculator calculator = calculator(2, 0.95);

        LocalChipDistribution result = calculator.calculate(
                List.of(
                        bar(0, "9.00", "10.00", "11.00", "9.00", "100", "100000", "100000000"),
                        bar(1, "19.00", "20.00", "21.00", "19.00", "100", "100000", "200000000")
                ),
                new BigDecimal("20.00"),
                ChipCalculationMode.COMPLETED_BAR
        );

        assertThat(result.quality()).isEqualTo(ChipDataQuality.VALID);
        assertThat(result.averageCost()).isBetween(new BigDecimal("19.00"), new BigDecimal("21.00"));
        assertThat(result.cost5()).isGreaterThan(new BigDecimal("18.00"));
    }

    @Test
    void allocatesOnePriceBarToItsOnlyPriceBucket() {
        LocalChipDistributionCalculator calculator = calculator(1, 1.0);

        LocalChipDistribution result = calculator.calculate(
                List.of(bar(0, "10.20", "10.20", "10.20", "10.20", "8", "100000", "102000000")),
                new BigDecimal("10.20"),
                ChipCalculationMode.INTRADAY_ESTIMATE
        );

        assertThat(result.averageCost()).isEqualByComparingTo("10.20");
        assertThat(result.cost5()).isEqualByComparingTo("10.20");
        assertThat(result.cost95()).isEqualByComparingTo("10.20");
        assertThat(result.calculationMode()).isEqualTo(ChipCalculationMode.INTRADAY_ESTIMATE);
    }

    @Test
    void acceptsAmountPerOneHundredVolumeUnitsWhenItFallsInsideDailyRange() {
        LocalChipDistributionCalculator calculator = calculator(1, 1.0);

        LocalChipDistribution result = calculator.calculate(
                List.of(bar(0, "9.00", "10.00", "11.00", "9.00", "5", "100000", "100000000")),
                new BigDecimal("10.00"),
                ChipCalculationMode.COMPLETED_BAR
        );

        assertThat(result.averageCost()).isBetween(new BigDecimal("9.70"), new BigDecimal("10.30"));
    }

    @Test
    void marksDistributionInsufficientWhenTurnoverCoverageIsBelowNinetyFivePercent() {
        LocalChipDistributionCalculator calculator = calculator(80, 0.95);
        List<EastMoneyKLine> bars = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            bars.add(bar(index, "9.80", "10.00", "10.20", "9.70",
                    index < 7 ? null : "2.00", "100000", "100000000"));
        }

        LocalChipDistribution result = calculator.calculate(
                bars,
                new BigDecimal("10.00"),
                ChipCalculationMode.COMPLETED_BAR
        );

        assertThat(result.quality()).isEqualTo(ChipDataQuality.INSUFFICIENT);
        assertThat(result.dataGaps()).contains("换手率有效覆盖低于95%");
        assertThat(result.averageCost()).isNull();
    }

    @Test
    void keepsExactlyNinetyFivePercentCoverageAuditableWithoutGuessingMissingTurnover() {
        LocalChipDistributionCalculator calculator = calculator(80, 0.95);
        List<EastMoneyKLine> bars = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            bars.add(bar(index, "9.80", "10.00", "10.20", "9.70",
                    index < 6 ? null : "2.00", "100000", "100000000"));
        }

        LocalChipDistribution result = calculator.calculate(
                bars,
                new BigDecimal("10.00"),
                ChipCalculationMode.COMPLETED_BAR
        );

        assertThat(result.quality()).isEqualTo(ChipDataQuality.VALID);
        assertThat(result.turnoverCoveragePercent()).isEqualByComparingTo("95.00");
        assertThat(result.dataGaps()).contains("少量换手率缺失日未注入新筹码");
    }

    @Test
    void clampsAbnormalTurnoverAndReportsTheQualityGap() {
        LocalChipDistributionCalculator calculator = calculator(2, 1.0);

        LocalChipDistribution result = calculator.calculate(
                List.of(
                        bar(0, "9.80", "10.00", "10.20", "9.70", "-3", "100000", "100000000"),
                        bar(1, "9.90", "10.10", "10.30", "9.80", "120", "100000", "101000000")
                ),
                new BigDecimal("10.10"),
                ChipCalculationMode.COMPLETED_BAR
        );

        assertThat(result.quality()).isEqualTo(ChipDataQuality.VALID);
        assertThat(result.dataGaps()).contains("异常换手率已按0%-100%截断");
    }

    @Test
    void exposesPriorHighResidualAndTurnoverSinceThatHigh() {
        LocalChipDistributionCalculator calculator = calculator(10, 1.0);
        List<EastMoneyKLine> bars = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            String high = index == 4 ? "15.00" : "10.50";
            String close = index == 4 ? "14.50" : "10.00";
            bars.add(bar(index, "9.80", close, high, "9.50", "10", "100000", "100000000"));
        }

        LocalChipDistribution result = calculator.calculate(
                bars,
                new BigDecimal("10.00"),
                ChipCalculationMode.COMPLETED_BAR
        );

        assertThat(result.priorHighPrice()).isEqualByComparingTo("15.00");
        assertThat(result.turnoverSincePriorHighPercent()).isEqualByComparingTo("150.00");
        assertThat(result.priorHighZoneResidualRatioPercent()).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
    }

    private LocalChipDistributionCalculator calculator(int minValidBars, double minCoverage) {
        return new LocalChipDistributionCalculator(120, 150, minValidBars, BigDecimal.valueOf(minCoverage));
    }

    private EastMoneyKLine bar(
            int dayOffset,
            String open,
            String close,
            String high,
            String low,
            String turnover,
            String volume,
            String amount
    ) {
        return new EastMoneyKLine(
                "002580",
                LocalDate.of(2026, 1, 1).plusDays(dayOffset),
                decimal(open),
                decimal(close),
                decimal(high),
                decimal(low),
                decimal(volume),
                decimal(amount),
                decimal(turnover)
        );
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
