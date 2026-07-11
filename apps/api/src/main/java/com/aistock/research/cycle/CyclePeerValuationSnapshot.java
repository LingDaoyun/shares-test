package com.aistock.research.cycle;

import java.math.BigDecimal;
import java.util.List;

public record CyclePeerValuationSnapshot(
        String industry,
        BigDecimal averagePeTtm,
        BigDecimal averagePbRatio,
        BigDecimal candidatePeDiscountPercent,
        BigDecimal candidatePbDiscountPercent,
        boolean valuationAdvantage,
        String conclusion,
        List<CyclePeerValuationCompany> peers
) {
}
