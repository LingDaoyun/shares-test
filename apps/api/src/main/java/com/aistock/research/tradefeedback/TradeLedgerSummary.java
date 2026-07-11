package com.aistock.research.tradefeedback;

import java.math.BigDecimal;

public record TradeLedgerSummary(
        BigDecimal latestPrice,
        long positionQuantity,
        BigDecimal averageCost,
        BigDecimal realizedProfit,
        BigDecimal unrealizedProfit,
        BigDecimal totalProfit
) {
}
