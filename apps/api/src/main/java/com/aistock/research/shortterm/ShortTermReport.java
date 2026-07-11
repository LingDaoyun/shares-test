package com.aistock.research.shortterm;

import com.aistock.research.trading.TradingSessionSnapshot;

import java.time.Instant;
import java.util.List;

public record ShortTermReport(
        String scope,
        int universeCount,
        int reviewedCount,
        int klineReviewedCount,
        int candidateCount,
        String quoteNote,
        TradingSessionSnapshot tradingSession,
        List<String> methodology,
        ShortTermRuleSet ruleSet,
        ShortTermWeightProfile weightProfile,
        List<ShortTermCandidate> candidates,
        List<ShortTermHotDirection> hotDirections,
        ShortTermMarketSentiment marketSentiment,
        List<ShortTermRiskExclusion> exclusions,
        Instant generatedAt
) {
    public ShortTermReport(
            String scope,
            int universeCount,
            int reviewedCount,
            int klineReviewedCount,
            int candidateCount,
            String quoteNote,
            TradingSessionSnapshot tradingSession,
            List<String> methodology,
            ShortTermRuleSet ruleSet,
            ShortTermWeightProfile weightProfile,
            List<ShortTermCandidate> candidates,
            List<ShortTermHotDirection> hotDirections,
            List<ShortTermRiskExclusion> exclusions,
            Instant generatedAt
    ) {
        this(scope, universeCount, reviewedCount, klineReviewedCount, candidateCount, quoteNote, tradingSession,
                methodology, ruleSet, weightProfile, candidates, hotDirections,
                new ShortTermMarketSentiment("未计算", java.math.BigDecimal.ZERO, 0, 0, 0, 0,
                        java.math.BigDecimal.ZERO, "兼容旧报告格式，未提供市场情绪快照。"),
                exclusions, generatedAt);
    }
}
