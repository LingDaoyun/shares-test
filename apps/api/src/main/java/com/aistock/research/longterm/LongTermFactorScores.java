package com.aistock.research.longterm;

import java.math.BigDecimal;

public record LongTermFactorScores(
        BigDecimal financialQualityScore,
        BigDecimal moatAndIndustryScore,
        BigDecimal valuationExpectationScore,
        BigDecimal capitalAllocationScore,
        BigDecimal evidenceRiskScore,
        BigDecimal overallScore
) {
}
