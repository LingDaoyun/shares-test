package com.aistock.research.market;

import com.aistock.research.universe.UniversalScreenExclusion;
import com.aistock.research.universe.UniversalScreenCoverage;
import com.aistock.research.universe.UniversalScreenStageStats;

import java.time.Instant;
import java.util.List;

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
        Instant generatedAt
) {
}
