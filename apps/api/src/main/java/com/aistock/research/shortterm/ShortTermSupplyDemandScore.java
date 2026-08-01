package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermSupplyDemandScore(
        BigDecimal mainNetInflowRatio,
        BigDecimal largeOrderNetInflowRatio,
        BigDecimal buyPressureScore,
        BigDecimal overheadPressureReliefScore,
        BigDecimal technicalRankingScore,
        BigDecimal v2RankingScore,
        BigDecimal chipContributionScore,
        BigDecimal v3RankingScore,
        BigDecimal rankingScore,
        List<String> dataGaps
) {
    public ShortTermSupplyDemandScore {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
