package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermIndustryLeadership(
        String industry,
        int cohortSize,
        int amountRank,
        BigDecimal percentile,
        BigDecimal contribution,
        String evidence
) {
    public static ShortTermIndustryLeadership unavailable(String industry, String reason) {
        return new ShortTermIndustryLeadership(
                industry, 0, 0, null, BigDecimal.ZERO,
                reason == null || reason.isBlank() ? "行业成交额横截面不可用" : reason
        );
    }
}
