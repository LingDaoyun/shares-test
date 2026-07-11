package com.aistock.research.factor;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class RatioScale {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private RatioScale() {
    }

    public static BigDecimal fromPercentPoints(String value) {
        return new BigDecimal(value)
                .divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    public static BigDecimal toPercentPoints(BigDecimal ratio) {
        return ratio == null ? null : ratio.multiply(ONE_HUNDRED).stripTrailingZeros();
    }
}
