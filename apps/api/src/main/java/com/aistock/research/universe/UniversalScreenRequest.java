package com.aistock.research.universe;

import java.math.BigDecimal;

public record UniversalScreenRequest(
        Integer limit,
        Integer scanLimit,
        BigDecimal minAmount,
        BigDecimal maxPe,
        BigDecimal maxPb,
        BigDecimal minFinancialScore,
        Boolean excludeSideways,
        Boolean includeNorthExchange,
        String mode,
        Boolean allowChiNext
) {
    public UniversalScreenRequest(
            Integer limit,
            Integer scanLimit,
            BigDecimal minAmount,
            BigDecimal maxPe,
            BigDecimal maxPb,
            BigDecimal minFinancialScore,
            Boolean excludeSideways,
            Boolean includeNorthExchange,
            String mode
    ) {
        this(
                limit,
                scanLimit,
                minAmount,
                maxPe,
                maxPb,
                minFinancialScore,
                excludeSideways,
                includeNorthExchange,
                mode,
                null
        );
    }
}
