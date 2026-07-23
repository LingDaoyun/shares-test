package com.aistock.research.v2.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LongTermStrategyInput(
        String symbol,
        String companyName,
        String industry,
        Instant decisionAt,
        Instant dataCutoffAt,
        BigDecimal valuationDiscountScore,
        BigDecimal qualityScore,
        BigDecimal moatScore,
        BigDecimal profitabilityScore,
        BigDecimal cashFlowScore,
        BigDecimal cyclePositionScore,
        BigDecimal cycleRecoveryScore,
        BigDecimal industryLeaderScore,
        BigDecimal policyCatalystScore,
        BigDecimal liquidityScore,
        List<String> riskFlags
) {
    public LongTermStrategyInput {
        riskFlags = riskFlags == null ? List.of() : List.copyOf(riskFlags);
    }
}
