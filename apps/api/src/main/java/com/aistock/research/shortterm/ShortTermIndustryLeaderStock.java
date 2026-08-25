package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ShortTermIndustryLeaderStock(
        int rank,
        String symbol,
        String name,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal mainNetInflow,
        BigDecimal mainNetInflowRatio,
        BigDecimal superLargeNetInflow,
        BigDecimal largeNetInflow,
        LocalDate tradeDate,
        Instant marketTimestamp,
        String sourceUrl
) {
}
