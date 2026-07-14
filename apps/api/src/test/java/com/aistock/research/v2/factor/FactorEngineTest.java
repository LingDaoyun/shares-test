package com.aistock.research.v2.factor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactorEngineTest {

    private final FactorEngine engine = new FactorEngine();

    @Test
    void missingDataReducesConfidenceInsteadOfReturningZeroScore() {
        FactorDefinition definition = new FactorDefinition(
                "TURNOVER_STABILITY",
                "换手稳定性",
                "SHORT_RIGHT_SIDE",
                "ratio",
                FactorDirection.HIGHER_IS_BETTER,
                "turnover_stability",
                FactorMissingPolicy.REDUCE_CONFIDENCE,
                "v2.0.0");

        FactorValue value = engine.evaluate(definition, new FactorInput("002714", Map.of()));

        assertThat(value.rawValue()).isNull();
        assertThat(value.normalizedValue()).isNull();
        assertThat(value.dataConfidenceImpact()).isEqualByComparingTo(new BigDecimal("-15.00"));
        assertThat(value.missingReason()).isEqualTo("MISSING_REQUIRED_FIELD:turnover_stability");
    }

    @Test
    void validatesExpectedUnitBeforeScoring() {
        FactorDefinition definition = new FactorDefinition(
                "AMOUNT_20D_MEDIAN",
                "20日成交额中位数",
                "SHORT_RIGHT_SIDE",
                "cny",
                FactorDirection.HIGHER_IS_BETTER,
                "amount_20d_median",
                FactorMissingPolicy.BLOCK,
                "v2.0.0");

        FactorInput input = new FactorInput("002714", Map.of(
                "amount_20d_median", new FactorInput.Measure(new BigDecimal("300000000"), "ratio")));

        assertThatThrownBy(() -> engine.evaluate(definition, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNIT_MISMATCH:AMOUNT_20D_MEDIAN expected cny but got ratio");
    }

    @Test
    void normalizesHigherIsBetterValuesBetweenZeroAndOneHundred() {
        FactorDefinition definition = new FactorDefinition(
                "RELATIVE_STRENGTH_20D",
                "20日相对强度",
                "SHORT_RIGHT_SIDE",
                "ratio",
                FactorDirection.HIGHER_IS_BETTER,
                "rs_20d",
                FactorMissingPolicy.REDUCE_CONFIDENCE,
                "v2.0.0");

        FactorInput input = new FactorInput("002714", Map.of(
                "rs_20d", new FactorInput.Measure(new BigDecimal("0.63"), "ratio")));

        FactorValue value = engine.evaluate(definition, input);

        assertThat(value.rawValue()).isEqualByComparingTo(new BigDecimal("0.63"));
        assertThat(value.normalizedValue()).isEqualByComparingTo(new BigDecimal("63.00"));
        assertThat(value.dataConfidenceImpact()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(value.missingReason()).isEmpty();
    }
}
