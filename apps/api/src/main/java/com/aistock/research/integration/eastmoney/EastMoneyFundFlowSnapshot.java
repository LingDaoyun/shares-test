package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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
        Instant fetchedAt,
        LocalDate tradeDate,
        Instant marketTimestamp
) {
    public EastMoneyFundFlowSnapshot(
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
        this(
                symbol,
                name,
                mainNetInflow,
                superLargeNetInflow,
                largeNetInflow,
                mediumNetInflow,
                smallNetInflow,
                mainNetInflowRatio,
                superLargeNetInflowRatio,
                largeNetInflowRatio,
                mediumNetInflowRatio,
                smallNetInflowRatio,
                sourceName,
                sourceUrl,
                fetchedAt,
                null,
                null
        );
    }

    public BigDecimal largeOrderNetInflow() {
        if (superLargeNetInflow == null && largeNetInflow == null) {
            return null;
        }
        return zero(superLargeNetInflow).add(zero(largeNetInflow));
    }

    public BigDecimal largeOrderNetInflowRatio() {
        if (superLargeNetInflowRatio == null && largeNetInflowRatio == null) {
            return null;
        }
        return zero(superLargeNetInflowRatio).add(zero(largeNetInflowRatio));
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
