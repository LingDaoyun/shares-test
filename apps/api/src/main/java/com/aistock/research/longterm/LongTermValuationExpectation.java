package com.aistock.research.longterm;

import java.math.BigDecimal;
import java.util.List;

public record LongTermValuationExpectation(
        String metricCode,
        String metricLabel,
        BigDecimal impliedExpectationPercent,
        BigDecimal evidenceExpectationPercent,
        BigDecimal pessimisticValue,
        BigDecimal baseValue,
        BigDecimal optimisticValue,
        BigDecimal discountToBasePercent,
        BigDecimal targetMarginOfSafetyPercent,
        BigDecimal entryReferencePrice,
        boolean normalizedEarningsUsed,
        String confidence,
        String confidenceLabel,
        List<String> evidence,
        List<String> dataGaps
) {
}
