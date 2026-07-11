package com.aistock.research.tech;

import java.math.BigDecimal;

public record TechTrackingRuleSet(
        BigDecimal coreMaxPe,
        BigDecimal coreMaxPb,
        BigDecimal hardMaxPe,
        BigDecimal hardMaxPb,
        BigDecimal pullbackWatchPercent,
        BigDecimal stopLossPercent,
        BigDecimal maxSinglePositionPercent
) {
}
