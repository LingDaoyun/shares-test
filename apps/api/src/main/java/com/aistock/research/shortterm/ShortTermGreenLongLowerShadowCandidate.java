package com.aistock.research.shortterm;

import com.aistock.research.trading.QuoteFreshnessSnapshot;

import java.math.BigDecimal;

public record ShortTermGreenLongLowerShadowCandidate(
        int rank,
        String symbol,
        String name,
        String market,
        String industry,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal bodyPercent,
        BigDecimal lowerShadowPercent,
        BigDecimal amount,
        BigDecimal turnoverRate,
        QuoteFreshnessSnapshot quoteFreshness,
        boolean provisional
) {
}
