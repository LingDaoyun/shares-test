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
        Map<String, String> context,
        SourceQualityStatus sourceQuality
) {
    public StrategySignal {
        requireNonNull(strategyCode, "strategyCode");
        requireNonBlank(strategyVersion, "strategyVersion");
        requireNonBlank(symbol, "symbol");
        requireNonBlank(companyName, "companyName");
        requireNonNull(decisionAt, "decisionAt");
        requireNonNull(dataCutoffAt, "dataCutoffAt");
        requireNonNull(candidateStage, "candidateStage");
        requireNonNull(action, "action");
        requireNonNull(sourceQuality, "sourceQuality");
        evidenceSummary = evidenceSummary == null ? List.of() : List.copyOf(evidenceSummary);
        blockedReasons = blockedReasons == null ? List.of() : List.copyOf(blockedReasons);
        context = context == null ? Map.of() : Map.copyOf(context);
        validateStageAndAction(candidateStage, action, blockedReasons);
    }

    public StrategySignal(
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
        this(strategyCode, strategyVersion, symbol, companyName, decisionAt, dataCutoffAt,
                candidateStage, action, positionLimit, entryCondition, invalidCondition,
                rankScore, dataConfidence, historicalHitRate, riskReward, evidenceSummary,
                blockedReasons, context, SourceQualityStatus.VERIFIED);
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void validateStageAndAction(
            CandidateStage candidateStage,
            StrategyAction action,
            List<String> blockedReasons
    ) {
        boolean blockedAction = action == StrategyAction.DATA_BLOCKED || action == StrategyAction.RISK_BLOCKED;
        if (candidateStage == CandidateStage.BLOCKED && (!blockedAction || blockedReasons.isEmpty())) {
            throw new IllegalArgumentException("BLOCKED stage requires a blocked action and blockedReasons");
        }
        if (blockedAction && candidateStage != CandidateStage.BLOCKED) {
            throw new IllegalArgumentException(action + " action requires BLOCKED stage");
        }
    }
}
