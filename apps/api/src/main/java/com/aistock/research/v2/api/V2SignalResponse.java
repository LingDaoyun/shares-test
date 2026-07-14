package com.aistock.research.v2.api;

import com.aistock.research.v2.decision.V2RecommendationLedgerEntity;
import com.aistock.research.v2.strategy.StrategySignal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record V2SignalResponse(
        String ledgerId,
        String strategyCode,
        String strategyVersion,
        String symbol,
        String companyName,
        Instant decisionAt,
        Instant dataCutoffAt,
        String candidateStage,
        String action,
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
    public static V2SignalResponse from(StrategySignal signal, V2RecommendationLedgerEntity ledger) {
        return new V2SignalResponse(
                ledger.getLedgerId(),
                signal.strategyCode().name(),
                signal.strategyVersion(),
                signal.symbol(),
                signal.companyName(),
                signal.decisionAt(),
                signal.dataCutoffAt(),
                signal.candidateStage().name(),
                signal.action().name(),
                signal.positionLimit(),
                signal.entryCondition(),
                signal.invalidCondition(),
                signal.rankScore(),
                signal.dataConfidence(),
                signal.historicalHitRate(),
                signal.riskReward(),
                signal.evidenceSummary(),
                signal.blockedReasons(),
                signal.context());
    }
}
