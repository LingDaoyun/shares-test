package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.LocalTime;

public record OvernightRuleSet(
        LocalTime entryStart,
        LocalTime entryEnd,
        LocalTime normalExitTime,
        int maxHoldingTradingDays,
        BigDecimal maxPositionRatio,
        BigDecimal maxT2PositionRatio,
        BigDecimal firstTargetFloor,
        BigDecimal firstTargetCap,
        BigDecimal secondTargetFloor,
        BigDecimal secondTargetCap,
        BigDecimal stopFloor,
        BigDecimal stopCap,
        BigDecimal trailingDrawdownPercent
) {
}
