package com.aistock.research.cycle;

import java.math.BigDecimal;

public record CyclePeerValuationCompany(
        String symbol,
        String name,
        String industry,
        BigDecimal peTtm,
        BigDecimal pbRatio,
        BigDecimal amount,
        String quoteUrl
) {
}
