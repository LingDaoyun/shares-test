package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermScoreBreakdown(
        BigDecimal technicalScore,
        BigDecimal goldenCrossScore,
        BigDecimal volumeScore,
        BigDecimal turnoverScore,
        BigDecimal closeStrengthScore,
        BigDecimal supportReversalScore,
        BigDecimal marketHeatScore,
        BigDecimal valuationScore,
        BigDecimal financialScore,
        BigDecimal riskPenalty,
        BigDecimal finalScore,
        BigDecimal stageAdjustment,
        BigDecimal mainNetInflowRatio,
        BigDecimal largeOrderNetInflowRatio,
        BigDecimal buyPressureScore,
        BigDecimal fundFlowAdjustment,
        BigDecimal overheadPressureReliefScore,
        BigDecimal technicalRankingScore,
        BigDecimal v2RankingScore,
        BigDecimal chipContributionScore,
        BigDecimal v3RankingScore,
        Integer v2Rank,
        Integer v3Rank,
        Integer rankDelta,
        BigDecimal relativeStrengthContribution,
        BigDecimal industryLeadershipContribution,
        BigDecimal marketHeatContribution,
        BigDecimal crossSectionAdjustment,
        BigDecimal rankingScore,
        BigDecimal volatilityContribution,
        BigDecimal visibleRankingAdjustment
) {
    public ShortTermScoreBreakdown {
        volatilityContribution = volatilityContribution == null ? BigDecimal.ZERO : volatilityContribution;
        visibleRankingAdjustment = visibleRankingAdjustment == null
                ? rankingScore == null || technicalRankingScore == null
                ? BigDecimal.ZERO
                : rankingScore.subtract(technicalRankingScore)
                : visibleRankingAdjustment;
    }

    public ShortTermScoreBreakdown(
            BigDecimal technicalScore,
            BigDecimal goldenCrossScore,
            BigDecimal volumeScore,
            BigDecimal turnoverScore,
            BigDecimal closeStrengthScore,
            BigDecimal supportReversalScore,
            BigDecimal marketHeatScore,
            BigDecimal valuationScore,
            BigDecimal financialScore,
            BigDecimal riskPenalty,
            BigDecimal finalScore,
            BigDecimal stageAdjustment,
            BigDecimal mainNetInflowRatio,
            BigDecimal largeOrderNetInflowRatio,
            BigDecimal buyPressureScore,
            BigDecimal fundFlowAdjustment,
            BigDecimal overheadPressureReliefScore,
            BigDecimal technicalRankingScore,
            BigDecimal v2RankingScore,
            BigDecimal chipContributionScore,
            BigDecimal v3RankingScore,
            Integer v2Rank,
            Integer v3Rank,
            Integer rankDelta,
            BigDecimal relativeStrengthContribution,
            BigDecimal industryLeadershipContribution,
            BigDecimal marketHeatContribution,
            BigDecimal crossSectionAdjustment,
            BigDecimal rankingScore
    ) {
        this(
                technicalScore, goldenCrossScore, volumeScore, turnoverScore, closeStrengthScore,
                supportReversalScore, marketHeatScore, valuationScore, financialScore, riskPenalty,
                finalScore, stageAdjustment, mainNetInflowRatio, largeOrderNetInflowRatio,
                buyPressureScore, fundFlowAdjustment, overheadPressureReliefScore,
                technicalRankingScore, v2RankingScore, chipContributionScore, v3RankingScore,
                v2Rank, v3Rank, rankDelta, relativeStrengthContribution,
                industryLeadershipContribution, marketHeatContribution, crossSectionAdjustment,
                rankingScore, BigDecimal.ZERO,
                rankingScore == null || technicalRankingScore == null
                        ? BigDecimal.ZERO : rankingScore.subtract(technicalRankingScore)
        );
    }
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
                BigDecimal.ZERO,
                marketHeatScore,
                valuationScore,
                financialScore,
                riskPenalty,
                finalScore,
                stageAdjustment,
                null,
                null,
                new BigDecimal("35"),
                BigDecimal.ZERO,
                new BigDecimal("45"),
                rankingScore,
                rankingScore,
                BigDecimal.ZERO,
                rankingScore,
                null,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                rankingScore,
                BigDecimal.ZERO,
                rankingScore == null ? BigDecimal.ZERO : rankingScore.subtract(rankingScore)
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
                BigDecimal.ZERO,
                marketHeatScore,
                valuationScore,
                financialScore,
                riskPenalty,
                finalScore,
                BigDecimal.ZERO,
                null,
                null,
                new BigDecimal("35"),
                BigDecimal.ZERO,
                new BigDecimal("45"),
                finalScore,
                finalScore,
                BigDecimal.ZERO,
                finalScore,
                null,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                finalScore,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }
}
