package com.aistock.research.cycle;

import java.time.Instant;
import java.util.List;

public record CycleTrialReport(
        String scope,
        int universeCount,
        int candidateCount,
        String quoteNote,
        List<String> methodology,
        CycleTrialRuleSet ruleSet,
        List<CycleTrialCandidate> candidates,
        Instant generatedAt
) {
}
