package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeFillView(
        String fillId,
        TradeSide side,
        Instant executedAt,
        BigDecimal price,
        long quantity,
        Instant createdAt,
        Instant updatedAt
) {
}
