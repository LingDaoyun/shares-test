package com.aistock.research.mispricing;

import java.math.BigDecimal;

public record MispricingRuleSet(
        BigDecimal hotOverheatThreshold,
        BigDecimal maxPeForValue,
        BigDecimal maxPbForValue,
        BigDecimal minQualityScore,
        BigDecimal preferredPullbackPercent,
        BigDecimal stopLossPercent,
        int scanLimit
) {
}
