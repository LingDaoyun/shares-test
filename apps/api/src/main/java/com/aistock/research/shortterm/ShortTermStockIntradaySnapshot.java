package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ShortTermStockIntradaySnapshot(
        String symbol,
        LocalDate tradeDate,
        String source,
        String volumeUnit,
        Instant fetchedAt,
        List<Point> points
) {
    public record Point(
            LocalDateTime minute,
            BigDecimal price,
            BigDecimal averagePrice,
            BigDecimal volume,
            BigDecimal amount
    ) {}
}
