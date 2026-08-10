package com.aistock.research.market;

import com.aistock.research.universe.UniversalScreenExclusion;
import com.aistock.research.universe.UniversalScreenCoverage;
import com.aistock.research.universe.UniversalScreenStageStats;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MarketScanReport(
        String scope,
        int universeCount,
        int reviewedCount,
        int candidateCount,
        String quoteNote,
        UniversalScreenCoverage coverage,
        List<String> methodology,
        MarketScanRuleSet ruleSet,
        List<UniversalScreenStageStats> stageStats,
        List<MarketScanCandidate> candidates,
        List<UniversalScreenExclusion> exclusionsSample,
        Map<String, String> tradeCaptureTokens,
        Instant generatedAt
) {
    public MarketScanReport {
        tradeCaptureTokens = tradeCaptureTokens == null ? Map.of() : Map.copyOf(tradeCaptureTokens);
    }

    public MarketScanReport(
            String scope,
            int universeCount,
            int reviewedCount,
            int candidateCount,
            String quoteNote,
            UniversalScreenCoverage coverage,
            List<String> methodology,
            MarketScanRuleSet ruleSet,
            List<UniversalScreenStageStats> stageStats,
            List<MarketScanCandidate> candidates,
            List<UniversalScreenExclusion> exclusionsSample,
            Instant generatedAt
    ) {
        this(scope, universeCount, reviewedCount, candidateCount, quoteNote, coverage, methodology, ruleSet,
                stageStats, candidates, exclusionsSample, Map.of(), generatedAt);
    }
}
