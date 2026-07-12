package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StrategyFeedbackSummary(
        String sourceModule,
        String ruleVersion,
        String horizon,
        int sampleCount,
        int positiveCount,
        BigDecimal positiveRate,
        BigDecimal averageReturn,
        BigDecimal medianReturn,
        BigDecimal averageRunup,
        BigDecimal averageDrawdown,
        BigDecimal averageExecutionDeviation,
        int executionDeviationSampleCount,
        LocalDate sampleStart,
        LocalDate sampleEnd,
        boolean promptEligible,
        boolean adjustmentEligible,
        BigDecimal reliabilityAdjustment
) {
}
