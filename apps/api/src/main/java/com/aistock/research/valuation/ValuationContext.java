package com.aistock.research.valuation;

import java.math.BigDecimal;
import java.util.List;

public record ValuationContext(
        BigDecimal score,
        ValuationContextState state,
        ValuationModel applicableModel,
        BigDecimal rawPe,
        BigDecimal rawPb,
        BigDecimal peReference,
        BigDecimal pbReference,
        BigDecimal industryPercentile,
        BigDecimal historyPercentile,
        boolean normalizedEarningsUsed,
        List<String> warnings,
        List<String> evidence
) {
}
