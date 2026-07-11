package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermScanRequest(
        Integer limit,
        Integer scanLimit,
        Integer klineLimit,
        BigDecimal minAmount,
        BigDecimal maxPe,
        BigDecimal maxPb,
        BigDecimal minVolumeRatio,
        BigDecimal maxEntryRise,
        BigDecimal maxDistanceToMa20,
        BigDecimal minFinancialScore
) {
    public static ShortTermScanRequest empty() {
        return new ShortTermScanRequest(null, null, null, null, null, null, null, null, null, null);
    }
}
