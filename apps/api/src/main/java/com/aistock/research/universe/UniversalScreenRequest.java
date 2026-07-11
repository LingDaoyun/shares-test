package com.aistock.research.universe;

import java.math.BigDecimal;

public record UniversalScreenRequest(
        Integer limit,
        Integer scanLimit,
        BigDecimal minAmount,
        BigDecimal maxPe,
        BigDecimal maxPb,
        BigDecimal minFinancialScore,
        Boolean excludeSideways,
        Boolean includeNorthExchange,
        String mode
) {
}
