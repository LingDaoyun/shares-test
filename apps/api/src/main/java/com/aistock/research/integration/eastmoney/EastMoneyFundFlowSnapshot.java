package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.Instant;

public record EastMoneyFundFlowSnapshot(
        String symbol,
        String name,
        BigDecimal mainNetInflow,
        BigDecimal superLargeNetInflow,
        BigDecimal largeNetInflow,
        BigDecimal mediumNetInflow,
        BigDecimal smallNetInflow,
        BigDecimal mainNetInflowRatio,
        BigDecimal superLargeNetInflowRatio,
        BigDecimal largeNetInflowRatio,
        BigDecimal mediumNetInflowRatio,
        BigDecimal smallNetInflowRatio,
        String sourceName,
        String sourceUrl,
        Instant fetchedAt
) {
    public BigDecimal largeOrderNetInflow() {
        if (superLargeNetInflow == null && largeNetInflow == null) {
            return null;
        }
        return zero(superLargeNetInflow).add(zero(largeNetInflow));
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
