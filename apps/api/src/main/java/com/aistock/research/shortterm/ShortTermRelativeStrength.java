package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermRelativeStrength(
        BigDecimal return5,
        BigDecimal return10,
        BigDecimal return20,
        BigDecimal marketPercentile5,
        BigDecimal marketPercentile10,
        BigDecimal marketPercentile20,
        BigDecimal industryPercentile5,
        BigDecimal industryPercentile10,
        BigDecimal industryPercentile20,
        int marketSampleCount,
        int industrySampleCount,
        BigDecimal compositeScore,
        BigDecimal contribution,
        List<String> dataGaps
) {
    public ShortTermRelativeStrength {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static ShortTermRelativeStrength unavailable(String reason) {
        return new ShortTermRelativeStrength(
                null, null, null, null, null, null, null, null, null,
                0, 0, null, BigDecimal.ZERO,
                reason == null || reason.isBlank() ? List.of() : List.of(reason)
        );
    }
}
