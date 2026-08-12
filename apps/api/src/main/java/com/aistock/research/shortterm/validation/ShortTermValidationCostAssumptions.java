package com.aistock.research.shortterm.validation;

import java.math.BigDecimal;

public record ShortTermValidationCostAssumptions(
        BigDecimal buyCommissionPercent,
        BigDecimal sellCommissionPercent,
        BigDecimal sellStampDutyPercent,
        BigDecimal buySlippagePercent,
        BigDecimal sellSlippagePercent
) {
    public ShortTermValidationCostAssumptions {
        buyCommissionPercent = nonNegative(buyCommissionPercent);
        sellCommissionPercent = nonNegative(sellCommissionPercent);
        sellStampDutyPercent = nonNegative(sellStampDutyPercent);
        buySlippagePercent = nonNegative(buySlippagePercent);
        sellSlippagePercent = nonNegative(sellSlippagePercent);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
