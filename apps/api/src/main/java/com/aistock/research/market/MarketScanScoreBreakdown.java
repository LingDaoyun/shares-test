package com.aistock.research.market;

import java.math.BigDecimal;

public record MarketScanScoreBreakdown(
        BigDecimal valuationScore,
        BigDecimal liquidityScore,
        BigDecimal priceActionScore,
        BigDecimal qualityProxyScore,
        BigDecimal riskScore,
        BigDecimal finalScore
) {
}
