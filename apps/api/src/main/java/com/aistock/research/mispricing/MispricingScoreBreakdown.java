package com.aistock.research.mispricing;

import java.math.BigDecimal;

public record MispricingScoreBreakdown(
        BigDecimal hotOverheatScore,
        BigDecimal qualityScore,
        BigDecimal valuationDiscountScore,
        BigDecimal cashflowDefenseScore,
        BigDecimal rotationTimingScore,
        BigDecimal finalScore
) {
}
