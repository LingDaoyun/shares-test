package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeFillSnapshot(
        String fillId,
        String caseId,
        String side,
        Instant executedAt,
        BigDecimal price,
        long quantity,
        Instant createdAt,
        Instant updatedAt
) {

    LedgerFill toLedgerFill() {
        return new LedgerFill(TradeSide.valueOf(side), executedAt, price, quantity, createdAt);
    }
}
