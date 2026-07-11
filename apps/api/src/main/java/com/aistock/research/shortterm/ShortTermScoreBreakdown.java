package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermScoreBreakdown(
        BigDecimal technicalScore,
        BigDecimal volumeScore,
        BigDecimal marketHeatScore,
        BigDecimal valuationScore,
        BigDecimal financialScore,
        BigDecimal riskPenalty,
        BigDecimal finalScore
) {
}
