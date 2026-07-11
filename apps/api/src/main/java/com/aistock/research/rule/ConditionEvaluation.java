package com.aistock.research.rule;

import java.math.BigDecimal;

public record ConditionEvaluation(
        String factor,
        Operator operator,
        BigDecimal expected,
        BigDecimal actual,
        boolean passed,
        String message
) {
}

