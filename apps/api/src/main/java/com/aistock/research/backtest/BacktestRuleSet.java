package com.aistock.research.backtest;

import java.math.BigDecimal;

public record BacktestRuleSet(
        int lookbackDays,
        int holdingDays,
        BigDecimal minVolumeRatio,
        BigDecimal maxVolumeRatio,
        BigDecimal maxDistanceToMa20Percent,
        BigDecimal minMa20SlopePercent,
        BigDecimal stopLossPercent,
        BigDecimal takeProfitPercent,
        BigDecimal commissionPercent,
        BigDecimal stampDutyPercent,
        BigDecimal slippagePercent,
        BigDecimal limitMovePercent,
        BigDecimal minRange60Percent,
        BigDecimal maxRange60Percent
) {
}
