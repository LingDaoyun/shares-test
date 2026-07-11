package com.aistock.research.factor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RatioScaleTest {

    @Test
    void convertsBetweenPercentagePointsAndStoredDecimalRatio() {
        BigDecimal stored = RatioScale.fromPercentPoints("14.26");

        assertThat(stored).isEqualByComparingTo("0.1426");
        assertThat(RatioScale.toPercentPoints(stored)).isEqualByComparingTo("14.26");
    }
}
