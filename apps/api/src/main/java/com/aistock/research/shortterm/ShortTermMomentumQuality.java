package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermMomentumQuality(
        BigDecimal turnoverRatePercent,
        String turnoverBand,
        BigDecimal turnoverScore,
        BigDecimal latestUpperShadowPercent,
        BigDecimal bullishUpperShadowMedian3Percent,
        BigDecimal closeLocationPercent,
        String closeStrengthLabel,
        BigDecimal closeStrengthScore,
        boolean provisional,
        boolean extremeUpperShadow,
        List<String> dataGaps
) {
    public ShortTermMomentumQuality {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static ShortTermMomentumQuality unavailable() {
        return new ShortTermMomentumQuality(
                null,
                "UNAVAILABLE",
                new BigDecimal("45.00"),
                null,
                null,
                null,
                "K线强度待补",
                new BigDecimal("50.00"),
                false,
                false,
                List.of("换手率或 OHLC 数据不足")
        );
    }
}
