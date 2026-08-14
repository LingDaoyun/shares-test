package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermRuleSet(
        int scanLimit,
        int klineLimit,
        BigDecimal minAmount,
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
                minVolumeRatio,
                maxEntryRisePercent,
                maxDistanceToMa20Percent,
                minFinancialScore,
                false
        );
    }
}
