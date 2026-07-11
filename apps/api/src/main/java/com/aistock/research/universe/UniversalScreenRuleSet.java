package com.aistock.research.universe;

import java.math.BigDecimal;

public record UniversalScreenRuleSet(
        int limit,
        int scanLimit,
        BigDecimal minAmount,
        BigDecimal maxPe,
        BigDecimal maxPb,
        BigDecimal minFinancialScore,
        boolean excludeSideways,
        boolean includeNorthExchange,
        String mode
) {
}
