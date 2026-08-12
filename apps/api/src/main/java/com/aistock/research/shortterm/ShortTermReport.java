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
        ShortTermMarketFundDirection marketFundDirection,
        List<ShortTermRiskExclusion> exclusions,
        Map<String, String> tradeCaptureTokens,
        ShortTermCoverageSnapshot coverage,
        List<String> reviewedSymbols,
        Instant dataCutoffAt,
        Instant generatedAt,
        ShortTermTechnicalReviewCoverage technicalReviewCoverage,
        ShortTermCrossSectionContext crossSectionContext,
        ShortTermMarketRegime marketRegime
) {
    public ShortTermReport {
        methodology = methodology == null ? List.of() : List.copyOf(methodology);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        hotDirections = hotDirections == null ? List.of() : List.copyOf(hotDirections);
        marketFundDirection = marketFundDirection == null
                ? ShortTermMarketFundDirection.unavailable(null)
                : marketFundDirection;
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        tradeCaptureTokens = tradeCaptureTokens == null ? Map.of() : Map.copyOf(tradeCaptureTokens);
        coverage = coverage == null ? ShortTermCoverageSnapshot.unreliable() : coverage;
        reviewedSymbols = reviewedSymbols == null ? List.of() : List.copyOf(reviewedSymbols);
        technicalReviewCoverage = technicalReviewCoverage == null
                ? ShortTermTechnicalReviewCoverage.unavailable()
                : technicalReviewCoverage;
        crossSectionContext = crossSectionContext == null
                ? ShortTermCrossSectionContext.unavailable()
                : crossSectionContext;
        marketRegime = marketRegime == null
                ? ShortTermMarketRegime.unavailable("历史报告未包含市场状态快照")
                : marketRegime;
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
            ShortTermMarketFundDirection marketFundDirection,
            List<ShortTermRiskExclusion> exclusions,
            Map<String, String> tradeCaptureTokens,
            ShortTermCoverageSnapshot coverage,
            List<String> reviewedSymbols,
            Instant dataCutoffAt,
            Instant generatedAt,
            ShortTermTechnicalReviewCoverage technicalReviewCoverage,
            ShortTermCrossSectionContext crossSectionContext
    ) {
        this(
                scope, universeCount, reviewedCount, klineReviewedCount, candidateCount,
                quoteNote, tradingSession, methodology, ruleSet, weightProfile, candidates,
                hotDirections, marketSentiment, marketFundDirection, exclusions, tradeCaptureTokens,
                coverage, reviewedSymbols, dataCutoffAt, generatedAt,
                technicalReviewCoverage, crossSectionContext,
                ShortTermMarketRegime.unavailable("历史报告未包含市场状态快照")
        );
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
            ShortTermMarketFundDirection marketFundDirection,
            List<ShortTermRiskExclusion> exclusions,
            Map<String, String> tradeCaptureTokens,
            ShortTermCoverageSnapshot coverage,
            List<String> reviewedSymbols,
            Instant dataCutoffAt,
            Instant generatedAt
    ) {
        this(
                scope, universeCount, reviewedCount, klineReviewedCount, candidateCount,
                quoteNote, tradingSession, methodology, ruleSet, weightProfile, candidates,
                hotDirections, marketSentiment, marketFundDirection, exclusions, tradeCaptureTokens,
                coverage, reviewedSymbols, dataCutoffAt, generatedAt,
                ShortTermTechnicalReviewCoverage.unavailable(),
                ShortTermCrossSectionContext.unavailable(),
                ShortTermMarketRegime.unavailable("历史报告未包含市场状态快照")
        );
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
                methodology, ruleSet, weightProfile, candidates, hotDirections, marketSentiment,
                ShortTermMarketFundDirection.unavailable(null), exclusions, tradeCaptureTokens,
                ShortTermCoverageSnapshot.unreliable(), List.of(), null, generatedAt);
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
            ShortTermCoverageSnapshot coverage,
            List<String> reviewedSymbols,
            Instant dataCutoffAt,
            Instant generatedAt
    ) {
        this(scope, universeCount, reviewedCount, klineReviewedCount, candidateCount, quoteNote, tradingSession,
                methodology, ruleSet, weightProfile, candidates, hotDirections, marketSentiment,
                ShortTermMarketFundDirection.unavailable(null), exclusions, tradeCaptureTokens,
                coverage, reviewedSymbols, dataCutoffAt, generatedAt);
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
                methodology, ruleSet, weightProfile, candidates, hotDirections, marketSentiment,
                ShortTermMarketFundDirection.unavailable(null), exclusions, Map.of(),
                ShortTermCoverageSnapshot.unreliable(), List.of(), null, generatedAt);
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
                ShortTermMarketFundDirection.unavailable(null), exclusions, Map.of(),
                ShortTermCoverageSnapshot.unreliable(), List.of(), null, generatedAt);
    }
}
