package com.aistock.research.tech;

import java.math.BigDecimal;

public record TechScoreBreakdown(
        BigDecimal policyScore,
        BigDecimal earningsScore,
        BigDecimal valuationScore,
        BigDecimal tradingDisciplineScore,
        BigDecimal finalScore
) {
}
