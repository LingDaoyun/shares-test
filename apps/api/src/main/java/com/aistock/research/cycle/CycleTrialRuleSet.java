package com.aistock.research.cycle;

import java.math.BigDecimal;

public record CycleTrialRuleSet(
        BigDecimal leftTrialScoreThreshold,
        BigDecimal rightAddScoreThreshold,
        BigDecimal maxChaseRisePercent,
        BigDecimal minVolumeRatioForBreakout,
        BigDecimal stopLossPercent,
        BigDecimal pullbackZonePercent
) {
}
