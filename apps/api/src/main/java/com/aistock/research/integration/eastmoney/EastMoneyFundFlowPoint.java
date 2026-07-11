package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;

public record EastMoneyFundFlowPoint(
        String symbol,
        String minute,
        BigDecimal mainNetInflow,
        BigDecimal smallNetInflow,
        BigDecimal mediumNetInflow,
        BigDecimal largeNetInflow,
        BigDecimal superLargeNetInflow
) {
}
