package com.aistock.research.market;

import java.math.BigDecimal;

public record MarketScanRuleSet(
        int scanLimit,
        BigDecimal minAmount,
        BigDecimal maxPe,
        BigDecimal maxPb,
        BigDecimal maxRiseForEntry,
        BigDecimal maxSinglePositionPercent,
        BigDecimal minFinancialScore,
        boolean excludeSideways,
        boolean includeNorthExchange,
        String mode
) {
}
