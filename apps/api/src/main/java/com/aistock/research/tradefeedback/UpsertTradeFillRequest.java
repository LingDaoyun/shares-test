package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;

public record UpsertTradeFillRequest(
        TradeSide side,
        Instant executedAt,
        BigDecimal price,
        long quantity
) {
}
