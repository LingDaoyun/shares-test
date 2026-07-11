package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermRuleSet(
        int scanLimit,
        int klineLimit,
        BigDecimal minAmount,
        BigDecimal maxPe,
        BigDecimal maxPb,
        BigDecimal minVolumeRatio,
        BigDecimal maxEntryRisePercent,
        BigDecimal maxDistanceToMa20Percent,
        BigDecimal minFinancialScore
) {
}
