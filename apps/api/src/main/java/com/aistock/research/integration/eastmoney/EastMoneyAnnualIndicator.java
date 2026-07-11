package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;

public record EastMoneyAnnualIndicator(
        String symbol,
        String name,
        String reportDate,
        String dataType,
        BigDecimal roe,
        BigDecimal operatingCashFlowPerShare,
        BigDecimal grossMargin,
        BigDecimal revenueGrowth,
        BigDecimal netProfitGrowth,
        BigDecimal eps,
        BigDecimal bps
) {
}
