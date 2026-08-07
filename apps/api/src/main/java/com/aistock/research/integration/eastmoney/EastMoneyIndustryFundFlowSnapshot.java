package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record EastMoneyIndustryFundFlowSnapshot(
        String code,
        String name,
        BigDecimal mainNetInflow,
        BigDecimal mainNetInflowRatio,
        BigDecimal superLargeNetInflow,
        BigDecimal superLargeNetInflowRatio,
        BigDecimal largeNetInflow,
        BigDecimal largeNetInflowRatio,
        int advancing,
        int declining,
        int constituentCount,
        String sourceName,
        String sourceUrl,
        Instant fetchedAt,
        LocalDate tradeDate,
        Instant marketTimestamp
) {
}
