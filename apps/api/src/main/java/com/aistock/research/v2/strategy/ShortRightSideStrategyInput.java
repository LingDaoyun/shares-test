package com.aistock.research.v2.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public record ShortRightSideStrategyInput(
        String symbol,
        String companyName,
        String hotDirection,
        Instant decisionAt,
        Instant dataCutoffAt,
        String tradingCheckpoint,
        BigDecimal marketHotScore,
        BigDecimal rightSideStructureScore,
        BigDecimal supplyAbsorptionScore,
        BigDecimal volumeBreakoutScore,
        BigDecimal shrinkRiseScore,
        BigDecimal fundamentalFloorScore,
        BigDecimal liquidityScore,
        BigDecimal crowdingRiskScore,
        String goldenCrossState,
        Integer goldenCrossTradingDays,
        Integer goldenCrossPriorityTier,
        boolean legacyAttestationVerified,
        String legacyCandidateAction,
        String legacyAdviceAction,
        String tailSignalStatus,
        boolean evidenceAllowsBuy,
        List<String> riskFlags
) {
    public ShortRightSideStrategyInput {
        goldenCrossState = goldenCrossState == null || goldenCrossState.isBlank()
                ? "NONE"
                : goldenCrossState.trim().toUpperCase(Locale.ROOT);
        goldenCrossPriorityTier = goldenCrossPriorityTier == null ? 0 : goldenCrossPriorityTier;
        legacyCandidateAction = normalizedGateValue(legacyCandidateAction, "UNKNOWN");
        legacyAdviceAction = normalizedGateValue(legacyAdviceAction, "UNKNOWN");
        tailSignalStatus = normalizedGateValue(tailSignalStatus, "UNAVAILABLE");
        riskFlags = riskFlags == null ? List.of() : List.copyOf(riskFlags);
    }

    public ShortRightSideStrategyInput(
            String symbol,
            String companyName,
            String hotDirection,
            Instant decisionAt,
            Instant dataCutoffAt,
            String tradingCheckpoint,
            BigDecimal marketHotScore,
            BigDecimal rightSideStructureScore,
            BigDecimal supplyAbsorptionScore,
            BigDecimal volumeBreakoutScore,
            BigDecimal shrinkRiseScore,
            BigDecimal fundamentalFloorScore,
            BigDecimal liquidityScore,
            BigDecimal crowdingRiskScore,
            List<String> riskFlags
    ) {
        this(symbol, companyName, hotDirection, decisionAt, dataCutoffAt, tradingCheckpoint,
                marketHotScore, rightSideStructureScore, supplyAbsorptionScore, volumeBreakoutScore,
                shrinkRiseScore, fundamentalFloorScore, liquidityScore, crowdingRiskScore,
                "NONE", null, 0, false, "UNKNOWN", "UNKNOWN", "UNAVAILABLE", false, riskFlags);
    }

    private static String normalizedGateValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }
}
