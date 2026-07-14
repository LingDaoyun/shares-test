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
        SourceQualityStatus sourceQuality,
        Map<String, Object> replayPayload,
        SignalProvenance signalProvenance
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
        requireNonNull(signalProvenance, "signalProvenance");
        evidenceSummary = evidenceSummary == null ? List.of() : List.copyOf(evidenceSummary);
        blockedReasons = blockedReasons == null ? List.of() : List.copyOf(blockedReasons);
        context = context == null ? Map.of() : Map.copyOf(context);
        replayPayload = replayPayload == null ? Map.of() : Map.copyOf(replayPayload);
        validateStageAndAction(candidateStage, action, blockedReasons);
        validateSourceQuality(candidateStage, action, sourceQuality, dataConfidence);
        validateProvenance(action, signalProvenance);
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
                blockedReasons, context, SourceQualityStatus.VERIFIED, Map.of(),
                SignalProvenance.COMPATIBILITY_PROBE);
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
            Map<String, String> context,
            SourceQualityStatus sourceQuality
    ) {
        this(strategyCode, strategyVersion, symbol, companyName, decisionAt, dataCutoffAt,
                candidateStage, action, positionLimit, entryCondition, invalidCondition,
                rankScore, dataConfidence, historicalHitRate, riskReward, evidenceSummary,
                blockedReasons, context, sourceQuality, Map.of(),
                SignalProvenance.COMPATIBILITY_PROBE);
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
        if (blockedReasons.stream().anyMatch(reason -> reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("blockedReasons must not contain blank values");
        }
    }

    private static void validateSourceQuality(
            CandidateStage candidateStage,
            StrategyAction action,
            SourceQualityStatus sourceQuality,
            BigDecimal dataConfidence
    ) {
        if (candidateStage != CandidateStage.BLOCKED && dataConfidence == null) {
            throw new IllegalArgumentException("dataConfidence must not be null for research signals");
        }
        if (sourceQuality == SourceQualityStatus.MISSING
                && (candidateStage != CandidateStage.BLOCKED || action != StrategyAction.DATA_BLOCKED)) {
            throw new IllegalArgumentException("MISSING source quality requires DATA_BLOCKED");
        }
    }

    private static void validateProvenance(StrategyAction action, SignalProvenance signalProvenance) {
        if (signalProvenance == SignalProvenance.AI_EVIDENCE_ONLY
                && (action == StrategyAction.ADD
                || action == StrategyAction.LIGHT_TRIAL
                || action == StrategyAction.REDUCE
                || action == StrategyAction.EXIT)) {
            throw new IllegalArgumentException("AI_EVIDENCE_ONLY cannot produce " + action);
        }
    }
}
