package com.aistock.research.v2.factor;

import java.math.BigDecimal;

public record FactorValue(
        String factorCode,
        String symbol,
        BigDecimal rawValue,
        BigDecimal normalizedValue,
        BigDecimal dataConfidenceImpact,
        String missingReason
) {
}
