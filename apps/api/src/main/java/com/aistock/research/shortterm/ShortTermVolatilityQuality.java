package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermVolatilityQuality(
        BigDecimal atrPercent,
        BigDecimal distanceToMa20Atr,
        BigDecimal contractionRatio5To20,
        BigDecimal breakoutExpansionRatio,
        BigDecimal breakoutFromHigh20Atr,
        String state,
        String label,
        boolean contractionBreakout,
        BigDecimal contribution,
        List<String> dataGaps
) {
    public ShortTermVolatilityQuality {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static ShortTermVolatilityQuality unavailable(String reason) {
        return new ShortTermVolatilityQuality(
                null, null, null, null, null,
                "UNAVAILABLE", "波动率待补", false, BigDecimal.ZERO,
                reason == null || reason.isBlank() ? List.of() : List.of(reason)
        );
    }
}
