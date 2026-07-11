package com.aistock.research.mispricing;

import java.math.BigDecimal;
import java.util.List;

public record StyleHeatSnapshot(
        String hotThemeName,
        BigDecimal heatScore,
        BigDecimal valuationPressure,
        BigDecimal crowdingPressure,
        String riskLabel,
        List<String> signals
) {
}
