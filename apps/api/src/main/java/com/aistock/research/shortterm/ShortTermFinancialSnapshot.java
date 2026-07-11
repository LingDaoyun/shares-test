package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermFinancialSnapshot(
        String reportDate,
        String dataType,
        BigDecimal roe,
        BigDecimal operatingCashFlowPerShare,
        BigDecimal grossMargin,
        BigDecimal revenueGrowth,
        BigDecimal netProfitGrowth,
        BigDecimal averageRoe,
        int positiveCashFlowYears,
        BigDecimal qualityScore,
        String statusLabel,
        List<String> dataGaps
) {
}
