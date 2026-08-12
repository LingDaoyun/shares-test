package com.aistock.research.shortterm.validation;

import java.math.BigDecimal;

public record ShortTermValidationSummary(
        String ruleVersion,
        String signalFamily,
        String marketRegime,
        String horizon,
        String status,
        int minimumSampleCount,
        int sampleCount,
        BigDecimal positiveRatePercent,
        BigDecimal averageNetReturnPercent,
        BigDecimal medianNetReturnPercent,
        BigDecimal averageMfePercent,
        BigDecimal averageMaePercent
) {
}
