package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermRiskExclusion(
        String symbol,
        String name,
        String market,
        String industry,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal amount,
        BigDecimal peTtm,
        BigDecimal pbRatio,
        String category,
        String reason,
        String evidence,
        String sourceUrl
) {
}
