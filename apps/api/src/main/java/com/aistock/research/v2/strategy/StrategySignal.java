package com.aistock.research.v2.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record StrategySignal(
        StrategyCode strategyCode,
        String strategyVersion,
        String symbol,
        String companyName,
        Instant decisionAt,
        Instant dataCutoffAt,
        CandidateStage candidateStage,
        StrategyAction action,
        BigDecimal positionLimit,
        String entryCondition,
        String invalidCondition,
        BigDecimal rankScore,
        BigDecimal dataConfidence,
        BigDecimal historicalHitRate,
        BigDecimal riskReward,
        List<String> evidenceSummary,
        List<String> blockedReasons,
        Map<String, String> context
) {
    public StrategySignal {
        evidenceSummary = evidenceSummary == null ? List.of() : List.copyOf(evidenceSummary);
        blockedReasons = blockedReasons == null ? List.of() : List.copyOf(blockedReasons);
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
