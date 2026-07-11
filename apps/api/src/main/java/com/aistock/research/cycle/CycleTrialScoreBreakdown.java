package com.aistock.research.cycle;

import java.math.BigDecimal;

public record CycleTrialScoreBreakdown(
        BigDecimal catalystScore,
        BigDecimal priceLocationScore,
        BigDecimal reversalScore,
        BigDecimal volumeScore,
        BigDecimal valuationScore,
        BigDecimal finalScore
) {
}
