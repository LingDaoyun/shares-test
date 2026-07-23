package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ShortTermTechnicalSnapshot(
        LocalDate tradeDate,
        BigDecimal ma5,
        BigDecimal ma10,
        BigDecimal ma20,
        BigDecimal ma60,
        BigDecimal ma20SlopePercent,
        BigDecimal ma60SlopePercent,
        BigDecimal previousHigh20,
        BigDecimal previousHigh60,
        BigDecimal breakoutFromPreviousHigh20Percent,
        BigDecimal previousRange20Percent,
        BigDecimal high120,
        BigDecimal low120,
        BigDecimal volumeRatio5,
        BigDecimal volumeRatio20,
        BigDecimal rangePosition60,
        BigDecimal rangePosition120,
        BigDecimal distanceToMa20Percent,
        BigDecimal drawdownFrom120HighPercent,
        BigDecimal todayAmplitudePercent,
        int consecutiveAboveMa20Days,
        String rightSideSignal,
        ShortTermGoldenCrossSnapshot goldenCross
) {
}
