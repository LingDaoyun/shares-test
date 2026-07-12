package com.aistock.research.cycle;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record CycleTrialReport(
        String scope,
        int universeCount,
        int candidateCount,
        String quoteNote,
        List<String> methodology,
        CycleTrialRuleSet ruleSet,
        List<CycleTrialCandidate> candidates,
        Map<String, String> tradeCaptureTokens,
        Instant generatedAt
) {
    public CycleTrialReport(
            String scope,
            int universeCount,
            int candidateCount,
            String quoteNote,
            List<String> methodology,
            CycleTrialRuleSet ruleSet,
            List<CycleTrialCandidate> candidates,
            Instant generatedAt
    ) {
        this(scope, universeCount, candidateCount, quoteNote, methodology, ruleSet, candidates,
                Map.of(), generatedAt);
    }
}
