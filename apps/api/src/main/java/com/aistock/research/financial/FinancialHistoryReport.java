package com.aistock.research.financial;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FinancialHistoryReport(
        String symbol,
        String companyName,
        String status,
        String statusLabel,
        int annualPointCount,
        BigDecimal qualityScore,
        BigDecimal averageRoe,
        BigDecimal averageGrossMargin,
        BigDecimal averageRevenueGrowth,
        BigDecimal averageNetProfitGrowth,
        int positiveCashFlowYears,
        int negativeRevenueGrowthYears,
        List<FinancialMetricPoint> points,
        List<String> conclusions,
        List<String> dataGaps,
        Instant generatedAt
) {
}
