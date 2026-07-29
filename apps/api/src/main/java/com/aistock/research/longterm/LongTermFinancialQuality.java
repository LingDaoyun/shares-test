package com.aistock.research.longterm;

import java.math.BigDecimal;
import java.util.List;

public record LongTermFinancialQuality(
        int sampleYears,
        BigDecimal medianRoe,
        BigDecimal roeReference,
        int roeReferenceMetYears,
        int positiveCashFlowYears,
        BigDecimal cumulativeCashToProfitRatio,
        BigDecimal grossMarginRange,
        String status,
        String statusLabel,
        List<String> evidence,
        List<String> dataGaps
) {
}
