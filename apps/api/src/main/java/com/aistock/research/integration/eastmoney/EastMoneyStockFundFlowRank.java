package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record EastMoneyStockFundFlowRank(
        String symbol,
        String name,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal mainNetInflow,
        BigDecimal mainNetInflowRatio,
        BigDecimal superLargeNetInflow,
        BigDecimal largeNetInflow,
        Instant marketTimestamp,
        LocalDate tradeDate,
        String sourceName,
        String sourceUrl,
        Instant fetchedAt
) {
}
