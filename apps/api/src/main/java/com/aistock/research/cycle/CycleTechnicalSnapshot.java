package com.aistock.research.cycle;

import java.math.BigDecimal;

public record CycleTechnicalSnapshot(
        String tradeDate,
        BigDecimal ma5,
        BigDecimal ma10,
        BigDecimal ma20,
        BigDecimal ma60,
        BigDecimal previousHigh20,
        BigDecimal previousHigh60,
        BigDecimal low20,
        BigDecimal low60,
        BigDecimal volumeRatio5,
        BigDecimal volumeRatio20,
        BigDecimal rangePosition60,
        BigDecimal closeNearHigh,
        BigDecimal reboundFrom20LowPercent,
        BigDecimal distanceToMa20Percent
) {
}
