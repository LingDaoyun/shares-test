package com.aistock.research.shortterm.validation;

import com.aistock.research.tradefeedback.MarketBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermHorizonOutcomeCalculatorTest {

    private final ShortTermHorizonOutcomeCalculator calculator = new ShortTermHorizonOutcomeCalculator();
    private final ShortTermValidationCostAssumptions costs = new ShortTermValidationCostAssumptions(
            decimal("0.03"), decimal("0.03"), decimal("0.05"), decimal("0.05"), decimal("0.05"));

    @Test
    void evaluatesExactT2TradingDateAndIgnoresLaterBars() {
        LocalDate t1 = LocalDate.parse("2026-08-13");
        LocalDate t2 = LocalDate.parse("2026-08-14");
        List<MarketBar> bars = List.of(
                bar(t1, "102", "104", "97"),
                bar(t2, "105", "106", "101"),
                bar(LocalDate.parse("2026-08-17"), "180", "200", "170")
        );

        ShortTermHorizonEvaluation result = calculator.evaluate(
                "T2", LocalDate.parse("2026-08-12"), t2, decimal("100"), bars, costs);

        assertThat(result.status()).isEqualTo("MATURED");
        assertThat(result.evaluationDate()).isEqualTo(t2);
        assertThat(result.grossReturnPercent()).isEqualByComparingTo("5.0000");
        assertThat(result.netReturnPercent()).isEqualByComparingTo("4.7797");
        assertThat(result.maxFavorableExcursionPercent()).isEqualByComparingTo("6.0000");
        assertThat(result.maxAdverseExcursionPercent()).isEqualByComparingTo("-3.0000");
    }

    @Test
    void missingTargetTradingDayIsUnavailableAndNeverShiftsForward() {
        LocalDate t1 = LocalDate.parse("2026-08-13");
        LocalDate t2 = LocalDate.parse("2026-08-14");

        ShortTermHorizonEvaluation result = calculator.evaluate(
                "T1", LocalDate.parse("2026-08-12"), t1, decimal("100"),
                List.of(bar(t2, "105", "106", "101")), costs);

        assertThat(result.status()).isEqualTo("UNAVAILABLE_SUSPENDED_OR_MISSING");
        assertThat(result.evaluationDate()).isEqualTo(t1);
        assertThat(result.evaluationPrice()).isNull();
    }

    @Test
    void invalidBaselineFailsClosed() {
        ShortTermHorizonEvaluation result = calculator.evaluate(
                "T1",
                LocalDate.parse("2026-08-12"),
                LocalDate.parse("2026-08-13"),
                null,
                List.of(bar(LocalDate.parse("2026-08-13"), "102", "104", "97")),
                costs
        );

        assertThat(result.status()).isEqualTo("UNAVAILABLE_BASELINE");
        assertThat(result.netReturnPercent()).isNull();
    }

    private MarketBar bar(LocalDate date, String close, String high, String low) {
        return new MarketBar(date, decimal(close), decimal(high), decimal(low));
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
