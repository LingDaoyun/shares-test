package com.aistock.research.valuation;

import java.math.BigDecimal;

public record ValuationHistoryPoint(
        String reportDate,
        String tradeDate,
        BigDecimal closePrice,
        BigDecimal eps,
        BigDecimal bps,
        BigDecimal pe,
        BigDecimal pb
) {
}
