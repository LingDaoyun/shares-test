package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermRuleSet(
        int scanLimit,
        int klineLimit,
        BigDecimal minAmount,
        BigDecimal maxPricePerShare,
        BigDecimal minVolumeRatio,
        BigDecimal maxEntryRisePercent,
        BigDecimal maxDistanceToMa20Percent,
        BigDecimal minFinancialScore,
        boolean allowChiNext
) {
    public ShortTermRuleSet(
            int scanLimit,
            int klineLimit,
            BigDecimal minAmount,
            BigDecimal minVolumeRatio,
            BigDecimal maxEntryRisePercent,
            BigDecimal maxDistanceToMa20Percent,
            BigDecimal minFinancialScore
    ) {
        this(
                scanLimit,
                klineLimit,
                minAmount,
                new BigDecimal("100"),
                minVolumeRatio,
                maxEntryRisePercent,
                maxDistanceToMa20Percent,
                minFinancialScore,
                false
        );
    }
}
