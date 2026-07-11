package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record LatestMarketPrice(
        BigDecimal price,
        String source,
        LocalDate tradeDate,
        Instant marketTimestamp
) {
}
