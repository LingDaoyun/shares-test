package com.aistock.research.valuation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ValuationContextCalculatorTest {

    private final ValuationContextCalculator calculator = new ValuationContextCalculator();

    @Test
    void preservesNegativePeAsDistortedCycleEvidence() {
        ValuationContext context = calculator.evaluate(
                new BigDecimal("-8"),
                new BigDecimal("3.2"),
                new BigDecimal("45"),
                new BigDecimal("6"),
                "生猪养殖"
        );

        assertThat(context.rawPe()).isEqualByComparingTo("-8");
        assertThat(context.state()).isEqualTo(ValuationContextState.DISTORTED);
        assertThat(context.applicableModel()).isEqualTo(ValuationModel.CYCLICAL);
        assertThat(context.warnings()).anySatisfy(item -> assertThat(item).contains("当前亏损"));
    }

    @Test
    void treatsExtremePositiveMultiplesAsWarningInsteadOfExclusion() {
        ValuationContext context = calculator.evaluate(
                new BigDecimal("300"),
                new BigDecimal("45"),
                new BigDecimal("100"),
                new BigDecimal("15"),
                "机器人"
        );

        assertThat(context.state()).isEqualTo(ValuationContextState.STRETCHED);
        assertThat(context.score()).isPositive();
        assertThat(context.warnings()).isNotEmpty();
    }

    @Test
    void distinguishesMissingFromDistorted() {
        ValuationContext context = calculator.evaluate(
                null,
                null,
                new BigDecimal("45"),
                new BigDecimal("6"),
                "软件"
        );

        assertThat(context.state()).isEqualTo(ValuationContextState.MISSING);
        assertThat(context.rawPe()).isNull();
        assertThat(context.score()).isEqualByComparingTo("50.00");
    }
}
