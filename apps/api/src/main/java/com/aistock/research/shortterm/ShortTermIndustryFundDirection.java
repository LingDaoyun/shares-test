package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermIndustryFundDirection(
        String code,
        String name,
        BigDecimal mainNetInflow,
        BigDecimal mainNetInflowRatio,
        BigDecimal superLargeNetInflow,
        BigDecimal largeNetInflow,
        int advancing,
        int declining,
        int constituentCount,
        BigDecimal concentrationPercent,
        String sourceUrl
) {
}
