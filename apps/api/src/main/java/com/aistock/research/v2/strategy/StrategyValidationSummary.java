package com.aistock.research.v2.strategy;

import java.math.BigDecimal;

public record StrategyValidationSummary(
        StrategyCode strategyCode,
        String strategyVersion,
        int sampleCount,
        BigDecimal hitRate,
        BigDecimal maxDrawdown,
        String validationWindow
) {
}
