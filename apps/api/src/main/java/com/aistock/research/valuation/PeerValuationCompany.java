package com.aistock.research.valuation;

import java.math.BigDecimal;

public record PeerValuationCompany(
        String symbol,
        String companyName,
        String industry,
        String themeCode,
        BigDecimal peTtm,
        BigDecimal pbRatio,
        BigDecimal latestPrice,
        BigDecimal amount,
        String relationType
) {
}
