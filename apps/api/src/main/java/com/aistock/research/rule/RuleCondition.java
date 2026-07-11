package com.aistock.research.rule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record RuleCondition(
        @NotBlank String factor,
        @NotNull Operator operator,
        @NotNull BigDecimal value,
        BigDecimal weight
) {
    public BigDecimal effectiveWeight() {
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return weight;
    }
}

