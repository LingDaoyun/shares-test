package com.aistock.research.market.context;

import java.util.List;

public record LongTermIndustryContext(
        String industry,
        String modelCode,
        String modelLabel,
        String cycleType,
        String cycleTypeLabel,
        List<String> evidence,
        List<String> dataGaps
) {
}
