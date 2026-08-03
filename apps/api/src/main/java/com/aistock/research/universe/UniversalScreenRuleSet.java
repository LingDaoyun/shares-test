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
        String mode,
        boolean allowChiNext
) {
    public UniversalScreenRuleSet(
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
        this(
                limit,
                scanLimit,
                minAmount,
                maxPe,
                maxPb,
                minFinancialScore,
                excludeSideways,
                includeNorthExchange,
                mode,
                false
        );
    }
}
