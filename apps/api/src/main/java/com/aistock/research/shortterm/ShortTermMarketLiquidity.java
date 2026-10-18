package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Quote-sample amounts and same-minute index totals deliberately retain separate labels and cutoffs. */
public record ShortTermMarketLiquidity(
        LocalDate tradeDate,
        Instant dataCutoffAt,
        String status,
        String label,
        BigDecimal sampleAmountYuan,
        BigDecimal amountCoveragePercent,
        BigDecimal currentComparableAmountYuan,
        BigDecimal previousComparableAmountYuan,
        BigDecimal amountChangePercent,
        BigDecimal currentComparableVolumeHands,
        BigDecimal previousComparableVolumeHands,
        BigDecimal volumeChangePercent,
        LocalDate comparisonDate,
        String comparisonTime,
        String source,
        String explanation,
        List<String> dataGaps
) {
    public ShortTermMarketLiquidity {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
