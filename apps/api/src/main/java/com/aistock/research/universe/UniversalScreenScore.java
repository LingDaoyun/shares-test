package com.aistock.research.universe;

import java.math.BigDecimal;

public record UniversalScreenScore(
        BigDecimal financialScore,
        BigDecimal valuationScore,
        BigDecimal liquidityScore,
        BigDecimal trendScore,
        BigDecimal riskScore,
        BigDecimal finalScore
) {
}
