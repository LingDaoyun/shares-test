package com.aistock.research.v2.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StrategySignalFactory {

    private StrategySignalFactory() {
    }

    public static StrategySignal blocked(
            StrategyCode strategyCode,
            String strategyVersion,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            StrategyAction action,
            List<String> blockedReasons,
            Map<String, String> context
    ) {
        SourceQualityStatus sourceQuality = action == StrategyAction.DATA_BLOCKED
                ? SourceQualityStatus.MISSING
                : SourceQualityStatus.SINGLE_SOURCE;
        return blocked(strategyCode, strategyVersion, symbol, companyName, decisionAt, dataCutoffAt,
                action, blockedReasons, context, sourceQuality, replayPayloadFromContext(context));
    }

    public static StrategySignal blocked(
            StrategyCode strategyCode,
            String strategyVersion,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            StrategyAction action,
            List<String> blockedReasons,
            Map<String, String> context,
            SourceQualityStatus sourceQuality,
            Map<String, Object> replayPayload
    ) {
        return new StrategySignal(
                strategyCode,
                strategyVersion,
                symbol,
                companyName,
                decisionAt,
                dataCutoffAt,
                CandidateStage.BLOCKED,
                action,
                BigDecimal.ZERO,
                "",
                "",
                null,
                new BigDecimal("0.00"),
                null,
                null,
                List.of(),
                blockedReasons,
                context,
                sourceQuality,
                replayPayload,
                SignalProvenance.RULE_ENGINE);
    }

    public static StrategySignal research(
            StrategyCode strategyCode,
            String strategyVersion,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            CandidateStage candidateStage,
            StrategyAction action,
            BigDecimal rankScore,
            BigDecimal dataConfidence,
            BigDecimal historicalHitRate,
            BigDecimal riskReward,
            Map<String, String> context
    ) {
        return research(strategyCode, strategyVersion, symbol, companyName, decisionAt, dataCutoffAt,
                candidateStage, action, rankScore, dataConfidence, historicalHitRate, riskReward,
                context, replayPayloadFromContext(context), SourceQualityStatus.SINGLE_SOURCE);
    }

    public static StrategySignal research(
            StrategyCode strategyCode,
            String strategyVersion,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            CandidateStage candidateStage,
            StrategyAction action,
            BigDecimal rankScore,
            BigDecimal dataConfidence,
            BigDecimal historicalHitRate,
            BigDecimal riskReward,
            Map<String, String> context,
            Map<String, Object> replayPayload
    ) {
        return research(strategyCode, strategyVersion, symbol, companyName, decisionAt, dataCutoffAt,
                candidateStage, action, rankScore, dataConfidence, historicalHitRate, riskReward,
                context, replayPayload, SourceQualityStatus.SINGLE_SOURCE);
    }

    private static Map<String, Object> replayPayloadFromContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(context);
    }

    public static StrategySignal research(
            StrategyCode strategyCode,
            String strategyVersion,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            CandidateStage candidateStage,
            StrategyAction action,
            BigDecimal rankScore,
            BigDecimal dataConfidence,
            BigDecimal historicalHitRate,
            BigDecimal riskReward,
            Map<String, String> context,
            Map<String, Object> replayPayload,
            SourceQualityStatus sourceQuality
    ) {
        return research(strategyCode, strategyVersion, symbol, companyName, decisionAt, dataCutoffAt,
                candidateStage, action, rankScore, dataConfidence, historicalHitRate, riskReward,
                context, replayPayload, sourceQuality, SignalProvenance.RULE_ENGINE);
    }

    public static StrategySignal research(
            StrategyCode strategyCode,
            String strategyVersion,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            CandidateStage candidateStage,
            StrategyAction action,
            BigDecimal rankScore,
            BigDecimal dataConfidence,
            BigDecimal historicalHitRate,
            BigDecimal riskReward,
            Map<String, String> context,
            Map<String, Object> replayPayload,
            SourceQualityStatus sourceQuality,
            SignalProvenance signalProvenance
    ) {
        return new StrategySignal(
                strategyCode,
                strategyVersion,
                symbol,
                companyName,
                decisionAt,
                dataCutoffAt,
                candidateStage,
                action,
                null,
                "",
                "",
                rankScore,
                dataConfidence,
                historicalHitRate,
                riskReward,
                List.of(),
                List.of(),
                context,
                sourceQuality,
                replayPayload,
                signalProvenance);
    }
}
