package com.aistock.research.v2.strategy;

import java.math.BigDecimal;
import java.time.Instant;
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
                context);
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
                context);
    }
}
