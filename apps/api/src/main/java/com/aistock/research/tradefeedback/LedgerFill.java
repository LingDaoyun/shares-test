package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerFill(
        TradeSide side,
        Instant executedAt,
        BigDecimal price,
        long quantity,
        Instant createdAt
) {

    public LedgerFill(TradeSide side, Instant executedAt, BigDecimal price, long quantity) {
        this(side, executedAt, price, quantity, executedAt);
    }
}
