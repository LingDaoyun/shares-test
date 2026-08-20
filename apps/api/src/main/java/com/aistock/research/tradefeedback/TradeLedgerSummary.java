package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeLedgerSummary(
        BigDecimal latestPrice,
        long positionQuantity,
        BigDecimal averageCost,
        BigDecimal realizedProfit,
        BigDecimal unrealizedProfit,
        BigDecimal totalProfit,
        Instant openedAt
) {
}
