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
        BigDecimal mainNetInflowRatio,
        BigDecimal largeOrderNetInflowRatio,
        BigDecimal buyPressureScore,
        BigDecimal overheadPressureReliefScore,
        BigDecimal technicalRankingScore,
        BigDecimal v2RankingScore,
        BigDecimal chipContributionScore,
        BigDecimal v3RankingScore,
        Integer v2Rank,
        Integer v3Rank,
        Integer rankDelta,
        BigDecimal rankingScore
) {
    public ShortTermScoreBreakdown(
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
        this(
                technicalScore,
                goldenCrossScore,
                volumeScore,
                turnoverScore,
                closeStrengthScore,
                marketHeatScore,
                valuationScore,
                financialScore,
                riskPenalty,
                finalScore,
                stageAdjustment,
                null,
                null,
                new BigDecimal("35"),
                new BigDecimal("45"),
                rankingScore,
                rankingScore,
                BigDecimal.ZERO,
                rankingScore,
                null,
                null,
                null,
                rankingScore
        );
    }

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
                null,
                null,
                new BigDecimal("35"),
                new BigDecimal("45"),
                finalScore,
                finalScore,
                BigDecimal.ZERO,
                finalScore,
                null,
                null,
                null,
                finalScore
        );
    }
}
