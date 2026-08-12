package com.aistock.research.shortterm.validation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermValidationSummaryCalculatorTest {

    private final ShortTermValidationSummaryCalculator calculator = new ShortTermValidationSummaryCalculator();

    @Test
    void hidesProbabilityAndExpectedReturnBelowMinimumSampleSize() {
        ShortTermValidationSummary summary = calculator.summarize(
                "v4", "GOLDEN_CROSS_VOLUME", "TREND_EXPANSION", "T1", 3,
                List.of(sample("1.00", "3.00", "-1.00"), sample("-0.50", "1.00", "-2.00"))
        );

        assertThat(summary.status()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(summary.sampleCount()).isEqualTo(2);
        assertThat(summary.positiveRatePercent()).isNull();
        assertThat(summary.averageNetReturnPercent()).isNull();
    }

    @Test
    void aggregatesOnlyAfterTheCohortReachesItsMinimum() {
        ShortTermValidationSummary summary = calculator.summarize(
                "v4", "GOLDEN_CROSS_VOLUME", "TREND_EXPANSION", "T1", 3,
                List.of(
                        sample("1.00", "3.00", "-1.00"),
                        sample("-0.50", "1.00", "-2.00"),
                        sample("2.00", "4.00", "-0.50")
                )
        );

        assertThat(summary.status()).isEqualTo("AVAILABLE");
        assertThat(summary.sampleCount()).isEqualTo(3);
        assertThat(summary.positiveRatePercent()).isEqualByComparingTo("66.67");
        assertThat(summary.averageNetReturnPercent()).isEqualByComparingTo("0.83");
        assertThat(summary.medianNetReturnPercent()).isEqualByComparingTo("1.00");
        assertThat(summary.averageMfePercent()).isEqualByComparingTo("2.67");
        assertThat(summary.averageMaePercent()).isEqualByComparingTo("-1.17");
    }

    private ShortTermValidationSample sample(String net, String mfe, String mae) {
        return new ShortTermValidationSample(new BigDecimal(net), new BigDecimal(mfe), new BigDecimal(mae));
    }
}
