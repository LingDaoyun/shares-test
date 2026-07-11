package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermHotDirection(
        String code,
        String label,
        BigDecimal heatScore,
        BigDecimal averageChangePercent,
        BigDecimal positiveRatioPercent,
        BigDecimal totalAmount,
        int sampleCount,
        List<String> leaders,
        String evidence
) {
}
