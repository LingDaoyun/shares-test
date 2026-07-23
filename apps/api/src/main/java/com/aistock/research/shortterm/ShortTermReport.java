package com.aistock.research.shortterm;

import com.aistock.research.trading.TradingSessionSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        Map<String, String> tradeCaptureTokens,
        ShortTermCoverageSnapshot coverage,
        List<String> reviewedSymbols,
        Instant dataCutoffAt,
        Instant generatedAt
) {
    public ShortTermReport {
        methodology = methodology == null ? List.of() : List.copyOf(methodology);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        hotDirections = hotDirections == null ? List.of() : List.copyOf(hotDirections);
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        tradeCaptureTokens = tradeCaptureTokens == null ? Map.of() : Map.copyOf(tradeCaptureTokens);
        coverage = coverage == null ? ShortTermCoverageSnapshot.unreliable() : coverage;
        reviewedSymbols = reviewedSymbols == null ? List.of() : List.copyOf(reviewedSymbols);
    }

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
            ShortTermMarketSentiment marketSentiment,
            List<ShortTermRiskExclusion> exclusions,
            Map<String, String> tradeCaptureTokens,
            Instant generatedAt
    ) {
        this(scope, universeCount, reviewedCount, klineReviewedCount, candidateCount, quoteNote, tradingSession,
                methodology, ruleSet, weightProfile, candidates, hotDirections, marketSentiment, exclusions,
                tradeCaptureTokens, ShortTermCoverageSnapshot.unreliable(), List.of(), null, generatedAt);
    }

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
            ShortTermMarketSentiment marketSentiment,
            List<ShortTermRiskExclusion> exclusions,
            Instant generatedAt
    ) {
        this(scope, universeCount, reviewedCount, klineReviewedCount, candidateCount, quoteNote, tradingSession,
                methodology, ruleSet, weightProfile, candidates, hotDirections, marketSentiment, exclusions,
                Map.of(), ShortTermCoverageSnapshot.unreliable(), List.of(), null, generatedAt);
    }

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
                exclusions, Map.of(), ShortTermCoverageSnapshot.unreliable(), List.of(), null, generatedAt);
    }
}
