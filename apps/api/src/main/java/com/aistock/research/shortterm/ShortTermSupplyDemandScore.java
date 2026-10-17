package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermSupplyDemandScore(
        BigDecimal mainNetInflowRatio,
        BigDecimal largeOrderNetInflowRatio,
        BigDecimal buyPressureScore,
        BigDecimal fundFlowAdjustment,
        BigDecimal overheadPressureReliefScore,
        BigDecimal technicalRankingScore,
        BigDecimal v2RankingScore,
        BigDecimal chipContributionScore,
        BigDecimal v3RankingScore,
        BigDecimal rankingScore,
        List<String> dataGaps,
        BigDecimal chipPressurePenalty,
        String chipPressureSummary
) {
    public ShortTermSupplyDemandScore(
            BigDecimal mainNetInflowRatio,
            BigDecimal largeOrderNetInflowRatio,
            BigDecimal buyPressureScore,
            BigDecimal fundFlowAdjustment,
            BigDecimal overheadPressureReliefScore,
            BigDecimal technicalRankingScore,
            BigDecimal v2RankingScore,
            BigDecimal chipContributionScore,
            BigDecimal v3RankingScore,
            BigDecimal rankingScore,
            List<String> dataGaps
    ) {
        this(mainNetInflowRatio, largeOrderNetInflowRatio, buyPressureScore,
                fundFlowAdjustment, overheadPressureReliefScore, technicalRankingScore,
                v2RankingScore, chipContributionScore, v3RankingScore, rankingScore,
                dataGaps, null, null);
    }

    public ShortTermSupplyDemandScore {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
