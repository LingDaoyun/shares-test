package com.aistock.research.financial;

import java.math.BigDecimal;

public record FinancialMetricPoint(
        String symbol,
        String companyName,
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
