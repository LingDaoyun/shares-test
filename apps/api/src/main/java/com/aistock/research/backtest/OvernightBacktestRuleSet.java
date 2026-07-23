package com.aistock.research.backtest;

import java.math.BigDecimal;

public record OvernightBacktestRuleSet(
        int lookbackDays,
        BigDecimal firstTargetPercent,
        BigDecimal secondTargetPercent,
        BigDecimal hardStopPercent,
        int maxHoldingTradingDays,
        BigDecimal commissionPercent,
        BigDecimal stampDutyPercent,
        BigDecimal slippagePercent,
        BigDecimal limitMovePercent,
        BigDecimal minVolumeRatio,
        BigDecimal maxDistanceToMa20Percent,
        BigDecimal trailingDrawdownPercent
) {
}
