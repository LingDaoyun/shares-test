package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermScanRequest(
        Integer limit,
        Integer scanLimit,
        Integer klineLimit,
        BigDecimal minAmount,
        BigDecimal maxPricePerShare,
        BigDecimal minVolumeRatio,
        BigDecimal maxEntryRise,
        BigDecimal maxDistanceToMa20,
        BigDecimal minFinancialScore,
        Boolean allowStaticCachePreview,
        Boolean allowChiNext
) {
    public ShortTermScanRequest(
            Integer limit,
            Integer scanLimit,
            Integer klineLimit,
            BigDecimal minAmount,
            BigDecimal minVolumeRatio,
            BigDecimal maxEntryRise,
            BigDecimal maxDistanceToMa20,
            BigDecimal minFinancialScore,
            Boolean allowStaticCachePreview
    ) {
        this(
                limit,
                scanLimit,
                klineLimit,
                minAmount,
                null,
                minVolumeRatio,
                maxEntryRise,
                maxDistanceToMa20,
                minFinancialScore,
                allowStaticCachePreview,
                null
        );
    }

    public static ShortTermScanRequest empty() {
        return new ShortTermScanRequest(null, null, null, null, null, null, null, null, null, null, null);
    }
}
