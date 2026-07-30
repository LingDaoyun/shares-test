package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermScoreBreakdown(
        BigDecimal technicalScore,
        BigDecimal goldenCrossScore,
        BigDecimal volumeScore,
        BigDecimal turnoverScore,
        BigDecimal closeStrengthScore,
        BigDecimal marketHeatScore,
        BigDecimal valuationScore,
        BigDecimal financialScore,
        BigDecimal riskPenalty,
        BigDecimal finalScore,
        BigDecimal stageAdjustment,
        BigDecimal rankingScore
) {
    public ShortTermScoreBreakdown(
            BigDecimal technicalScore,
            BigDecimal volumeScore,
            BigDecimal marketHeatScore,
            BigDecimal valuationScore,
            BigDecimal financialScore,
            BigDecimal riskPenalty,
            BigDecimal finalScore
    ) {
        this(
                technicalScore,
                technicalScore,
                volumeScore,
                new BigDecimal("45"),
                new BigDecimal("50"),
                marketHeatScore,
                valuationScore,
                financialScore,
                riskPenalty,
                finalScore,
                BigDecimal.ZERO,
                finalScore
        );
    }
}
