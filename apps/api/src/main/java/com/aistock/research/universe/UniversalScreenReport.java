package com.aistock.research.universe;

import java.time.Instant;
import java.util.List;

public record UniversalScreenReport(
        String scope,
        int universeCount,
        int reviewedCount,
        int candidateCount,
        String quoteNote,
        UniversalScreenCoverage coverage,
        List<UniversalScreenStageStats> stageStats,
        UniversalScreenRuleSet ruleSet,
        List<UniversalScreenCandidate> candidates,
        List<UniversalScreenExclusion> exclusionsSample,
        Instant generatedAt
) {
}
